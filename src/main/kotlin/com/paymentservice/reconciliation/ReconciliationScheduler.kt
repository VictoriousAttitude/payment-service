package com.paymentservice.reconciliation

import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production driver for reconciliation. The checks in ReconciliationService are
 * only as useful as the thing that runs them — left to a manual endpoint, a
 * ledger corruption or a stuck transaction goes unnoticed until someone looks.
 * This runs the full suite on a fixed schedule and, on any anomaly, emits a
 * single ERROR with a stable marker that a log-based alert rule keys on.
 *
 * Logging is the alert transport on purpose: it has zero infra dependencies and
 * forwards cleanly to whatever the platform already ships logs to (PagerDuty,
 * Opsgenie, a Loki/ELK rule). Metric-based alerting is layered on separately.
 */
@Component
class ReconciliationScheduler(
    private val reconciliationService: ReconciliationService,
    meterRegistry: MeterRegistry,
    @Value("\${payment.reconciliation.stuck-threshold-minutes:30}") private val stuckThresholdMinutes: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // 1 = last sweep clean, 0 = anomalies. Exposed as a gauge so alerting can
    // fire on reconciliation_healthy == 0 rather than scraping logs.
    private val healthy = AtomicInteger(1)
    private val anomalies = meterRegistry.counter("reconciliation.anomalies")

    init {
        meterRegistry.gauge("reconciliation.healthy", healthy)
    }

    @Scheduled(
        fixedDelayString = "\${payment.reconciliation.interval-ms:300000}",
        initialDelayString = "\${payment.reconciliation.initial-delay-ms:60000}"
    )
    @SchedulerLock(name = "reconciliation")
    fun reconcile(): ReconciliationReport {
        val report = reconciliationService.runFullReconciliation(
            Duration.ofMinutes(stuckThresholdMinutes)
        )
        if (report.healthy) {
            healthy.set(1)
            log.info("reconciliation ok: all checks clean, ledger balanced per-currency")
        } else {
            healthy.set(0)
            anomalies.increment()
            alert(report)
        }
        return report
    }

    /**
     * Stable, greppable alert line. ALERT_MARKER is what the alert rule matches;
     * the per-category ids let an operator jump straight to the offending rows.
     */
    private fun alert(report: ReconciliationReport) {
        log.error(
            "$ALERT_MARKER stuck={} missingLedgerEntries={} unbalanced={} amountMismatched={} " +
                "globalBalanced={} snapshotDrift={}",
            report.stuckTransactions,
            report.transactionsWithoutLedgerEntries,
            report.unbalancedPostingGroups,
            report.amountMismatchedTransactions,
            report.globalBalance.balanced,
            report.snapshotDrift
        )
    }

    companion object {
        const val ALERT_MARKER = "RECONCILIATION_ALERT"
    }
}
