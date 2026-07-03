package com.paymentservice.settlement

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Rejected before registration; the upload endpoint maps this to 413. */
class SettlementFileTooLargeException(sizeBytes: Int, maxBytes: Long) :
    RuntimeException("settlement file is $sizeBytes bytes, limit is $maxBytes")

/** [created] is false for a byte-identical re-upload or a resumed RECEIVED row. */
data class SettlementFileIngestion(val file: SettlementFile, val created: Boolean)

/**
 * Non-transactional orchestrator of settlement-file ingestion (the other half
 * of the two-bean split): size guard, SHA-256 dedup, register-then-process,
 * metrics and alerting.
 *
 * Idempotency by content hash: the same bytes always map to the same row, so a
 * re-upload returns the persisted verdict instead of reconciling again, and a
 * crash between registration and processing leaves a RECEIVED row that the
 * identical re-upload resumes.
 *
 * Alerting mirrors ReconciliationScheduler: one ERROR line with a stable
 * marker per bad file for log-based alert rules, a healthy gauge for
 * metric-based ones, and per-type discrepancy counters for dashboards.
 */
@Service
class SettlementFileIngestionService(
    private val processor: SettlementFileProcessor,
    private val fileRepository: SettlementFileRepository,
    private val discrepancyRepository: SettlementFileDiscrepancyRepository,
    private val meterRegistry: MeterRegistry,
    @Value("\${payment.settlement-file.max-bytes:1048576}") private val maxBytes: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // 1 = last ingested file clean, 0 = discrepancies or parse failure.
    private val healthy = AtomicInteger(1)

    init {
        meterRegistry.gauge("settlement.file.healthy", healthy)
    }

    fun ingest(filename: String, content: String): SettlementFileIngestion {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) throw SettlementFileTooLargeException(bytes.size, maxBytes)
        val sha = sha256(bytes)

        val existing = fileRepository.findByContentSha256(sha)
        if (existing != null && existing.status != SettlementFileStatus.RECEIVED) {
            return SettlementFileIngestion(existing, created = false)
        }

        val registered = existing ?: register(filename, content, sha)
        val processed = processor.process(registered.id)
        record(processed)
        return SettlementFileIngestion(processed, created = existing == null)
    }

    private fun register(filename: String, content: String, sha: String): SettlementFile =
        try {
            processor.register(filename, content, sha)
        } catch (e: DataIntegrityViolationException) {
            // lost the unique-index race to a concurrent identical upload
            fileRepository.findByContentSha256(sha) ?: throw e
        }

    private fun record(file: SettlementFile) {
        when {
            file.status == SettlementFileStatus.FAILED -> {
                meterRegistry.counter(INGESTED_METRIC, "result", "failed").increment()
                healthy.set(0)
                log.error(
                    "$ALERT_MARKER file={} filename={} rejected: {}",
                    file.id,
                    file.filename,
                    file.failureReason
                )
            }
            file.discrepancyCount > 0 -> {
                meterRegistry.counter(INGESTED_METRIC, "result", "discrepancies").increment()
                healthy.set(0)
                alert(file)
            }
            else -> {
                meterRegistry.counter(INGESTED_METRIC, "result", "clean").increment()
                healthy.set(1)
                log.info(
                    "settlement file ok: file={} matched={} pending={}",
                    file.id,
                    file.matchedCount,
                    file.pendingCount
                )
            }
        }
    }

    /** Stable, greppable alert line, same contract as RECONCILIATION_ALERT. */
    private fun alert(file: SettlementFile) {
        val countsByType = discrepancyRepository.findByFileIdOrderByReference(file.id)
            .groupingBy { it.type }
            .eachCount()
        countsByType.forEach { (type, count) ->
            meterRegistry.counter(DISCREPANCY_METRIC, "type", type.name).increment(count.toDouble())
        }
        log.error(
            "$ALERT_MARKER file={} filename={} matched={} pending={} discrepancies={} byType={}",
            file.id,
            file.filename,
            file.matchedCount,
            file.pendingCount,
            file.discrepancyCount,
            countsByType
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val ALERT_MARKER = "SETTLEMENT_FILE_ALERT"
        private const val INGESTED_METRIC = "settlement.files.ingested"
        private const val DISCREPANCY_METRIC = "settlement.file.discrepancies"
    }
}
