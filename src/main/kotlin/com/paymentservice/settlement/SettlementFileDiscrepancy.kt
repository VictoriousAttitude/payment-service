package com.paymentservice.settlement

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One persisted discrepancy from a settlement-file reconciliation run.
 * Append-only, like a ledger entry: a verdict is evidence, never edited
 * (a corrected file arrives as a new upload with a new sha).
 */
@Entity
@Table(name = "settlement_file_discrepancies")
class SettlementFileDiscrepancy(

    @Column(name = "file_id", nullable = false)
    val fileId: UUID,

    @Column(nullable = false)
    val reference: String,

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    val type: FileDiscrepancyType,

    @Column(nullable = false, columnDefinition = "text")
    val detail: String,

    @Column(name = "ledger_value", length = 100)
    val ledgerValue: String? = null,

    @Column(name = "settlement_value", length = 100)
    val settlementValue: String? = null
) {

    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
