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

_To be filled from the recorded runs._

## honesty notes

- Single-node, client and server share the machine: k6, the JVM and Postgres
  compete for the same cores.
- The SQL bench measures warm-cache reads; a cold ledger scan is worse for
  the full SUM, which only strengthens the snapshot claim.
- The provider is the in-process simulator (~500ms, 90/10), so `auth_lag_ms`
  is dominated by the 2s outbox poll cadence, not provider I/O.
