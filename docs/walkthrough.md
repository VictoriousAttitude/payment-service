# a 10-minute walkthrough

The repo's claim is simple: the money is correct, and every layer that makes it
correct can be demonstrated, not just described. This tour runs the
demonstrations in order. Each stop says what to run, what you should see, and
where the corresponding code lives. Total time is about ten minutes with the
stack already built.

```bash
docker compose up --build     # postgres + the service; wait for "Started"
KEY="test-api-key-123"
MID="a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"
```

## minute 1-2 — a payment becomes ledger rows

```bash
# create: commits the transaction AND an outbox event atomically; a dispatcher
# delivers to the provider simulator, which posts a signed webhook back
ID=$(curl -s -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" -H "X-Api-Key: $KEY" \
  -H "Idempotency-Key: tour-001" \
  -d "{\"merchantId\":\"$MID\",\"amount\":10000,\"currency\":\"EUR\"}" | jq -r .id)

# ~2-3s later the async authorization lands (90% approve rate by design)
curl -s http://localhost:8080/api/v1/payments/$ID -H "X-Api-Key: $KEY" | jq .status

# capture: status transition + double-entry postings in one transaction
curl -s -X POST http://localhost:8080/api/v1/payments/$ID/capture -H "X-Api-Key: $KEY" | jq .

# the balance is derived from the ledger, never a stored counter: 10000 - 2% fee
curl -s http://localhost:8080/api/v1/merchants/$MID/balance -H "X-Api-Key: $KEY" | jq .
```

Look at the postings themselves — one balanced group per capture (INCOMING
10000 debit, MERCHANT 9800 credit, PLATFORM 200 fee credit):

```bash
docker compose exec db psql -U postgres -d payments -c \
  "SELECT account_type, entry_type, amount FROM ledger_entries ORDER BY created_at DESC LIMIT 3;"
```

Code: `payment/PaymentService.kt` (state machine + atomicity),
`ledger/LedgerService.kt` (fee floor, `validateBalance`), `payment/outbox/`
(SKIP LOCKED dispatcher, backoff, dead-letter).

## minute 3 — the database refuses what the application cannot

The guards live below the application, so a bug in the service cannot commit
bad money. Two you can trip by hand:

```bash
# the ledger is append-only by trigger (V7) — even a superuser UPDATE is rejected
docker compose exec db psql -U postgres -d payments -c \
  "UPDATE ledger_entries SET amount = 0 WHERE amount > 0;"
# ERROR: ledger_entries are immutable: UPDATE is not allowed

# replaying an idempotency key with a DIFFERENT body is a 422, not a duplicate charge
curl -s -X POST http://localhost:8080/api/v1/payments \
  -H "Content-Type: application/json" -H "X-Api-Key: $KEY" \
  -H "Idempotency-Key: tour-001" \
  -d "{\"merchantId\":\"$MID\",\"amount\":99999,\"currency\":\"EUR\"}" | jq .
```

The rest are deferred constraint triggers checked at commit: per-posting-group
balance (V15/V18), terminal statuses absorbing (V16), captured ≤ authorized and
refunded ≤ captured (V17), payouts never drive payable below zero (V20).
Migrations: `src/main/resources/db/migration/`. Integration tests trip every
one of them: `LedgerBalanceConstraintTest`, `TransactionTerminalStatusTest`,
`PayableFloorConstraintTest`, `TransactionHistoryTest`.

## minute 4-5 — tamper evidence: edit history as a superuser, get caught

