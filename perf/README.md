# measured performance

Reproducible numbers behind two claims the README makes: the snapshot read
path stays flat while the full-history SUM grows with the ledger, and the
async create -> authorize -> capture pipeline holds up under concurrent load.
Everything here runs against the stock `docker compose` Postgres on a laptop —
these are shape-of-the-curve numbers, not capacity planning.

## harnesses

| script | measures | tool |
|---|---|---|
| `balance-bench.sh` | full-history SUM vs snapshot+tail read, same account, growing ledger (200k / 1M / 5M rows) | pgbench, SQL identical to the service's read path |
| `k6/balance.js` | balance endpoint latency under constant arrival rate | k6 (dockerized) |
| `k6/create.js` | synchronous create path (validation, idempotency, tx + outbox intent in one commit) | k6 |
| `k6/flow.js` | create -> poll AUTHORIZED -> capture; `auth_lag_ms` is the outbox+simulator pipeline seen from the client, `capture_ms` the synchronous ledger posting | k6 |

## method

- `balance-bench.sh` seeds balanced two-leg posting pairs directly in SQL
  (triggers bypassed — pairs are balanced by construction and the benchmark
  measures reads, not the insert path), emulates one `SnapshotProcessor` fold,
  inserts a 500-pair un-folded tail, and measures both read shapes with
  pgbench (20-txn warmup, 200 measured txns, single client). Both shapes run
  in explicit transactions, mirroring the service's `@Transactional` reads;
  the snapshot shape issues the same three statements
  `LedgerService.accountBalance` does (cursor, snapshot row, tail SUM).
- k6 runs via `docker run --rm --network=host -v "$PWD/perf/k6:/scripts"
  grafana/k6 run /scripts/<name>.js` against a locally booted jar. Anchoring
  is parked during load runs (`ANCHOR_INTERVAL_MS=86400000
  ANCHOR_INITIAL_DELAY_MS=86400000`): the bulk-seeded synthetic history would
  otherwise make the first epoch a multi-million-leaf Merkle build, which is
  not a workload the anchoring cadence is designed for.

## environment

Laptop-class: i7-1355U (10 cores), 15 GB RAM, Postgres 17 in Docker, app and
db on the same host. Numbers below are for curve shape and relative deltas;
absolute values will differ on server hardware.

## results

### read shape: full-history SUM vs snapshot+tail (pgbench, avg ms)

Same account, same balance, two query shapes. The tail is 500 un-folded pairs
in every run — what a read scans between two folds at the 300s cadence.

| total ledger rows | full SUM | snapshot+tail |
|---|---|---|
| 200,000 | 17.1 ms | 0.49 ms |
| 1,000,000 | 64.9 ms | 0.39 ms |
| 5,000,000 | 225.1 ms | 0.45 ms |

The full SUM is linear in history (~45 µs/row here); the snapshot read is flat
because its cost is the tail, not the ledger. At 5M rows the gap is ~500x.

### balance endpoint p99, before/after wiring it through the snapshot

`GET /api/v1/merchants/{id}/balance` over the 5M-row ledger. "Before" is the
endpoint as it shipped with V23 — the snapshot existed but this endpoint still
aggregated per-currency with a full GROUP BY; "after" routes it through
snapshot rows + post-cursor tail (same commit as these numbers).

| load | before (full SUM) | after (snapshot+tail) |
|---|---|---|
| 2 rps, 60s | p99 337 ms (avg 287 ms) | p99 19.6 ms (avg 14.1 ms) |
| 10 rps, 60s | collapses: sustains 6.4/s, p99 22.4 s, 161 dropped iterations | p99 12.1 ms (avg 7.8 ms), zero drops |
| 100 rps, 60s | not attempted (saturated at 10) | p99 5.6 ms (avg 2.5 ms), 6001/6001 `200` |

The 10 rps row is the honest headline: at 5M rows the full-SUM endpoint could
not even hold ten reads per second — a queueing spiral, not slow-but-stable —
while the snapshot read holds 100 rps with single-digit-millisecond p99 on the
same ledger. (Later runs are faster per-request than the 2 rps run because the
JIT is warm and a scheduled fold absorbed the tail mid-session — exactly the
steady state the cadence maintains.) A correctness cross-check rode along for
free: the endpoint's pending EUR balance moved by exactly 530 captures x 9,800
net between the load runs, so the accelerated read agreed with the raw ledger
to the cent.

### write path (unchanged by the wiring; recorded on the same 5M-row ledger)

- `create.js`, 25 rps for 60s: p99 **7.2 ms** (avg 5.0 ms), 1501/1501 `201`.
  The synchronous create is validation + tx insert + outbox intent in one
  commit; provider dispatch is asynchronous by design.
- `flow.js`, 10 VUs for 120s: 590 payments end-to-end, zero stuck, zero HTTP
  failures; 530 authorized / 60 failed (the simulator's designed 90/10).
  - `auth_lag_ms` (create -> AUTHORIZED/FAILED observed): med 2.03 s,
    p99 2.34 s — pinned to the 2s outbox poll plus the ~500ms simulated
    provider, i.e. the pipeline adds almost nothing on top of its cadence.
  - `capture_ms` (synchronous double-entry posting + deferred balance trigger
    at commit): med 9.2 ms, p99 70 ms.

## honesty notes

- Single-node, client and server share the machine: k6, the JVM and Postgres
  compete for the same cores.
- The SQL bench measures warm-cache reads; a cold ledger scan is worse for
  the full SUM, which only strengthens the snapshot claim.
- The provider is the in-process simulator (~500ms, 90/10), so `auth_lag_ms`
  is dominated by the 2s outbox poll cadence, not provider I/O.
