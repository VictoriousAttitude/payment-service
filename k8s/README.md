# Kubernetes deployment

HA Postgres (CloudNativePG, synchronous replication) + a multi-replica app whose
scheduled jobs are single-execution cluster-wide via ShedLock. This is the
substrate the chaos harness (step 4) runs against: kill a pod or the DB primary
and prove the ledger and the anchor chain stay consistent.

## What is here

| File | Purpose |
|------|---------|
| `namespace.yaml` | `payments` ns, enforces the **restricted** Pod Security Standard |
| `postgres-cluster.yaml` | CNPG `Cluster`: 3 instances, quorum synchronous replication, non-superuser app owner |
| `deployment.yaml` | 2 replicas, probe groups, graceful drain, restricted securityContext, read-only rootfs |
| `service.yaml` / `pdb.yaml` / `hpa.yaml` | ClusterIP, `minAvailable: 1`, CPU autoscale 2..6 |
| `configmap.yaml` / `secret.yaml` | non-secret env + placeholder webhook secret (DB creds come from CNPG's `payments-db-app` Secret) |
| `networkpolicy.yaml` | default-deny + allow 8080 to the app tier |
| `kustomization.yaml` | the **core runnable slice** |
| `servicemonitor.yaml` | optional; needs the Prometheus Operator CRDs |
| `postgres-networkpolicy.yaml` | optional; restricts Postgres ingress to app + replication + operator |
| `backup/` | optional; MinIO + **Object-Locked (WORM)** bucket + CNPG continuous backup |

## Prerequisites

```sh
# A local cluster
kind create cluster

# CloudNativePG operator (targeting 1.24+; adjust the release URL to your version)
kubectl apply --server-side -f \
  https://raw.githubusercontent.com/cloudnative-pg/cloudnative-pg/release-1.24/releases/cnpg-1.24.0.yaml

# Optional: metrics-server (for the HPA), Prometheus Operator (for ServiceMonitor)

# Build the image and side-load it (the Deployment uses payment-service:local,
# imagePullPolicy IfNotPresent - nothing is pushed to a registry).
docker build -t payment-service:local .
kind load docker-image payment-service:local
```

## Apply

```sh
# Core: HA Postgres + app.
kubectl apply -k k8s/

# Optional add-ons (each is independent).
kubectl apply -f k8s/servicemonitor.yaml          # after Prometheus Operator
kubectl apply -f k8s/postgres-networkpolicy.yaml  # after the DB is healthy

# OR, instead of `apply -k k8s/`, bring up the WORM-backup variant (superset):
kubectl apply -k k8s/backup/
```

## Verify

```sh
kubectl -n payments get cluster payments-db          # wait for 3/3, one primary
kubectl -n payments get pods -l app=payment-service  # 2/2 Ready
kubectl -n payments port-forward svc/payment-service 8080:80
curl -s localhost:8080/actuator/health/readiness     # {"status":"UP",...} incl db
curl -s localhost:8080/actuator/health/liveness       # UP, and stays UP across a DB failover

# ShedLock rows appear once schedulers first fire (one row per task, locked_by = pod).
kubectl -n payments exec -it payments-db-1 -- psql -U postgres -d payments \
  -c "SELECT name, locked_by, lock_until FROM shedlock;"
```

## Drills (previews of the step-4 chaos harness)

**Failover, no committed-write loss.** Delete the primary; CNPG promotes a
standby and the `-rw` Service re-points to it. App pods briefly go NOT ready
(readiness includes `db`) but do NOT restart (liveness excludes `db`), then
recover. Because commits are synchronous, any payment that returned 2xx is on
the promoted standby.

```sh
kubectl -n payments delete pod payments-db-1   # if it is the primary
```

**Graceful drain.** Delete an app pod mid-traffic. The preStop sleep lets the
endpoint deregister before SIGTERM, then Spring finishes in-flight requests
within the 30s graceful budget (all inside `terminationGracePeriodSeconds: 45`).
A ShedLock a *graceful* pod holds is released on shutdown; a *SIGKILL*ed pod's
lock is instead reclaimed when `lockAtMostFor` expires - the crash safety net.

## Notes and trade-offs

- **NetworkPolicy on kind:** kindnet does not enforce policy (objects apply as
  no-ops). Use Calico/Cilium to see them bite.
- **CNPG API:** `minSyncReplicas`/`maxSyncReplicas` are the widely-supported
  fields; 1.24+ also offers `spec.postgresql.synchronous`. Match the CRD your
  operator ships.
- **Migrations under rollout:** Flyway runs in-app; its `pg_advisory_lock`
  serialises concurrent replica starts (one migrates, the rest no-op). Combined
  with `maxUnavailable: 0`, a bad migration fails readiness and stalls the
  rollout rather than taking the service down. Migrations must stay
  **expand/contract** (backward-compatible) so old and new pods coexist mid-roll.
- **Restricted PSS** applies to CNPG and MinIO too; both run non-root here.
- **WORM retention:** the backup bucket uses COMPLIANCE Object Lock, so no role
  can delete/overwrite a backup before it expires - not even the store's root
  user, and not a compromised DB role. The cost is that lifecycle/pruning is the
  object store's job (CNPG `retentionPolicy` is intentionally unset).
