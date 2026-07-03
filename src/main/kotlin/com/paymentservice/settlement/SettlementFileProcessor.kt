package com.paymentservice.settlement

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Transactional half of the ingestion two-bean split (same reasoning as the
 * outbox: @Transactional on a method invoked from within its own bean is a
 * self-invocation the proxy never sees, so the orchestrator and the
 * transactional worker are separate beans).
 *
 * [register] and [process] are deliberately separate transactions: a parse
 * failure still persists the FAILED verdict, and a crash between the two
 * commits leaves a resumable RECEIVED row that the identical re-upload
 * finishes. Ingestion is reconcile-and-alert only; nothing here mutates
 * payment state.
 */
@Component
class SettlementFileProcessor(
    private val fileRepository: SettlementFileRepository,
    private val discrepancyRepository: SettlementFileDiscrepancyRepository,
    private val extractService: SettlementExtractService,
    @Value("\${payment.settlement-file.window-days:2}") private val windowDays: Long
) {

    /**
     * Persists the RECEIVED row. The unique sha index is the concurrency
     * backstop: a concurrent identical upload loses the race with a
     * DataIntegrityViolationException, which the orchestrator resolves by
     * reloading the winner's row (this transaction is already rolled back).
     */
    @Transactional
    fun register(filename: String, content: String, sha256: String): SettlementFile =
        fileRepository.saveAndFlush(
            SettlementFile(filename = filename, contentSha256 = sha256, content = content)
        )

    /**
     * Parses and reconciles a RECEIVED file to its final verdict. Idempotent:
     * a file already PROCESSED or FAILED is returned as-is, so re-invoking on
     * a resumed row or a lost race is safe. The whole verdict (status, counts,
     * discrepancy rows) commits atomically.
     */
    @Transactional
    fun process(fileId: UUID): SettlementFile {
        val file = fileRepository.findById(fileId)
            .orElseThrow { IllegalStateException("settlement file $fileId not registered") }
        if (file.status != SettlementFileStatus.RECEIVED) return file
        try {
            reconcile(file, AcquirerCsvParser.parse(file.content))
        } catch (e: SettlementFileParseException) {
            file.status = SettlementFileStatus.FAILED
            file.failureReason = e.message
        }
        file.processedAt = Instant.now()
        return file
    }

    /**
     * Full-ledger extract per file is a known tradeoff inherited from
     * [SettlementExtractService.extract] (findAll-based): acceptable at demo
     * scale, and scoping the extract to the file's date range is a documented
     * production gap.
     */
    private fun reconcile(file: SettlementFile, parsed: List<AcquirerSettlementLine>) {
        val result = SettlementFileReconciler.reconcile(
            ledger = extractService.extract(),
            file = parsed,
            asOf = Instant.now(),
            window = Duration.ofDays(windowDays)
        )
        discrepancyRepository.saveAll(
            result.discrepancies.map {
                SettlementFileDiscrepancy(
                    fileId = file.id,
                    reference = it.reference,
                    type = it.type,
                    detail = it.detail,
                    ledgerValue = it.ledgerValue,
                    settlementValue = it.settlementValue
                )
            }
        )
        file.lineCount = parsed.size
        file.matchedCount = result.matchedCount
        file.pendingCount = result.pending.size
        file.discrepancyCount = result.discrepancies.size
        file.status = SettlementFileStatus.PROCESSED
    }
}
