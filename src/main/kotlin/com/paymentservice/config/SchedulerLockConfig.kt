package com.paymentservice.config

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

/**
 * Distributed at-most-once execution for every @Scheduled bean. Under more than
 * one replica each node's scheduler fires on its own timer; ShedLock gates each
 * tick on a shared DB lock (the `shedlock` table) so a task runs on exactly one
 * node per interval. This is per-task locking, not a single stable leader:
 * different ticks may run on different nodes, which is the correct primitive for
 * scheduler fan-out (a dead leader can't stall every job at once).
 *
 * usingDbTime() measures the lock window with the Postgres server clock instead
 * of each node's JVM clock, so lock expiry is immune to inter-node clock skew.
 *
 * defaultLockAtMostFor is the crash safety net: if a holder dies mid-run the lock
 * auto-expires after this and another node may take over. lockAtLeastFor is left
 * at its default PT0S, so a completed tick releases its lock immediately - this
 * is what keeps the test suite's sequential direct calls (dispatchPending(),
 * reconcile(), ...) working: each returns and unlocks before the next acquires.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
class SchedulerLockConfig {

    @Bean
    fun lockProvider(dataSource: DataSource): LockProvider =
        JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(JdbcTemplate(dataSource))
                .usingDbTime()
                .build()
        )
}
