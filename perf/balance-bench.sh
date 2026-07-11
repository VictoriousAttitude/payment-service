#!/usr/bin/env bash
# Balance-read benchmark: full-history SUM vs the V23 snapshot+tail read path.
#
# Seeds balanced treasury-style posting pairs for the dev merchant directly in
# SQL, emulates one snapshot fold (the same aggregation SnapshotProcessor
# runs), inserts a fresh post-cursor tail, and measures both read shapes with
# pgbench at growing ledger sizes. The claim under test: the full SUM grows
# with history, the snapshot read is bounded by the fold cadence.
#
# Honesty notes:
# - Triggers are bypassed during seeding (session_replication_role = replica):
#   the pairs are balanced by construction and this benchmark measures READS,
#   not the insert path. Nothing else runs against the db during the bench.
# - Both read shapes run inside an explicit transaction, mirroring the
#   service's @Transactional read; the snapshot path issues the same three
#   statements LedgerService.accountBalance does (cursor, snapshot row, tail).
#
# Requires: repo-root `docker compose up -d db` with the schema migrated
# (boot the app once against it). Run: perf/balance-bench.sh

set -euo pipefail
cd "$(dirname "$0")/.."

MERCHANT='a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'   # seeded dev merchant (V1)
INCOMING='b0000000-0000-4000-8000-000000000001'   # arbitrary counter-account
TAIL_PAIRS=${TAIL_PAIRS:-500}      # post-cursor pairs: the un-folded tail
RUNS=${RUNS:-200}                  # pgbench transactions per measurement
SCALES=(100000 500000 2500000)     # cumulative PAIRS (total rows = 2x)

psql_db() { docker compose exec -T db psql -qX -v ON_ERROR_STOP=1 -U postgres -d payments "$@"; }

clean() { # make reruns idempotent: drop previous bench rows and their snapshots
  psql_db <<SQL
SET session_replication_role = replica;
DELETE FROM ledger_entries WHERE description IN ('bench', 'bench-tail');
DELETE FROM ledger_balance_snapshots
WHERE account_id IN ('$MERCHANT', '$INCOMING');
SQL
}

seed_pairs() { # $1..$2 inclusive pair range, spread in the past (pre-cursor)
  psql_db <<SQL
SET session_replication_role = replica;
INSERT INTO ledger_entries
  (transaction_id, account_type, account_id, entry_type, amount, currency,
   description, posting_group_id, created_at)
SELECT NULL, leg.acct, leg.aid::uuid, leg.et, 100, 'EUR', 'bench',
       ('a0000000-0000-4000-8000-' || lpad(to_hex(i), 12, '0'))::uuid,
       now() - interval '30 days' + (i * interval '1 millisecond')
FROM generate_series($1, $2) AS i,
LATERAL (VALUES
  ('INCOMING', '$INCOMING', 'DEBIT'),
  ('MERCHANT', '$MERCHANT', 'CREDIT')
) AS leg(acct, aid, et);
SQL
}

fold() { # emulate one SnapshotProcessor run: fold everything, advance cursor
  psql_db <<SQL
BEGIN;
UPDATE ledger_snapshot_cursor SET as_of = now() WHERE id = 1;
INSERT INTO ledger_balance_snapshots
  (account_type, account_id, currency, total_debits, total_credits)
SELECT account_type, account_id, currency,
       COALESCE(SUM(amount) FILTER (WHERE entry_type = 'DEBIT'), 0),
       COALESCE(SUM(amount) FILTER (WHERE entry_type = 'CREDIT'), 0)
FROM ledger_entries
WHERE created_at <= now()
GROUP BY 1, 2, 3
ON CONFLICT (account_type, account_id, currency) DO UPDATE
  SET total_debits = EXCLUDED.total_debits,
      total_credits = EXCLUDED.total_credits;
COMMIT;
SQL
}

seed_tail() { # fresh pairs strictly after the cursor: what a read must scan
  psql_db <<SQL
SET session_replication_role = replica;
INSERT INTO ledger_entries
  (transaction_id, account_type, account_id, entry_type, amount, currency,
   description, posting_group_id, created_at)
SELECT NULL, leg.acct, leg.aid::uuid, leg.et, 100, 'EUR', 'bench-tail',
       gen_random_uuid(), clock_timestamp()
FROM generate_series(1, $TAIL_PAIRS) AS i,
LATERAL (VALUES
  ('INCOMING', '$INCOMING', 'DEBIT'),
  ('MERCHANT', '$MERCHANT', 'CREDIT')
) AS leg(acct, aid, et);
SQL
}

install_queries() {
  docker compose exec -T db bash -c "cat > /tmp/full_sum.sql" <<SQL
BEGIN;
SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END), 0)
     - COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END), 0)
FROM ledger_entries
WHERE account_type = 'MERCHANT' AND account_id = '$MERCHANT' AND currency = 'EUR';
COMMIT;
SQL
  docker compose exec -T db bash -c "cat > /tmp/snapshot_tail.sql" <<SQL
BEGIN;
-- pgbench has no :'var' string interpolation (psql-only), so the cursor rides
-- as an epoch number; to_timestamp() restores the exact same comparison.
SELECT extract(epoch FROM as_of) AS cursor FROM ledger_snapshot_cursor WHERE id = 1 \gset
SELECT total_credits - total_debits FROM ledger_balance_snapshots
WHERE account_type = 'MERCHANT' AND account_id = '$MERCHANT' AND currency = 'EUR';
SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END), 0)
     - COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END), 0)
FROM ledger_entries
WHERE account_type = 'MERCHANT' AND account_id = '$MERCHANT' AND currency = 'EUR'
  AND created_at > to_timestamp(:cursor);
COMMIT;
SQL
}

bench() { # $1 = script path in container; prints avg latency in ms
  docker compose exec -T db pgbench -n -f "$1" -c 1 -t 20 -U postgres payments >/dev/null  # warmup
  docker compose exec -T db pgbench -n -f "$1" -c 1 -t "$RUNS" -U postgres payments \
    | awk '/latency average/ {print $4}'
}

clean
echo "scale(total rows) | full SUM avg ms | snapshot+tail avg ms (tail=$((TAIL_PAIRS)) pairs)"
prev=0
for pairs in "${SCALES[@]}"; do
  seed_pairs $((prev + 1)) "$pairs"
  prev=$pairs
  fold
  seed_tail
  install_queries
  full=$(bench /tmp/full_sum.sql)
  snap=$(bench /tmp/snapshot_tail.sql)
  echo "$((pairs * 2)) | $full | $snap"
done
