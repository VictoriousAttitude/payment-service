# chaos - jepsen-lite chaos harness

The step-3 deployment makes claims: quorum synchronous replication loses no
acknowledged commit on a Postgres failover, ShedLock hands scheduled work to a
surviving replica, graceful drain plus readiness-gating loses no in-flight
request, and the epoch Merkle anchor chain stays intact across a crash. This
harness is the thing that turns those claims into a **pass/fail gate** by
breaking the cluster on purpose and then proving the ledger is still correct.

It follows Jepsen's shape: a workload generator issues operations while a
nemesis injects faults, every attempt is recorded to a history, and a pure
checker decides afterwards whether the history plus the final observed state is
consistent with the promised model.

## Design

**Functional core, imperative shell** (same split as `recon/`). `domain/` is
pure and has no I/O: the history model and the `check()` function. `driver/`
is the dirty part: the HTTP workload, the kubectl nemesis, the collector. The
CLI wires them. Only the core carries the correctness, so only the core is
unit and property tested and mutation gated. The driver needs a live cluster
and is exercised by actually running it, not by unit tests.

**The outcome trichotomy is the whole point.** A distributed operation has
three results, not two:

- `OK`   the server answered 2xx. The write DEFINITELY happened and must be
  durable across the fault.
- `FAIL` a clean 4xx. The write DEFINITELY did not happen and must leave no
  trace.
- `INFO` unknown: a timeout, a dropped socket mid-kill, or a 5xx. The write MAY
  or MAY NOT have applied.

Collapsing `INFO` into `FAIL` is the classic lost-update bug. Keeping it
distinct is why a history is recorded instead of just asserting on final state,
and it is what makes the idempotency check (P2) meaningful: the workload retries
the SAME idempotency key on `INFO`, and a correct server collapses the retries
onto one transaction.

**What the harness genuinely adds is P1 and P2.** Those need the client's view
of what was acknowledged, which the service cannot know about itself. The
remaining properties assert the service's own post-fault self-reports
(reconciliation, anchor verify) still come back clean, which is what correlates
"the reconciler says it is fine" with a specific injected fault.

## Properties checked

| # | Property | Fault it defends against |
|---|----------|--------------------------|
| P1 | every 2xx create survives in final state | committed-write loss on DB failover |
| P2 | one idempotency key yields at most one transaction | retry double-charge under uncertainty |
| P3 | unknown-outcome ops are all-or-nothing (no unbalanced posting group) | a capture cut off mid-flight |
| P4 | global double-entry balance per currency | any torn ledger write |
| P5 | the O(1) snapshot still equals the naive ledger SUM | an interrupted or double fold |
| P6 | the epoch Merkle anchor chain still verifies | a crash mid-anchor |
| P7 | after quiescence nothing is stuck / unposted | a scheduled job that did not resume on the survivor |

## Faults (the nemesis)

- `kill_app_pod`: `kubectl delete pod ... --grace-period=0 --force` on a random
  payment-service pod. SIGKILL, not graceful, so the process dies mid-request
  and the crash safety nets are exercised (ShedLock `lockAtMostFor` reclaim,
  the survivor picking up batch work), not the happy-path drain.
- `kill_db_primary`: force-deletes the CNPG primary, triggering a failover. The
  promoted standby must already hold every acknowledged commit (synchronous
  replication), which is what P1 proves end to end.

## Run against a live cluster

Bring up the step-3 cluster first (see `../k8s/README.md`), then:

```sh
python -m venv .venv && . .venv/bin/activate
pip install pytest hypothesis            # dev only; the harness itself is zero-dep

kubectl -n payments port-forward svc/payment-service 8080:80 &

chaos run \
  --base-url http://localhost:8080 \
  --api-key test-api-key-123 \
  --merchant-id a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11 \
  --nemesis both --ops 200 --threads 8 --period 5 --settle 30 \
  --history history.jsonl --final final.json
echo "exit: $?"     # non-zero if any property was violated
```

`run` records `history.jsonl` (append-only, flushed per op, so it survives even
if the harness process itself is killed) and `final.json`. The exit code is
non-zero on any violation, so CI or a cron step fails loudly.

## Re-check offline (no cluster)

A failing run is reproducible from its artifacts alone:

```sh
chaos check --history history.jsonl --final final.json
```

This runs only the pure checker. Same verdict on any machine, which is how the
core is gated in CI without a cluster.

## Test

```sh
pytest -q                 # unit + hypothesis property tests for the pure core
ruff check . && mypy
mutmut run                # zero-survivor gate on domain/ (history + checker)
```
