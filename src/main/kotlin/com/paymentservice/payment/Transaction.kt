package com.paymentservice.payment

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "transactions")
class Transaction(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "merchant_id", nullable = false)
    val merchantId: UUID,

    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,

    @Column(nullable = false)
    val amount: Long,

    @Column(nullable = false, length = 3)
    val currency: String,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: PaymentStatus = PaymentStatus.CREATED,

    @Column(name = "payment_method", columnDefinition = "jsonb")
    var paymentMethod: String? = null,

    @Column(name = "provider_reference")
    var providerReference: String? = null,

    @Column(name = "failure_reason")
    var failureReason: String? = null,

    val description: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    fun transitionTo(newStatus: PaymentStatus) {
        status = status.transitionTo(newStatus)
        updatedAt = Instant.now()
    }
}
