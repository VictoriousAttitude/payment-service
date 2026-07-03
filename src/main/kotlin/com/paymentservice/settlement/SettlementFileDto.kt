package com.paymentservice.settlement

import java.time.Instant
import java.util.UUID

class SettlementFileNotFoundException(id: UUID) : RuntimeException("Settlement file $id not found")

/** Persisted verdict of one ingested file; the POST and list representation. */
data class SettlementFileSummaryResponse(
    val id: UUID,
    val filename: String,
    val status: SettlementFileStatus,
    val lineCount: Int,
    val matchedCount: Int,
    val pendingCount: Int,
    val discrepancyCount: Int,
    val failureReason: String?,
    val createdAt: Instant,
    val processedAt: Instant?
) {
    companion object {
        fun from(file: SettlementFile) = SettlementFileSummaryResponse(
            id = file.id,
            filename = file.filename,
            status = file.status,
            lineCount = file.lineCount,
            matchedCount = file.matchedCount,
            pendingCount = file.pendingCount,
            discrepancyCount = file.discrepancyCount,
            failureReason = file.failureReason,
            createdAt = file.createdAt,
            processedAt = file.processedAt
        )
    }
}

data class FileDiscrepancyResponse(
    val reference: String,
    val type: FileDiscrepancyType,
    val detail: String,
    val ledgerValue: String?,
    val settlementValue: String?
) {
    companion object {
        fun from(discrepancy: SettlementFileDiscrepancy) = FileDiscrepancyResponse(
            reference = discrepancy.reference,
            type = discrepancy.type,
            detail = discrepancy.detail,
            ledgerValue = discrepancy.ledgerValue,
            settlementValue = discrepancy.settlementValue
        )
    }
}

data class SettlementFileDetailResponse(
    val file: SettlementFileSummaryResponse,
    val discrepancies: List<FileDiscrepancyResponse>
)