The append-only trigger stops the app; it does not stop a superuser or a
tampered restore. Epoch Merkle anchoring (RFC 6962, hash-chained roots) makes
that visible by recomputation. Full runbook in the README
(["ledger anchoring"](../README.md#ledger-anchoring-tamper-evidence)); the
short version:

```bash
# export the chain + leaves, verify offline: CLEAN, exit 0
curl -s http://localhost:8080/api/v1/ledger-anchors -o anchors.json
mkdir -p leaves && for e in $(jq -r '.[].epoch' anchors.json); do
  curl -s "http://localhost:8080/api/v1/ledger-anchors/$e/leaves" -o "leaves/$e.csv"; done
(cd recon && uv run anchorverify --anchors ../anchors.json --leaves-dir ../leaves)

# tamper below every control (disable trigger, edit, re-enable), re-verify:
# both the JVM /verify endpoint and the Python verifier flag ROOT_MISMATCH, exit 1
```

The two verifiers share nothing but golden byte vectors — a bug in the writer
cannot hide in the checker. Code: `ledger/` (tree, canonical leaf codec,
sealing), `recon/src/recon/anchorverify.py`.

## minute 6 — three-way settlement reconciliation

The one input genuinely independent of this codebase is the acquirer's
settlement file, so it is reconciled twice — JVM online, Python offline — and
an adversary (`procsim`) injects every discrepancy class to prove both catch
all of them. Runbook: README
(["three-way reconciliation"](../README.md#three-way-reconciliation)). The
punchline to try: seed a `wrong_fee` fault, watch `recon` exit 1 with
`FEE_MISMATCH`, then upload the same bytes to `POST /api/v1/settlement-files`
and read the identical verdict from the JVM.

## minute 7 — the bug the conformance suite found

`mbt/` drives the live API in lockstep with a pure Python reference model
(Hypothesis stateful testing) — interleaved partial captures, refunds, voids,
idempotent replays across several payments, checked after every step. On its
first live run it found a real fault no example-based test had: a bare token
like `card` in `paymentMethod` passed bean validation and 500'd at the jsonb
INSERT. Now it is a 400 at the gate (`@JsonDocument`), and a machine rule pins
the contract.

```bash
cd mbt && uv run pytest tests/test_live_conformance.py -q
```

## minute 8 — chaos: kill the primary, keep the money

`chaos/` is a jepsen-lite harness against the `k8s/` deployment: a workload
issues concurrent payments while a nemesis SIGKILLs app pods and the CNPG
primary. The key discipline is the ok/fail/info outcome trichotomy — a timeout
is *unknown*, not failed — which is what makes "every acknowledged create
survives" (P1) and "one idempotency key, at most one transaction" (P2)
meaningful. The verdict is recomputed by a pure checker from artifacts alone:

```bash
chaos check --history history.jsonl --final final.json   # same verdict on any machine
```

## minute 9 — performance is measured, not asserted

Balances read through a rolling checkpoint (V23) instead of a full-history
SUM. `perf/` holds the harness and honesty notes; headline numbers on a 5M-row
ledger (single laptop, warm cache):

| read path | 200k rows | 1M | 5M |
|---|---|---|---|
| full SUM | 17.1 ms | 64.9 ms | 225.1 ms |
| snapshot + tail | ~0.4 ms | ~0.4 ms | ~0.5 ms |

At the API: the endpoint that collapsed at 10 req/s on the full SUM (p99 22.4 s,
dropped iterations) holds 100 req/s at p99 5.6 ms on the snapshot path. The
exactness cross-check: under a 590-payment load run, pending EUR moved by
exactly 530 × 9800. Details: [`perf/README.md`](../perf/README.md).

## minute 10 — the restore drill: a backup that was actually restored

The WORM backup variant (`k8s/backup/`) was not left theoretical. The executed
PITR drill — base backup to a COMPLIANCE-locked MinIO bucket, writes on both
sides of a target instant, recovery to that instant, phase-B rows provably
absent — ends with the strongest possible restore check: the Python verifier
recomputes the Merkle chain from the *restored* ledger and reports CLEAN. Also
in there: the proof that even MinIO's root user cannot permanently delete a
backup object (`is WORM protected`, exit 1), and the delete-marker pitfall that
looks like data loss but isn't. Full record with quoted outputs:
[`k8s/backup/RESTORE-DRILL.md`](../k8s/backup/RESTORE-DRILL.md).

## where to go deeper

- design rationale and tradeoffs, objection by objection: [`README.md`](../README.md)
- the five-layer integrity model diagram: README, "ledger integrity"
- k8s failover/drain drills: [`k8s/README.md`](../k8s/README.md)
- every gap that is real is listed, not hidden: README, "production gaps"
