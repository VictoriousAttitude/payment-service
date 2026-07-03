package com.paymentservice.settlement

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/**
 * RECEIVED is the only non-terminal state: registration and processing run in
 * separate transactions (two-bean split), so a crash between them leaves a
 * resumable RECEIVED row that the identical re-upload finishes. PROCESSED and
 * FAILED are final verdicts; re-uploading the same bytes returns them as-is.
 */
enum class SettlementFileStatus { RECEIVED, PROCESSED, FAILED }

/**
 * One ingested acquirer settlement file: the raw content (kept for
 * audit/replay), its SHA-256 (idempotency key, unique-indexed) and the
 * persisted reconciliation verdict. Ingestion is reconcile-and-alert only -
 * this row never mutates payment state.
 */
@Entity
@Table(name = "settlement_files")
class SettlementFile(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val filename: String,

    @Column(name = "content_sha256", nullable = false, length = 64)
    val contentSha256: String,

    // columnDefinition text, not @Lob: @Lob maps to a Postgres oid, which
    // breaks both reads and Hibernate validate against a TEXT column.
    @Column(nullable = false, columnDefinition = "text")
    val content: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
) {

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: SettlementFileStatus = SettlementFileStatus.RECEIVED

    @Column(name = "line_count", nullable = false)
    var lineCount: Int = 0

    @Column(name = "matched_count", nullable = false)
    var matchedCount: Int = 0

    @Column(name = "pending_count", nullable = false)
    var pendingCount: Int = 0

    @Column(name = "discrepancy_count", nullable = false)
    var discrepancyCount: Int = 0

    @Column(name = "failure_reason", columnDefinition = "text")
    var failureReason: String? = null

    @Column(name = "processed_at")
    var processedAt: Instant? = null

    @Version
    @Column(nullable = false)
    var version: Long = 0
}
