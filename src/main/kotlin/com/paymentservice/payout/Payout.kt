package com.paymentservice.payout

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * A disbursement of the merchant's payable balance. The money movement lives
 * in the ledger (PAYABLE -> PAYOUT_CLEARING at creation, reversed on failure);
 * this row carries the lifecycle the confirm/fail paths transition.
 */
@Entity
@Table(name = "payouts")
class Payout(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "merchant_id", nullable = false)
    val merchantId: UUID,

    @Column(nullable = false)
    val amount: Long,

    @Column(nullable = false, length = 3)
    val currency: String,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: PayoutStatus = PayoutStatus.PENDING,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Version
    @Column(nullable = false)
    var version: Long = 0
) {
    fun transitionTo(newStatus: PayoutStatus) {
        status = status.transitionTo(newStatus)
        updatedAt = Instant.now()
    }
}
