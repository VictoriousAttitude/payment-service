-- Distributed at-most-once execution for the @Scheduled beans. Under more than
-- one replica every node's scheduler fires on its own timer; ShedLock gates each
-- tick on a row in this table so a given task runs on exactly one node per
-- interval. Without it, N replicas would double-seal anchors, double-fold balance
-- snapshots, double-dispatch the outbox, etc. This is the leader-election gap the
-- anchoring/settlement batches called out, closed with per-task locking (not a
-- single stable leader: different ticks may run on different nodes).
--
-- Managed by ShedLock's JdbcTemplateLockProvider, NOT a JPA entity, so Hibernate
-- ddl-auto=validate never inspects it (same lifecycle as the Camunda ACT_* tables).
-- TIMESTAMP without time zone because the provider is configured usingDbTime():
-- lock windows are measured by the Postgres server clock, immune to inter-node
-- clock skew, and the driver must not apply a session-timezone shift on read-back.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
