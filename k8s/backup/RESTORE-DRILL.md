# PITR restore drill — executed 2026-07-11

A backup you have never restored is a hope, not a backup. This is the record of
a full point-in-time-recovery drill executed against the live kind cluster:
base backup to the WORM bucket, writes on both sides of a target instant,
recovery to that instant, and independent cryptographic verification of the
restored ledger history. Every command below was run; outputs are quoted.

## Preconditions

- `kubectl apply -k k8s/backup/` applied; `Cluster payments-db` shows
  `ContinuousArchiving: True` (first WAL archive fails until the bucket Job
  completes — the operator retries and the condition self-heals).
- The bucket `payments-backups` was created with `--with-lock` and a default
  `COMPLIANCE 30d` retention (see `minio-bucket-job.yaml`).

## 1. Base backup

```sh
kubectl apply -f - <<'EOF'
apiVersion: postgresql.cnpg.io/v1
kind: Backup
metadata:
  name: payments-db-drill
  namespace: payments
spec:
  cluster:
    name: payments-db
EOF
kubectl wait --for=jsonpath='{.status.phase}'=completed backup/payments-db-drill -n payments --timeout=180s
```

Result: `completed`, WAL range `0000001500000000000000B8..B9`, 14:57:19Z.

## 2. WORM negative test

The tamper-proofing claim is only real if a privileged deletion actually
fails. Attempted as the MinIO **root** user, from inside the minio pod
(`export HOME=/tmp` first — `mc` needs a writable home):

- A plain `mc rm local/payments-backups/<object>` "succeeds" but only writes a
  **delete marker**: Object Lock forces versioning, so the locked data version
  is untouched and the object is fully recoverable by removing the marker
  (`mc rm --version-id <marker-id>`). Do not mistake this for a broken lock —
  and do not let a monitoring check mistake a delete marker for data loss.
- The real test is a permanent version delete:

```
mc rm --version-id 099faa17-... local/payments-backups/payments-db/base/20260711T145714/backup.info
mc: <ERROR> ... is WORM protected and cannot be overwritten   (exit 1)
```

Denied for root. `mc stat` afterwards shows the object intact. That is the
COMPLIANCE-mode property: no credential can destroy backup history before
retention expires.

## 3. Writes on both sides of the target instant

- **Phase A** (before backup): two captured EUR 100.00 payments → 6 ledger
  entries; the anchor scheduler sealed **epoch 1** over them
  (root `d3da3752…`, chain `09d59cf8…`, leaf_count 6).
- **Target time T** recorded from the primary: `SELECT now()` →
  `2026-07-11 14:58:44.303304+00`.
- **Phase B** (after T): two more captured payments → live cluster at 12
  ledger entries.
- `SELECT pg_switch_wal()` to force the segment holding phase B into the
  archive (`pg_stat_archiver.last_archived_wal` advanced to `…B9`).

## 4. Recovery to T

```sh
# edit targetTime in restore-cluster.yaml, then:
kubectl apply -f k8s/backup/restore-cluster.yaml
kubectl wait --for=condition=Ready cluster/payments-db-restore -n payments --timeout=300s
```

Ready in about a minute. Verification against `payments-db-restore-1`:

| check | live cluster | restored at T |
|---|---|---|
| ledger_entries | 12 | **6** |
| phase A transactions | CAPTURED | **CAPTURED** |
| phase B transactions | CAPTURED | **absent (0 rows)** |
| anchor epoch 1 root | `d3da3752…` | `d3da3752…` (identical) |

Exactly the pre-T history, nothing after it.

## 5. Independent cryptographic verification

Row counts prove presence, not integrity. The Merkle anchor chain proves the
restored bytes are the same history that was anchored before the backup:

1. Boot the app jar against the restored DB (port-forward
   `payments-db-restore-rw`, all scheduler initial delays parked so the drill
   writes nothing). The restored data dir keeps the original `payments` role
   password, i.e. the source cluster's `payments-db-app` secret — the restore
   cluster's own generated `-app` secret is for the default `app` user and is
   not what the application uses.
2. Export `GET /api/v1/ledger-anchors` and
   `GET /api/v1/ledger-anchors/1/leaves`.
3. `cd recon && uv run anchorverify --anchors anchors.json --leaves-dir leaves`

```
epochs verified: 1
failures:        0
CLEAN            (exit 0)
```

The zero-dependency Python verifier recomputed the RFC 6962 root and chain
hash from the restored leaves and matched the anchors. Restore is
byte-consistent with the pre-incident ledger.

## 6. Cleanup

```sh
kubectl delete cluster payments-db-restore -n payments
```

The PVC is garbage-collected with the cluster. The base backup and WAL stay in
the bucket — COMPLIANCE retention means they could not be deleted anyway.

## Known deprecation

CNPG 1.30 warns that in-tree `barmanObjectStore` support (used by
`cluster-backup-patch.yaml` and `restore-cluster.yaml`) is removed in 1.31 in
favor of the Barman Cloud Plugin. The drill runs on 1.30; migrating to the
plugin CRDs is a known follow-up before any operator upgrade.
