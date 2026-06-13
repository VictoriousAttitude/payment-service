package com.paymentservice.payment

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Append-only audit record of a single status transition. The transactions row
 * only ever shows the current status; this table is the full history — who went
 * where and when — for dispute handling, debugging, and compliance. Immutability
 * is enforced at the DB (a BEFORE UPDATE/DELETE trigger), like the ledger.
 */
@Entity
@Table(name = "transaction_events")
class TransactionEvent(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "transaction_id", nullable = false)
    val transactionId: UUID,

    // Null only for the initial creation event (no prior state).
    @Column(name = "from_status")
    @Enumerated(EnumType.STRING)
    val fromStatus: PaymentStatus?,

    @Column(name = "to_status", nullable = false)
    @Enumerated(EnumType.STRING)
    val toStatus: PaymentStatus,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)
