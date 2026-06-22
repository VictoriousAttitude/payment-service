package com.paymentservice.payment

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * One capture or refund applied to a transaction. A transaction can have many
 * of each (multi-capture, partial refund). The optional idempotency key makes a
 * single operation safely retryable: a replay with the same key returns the
 * current state instead of posting a second ledger set.
 */
@Entity
@Table(name = "payment_operations")
class PaymentOperation(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "transaction_id", nullable = false)
    val transactionId: UUID,

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    val type: OperationType,

    @Column(nullable = false)
    val amount: Long,

    @Column(name = "idempotency_key")
    val idempotencyKey: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)

enum class OperationType { CAPTURE, REFUND }
