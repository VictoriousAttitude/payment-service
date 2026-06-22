package com.paymentservice.dispute

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * A chargeback raised against a captured payment. Modeled as its own aggregate
 * rather than a payment status: disputes have an independent lifecycle and a
 * payment can be settled, refunded, etc. while a dispute runs in parallel.
 */
@Entity
@Table(name = "disputes")
class Dispute(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "transaction_id", nullable = false)
    val transactionId: UUID,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    val reason: DisputeReason,

    @Column(nullable = false)
    val amount: Long,

    @Column(nullable = false, length = 3)
    val currency: String,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: DisputeStatus = DisputeStatus.OPEN,

    @Column(name = "provider_reference")
    val providerReference: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Version
    @Column(nullable = false)
    var version: Long = 0
) {
    fun transitionTo(newStatus: DisputeStatus) {
        status = status.transitionTo(newStatus)
        updatedAt = Instant.now()
    }
}

/**
 * Why the cardholder disputed the charge. Mirrors the common acquirer reason
 * codes (Visa/Mastercard categories, as surfaced by Stripe).
 */
enum class DisputeReason {
    FRAUDULENT,
    PRODUCT_NOT_RECEIVED,
    PRODUCT_UNACCEPTABLE,
    DUPLICATE,
    SUBSCRIPTION_CANCELED,
    CREDIT_NOT_PROCESSED,
    UNRECOGNIZED,
    GENERAL
}
