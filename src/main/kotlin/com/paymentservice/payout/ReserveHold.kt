package com.paymentservice.payout

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * One rolling-reserve slice withheld at settlement. The money lives in the
 * ledger (MERCHANT_RESERVE credit at settlement, released to MERCHANT_PAYABLE
 * after the hold period); this row carries the lifecycle and the release
 * schedule. One hold per transaction, enforced by uq_reserve_holds_txn.
 */
@Entity
@Table(name = "reserve_holds")
class ReserveHold(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "transaction_id", nullable = false)
    val transactionId: UUID,

    @Column(name = "merchant_id", nullable = false)
    val merchantId: UUID,

    @Column(nullable = false)
    val amount: Long,

    @Column(nullable = false, length = 3)
    val currency: String,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: ReserveHoldStatus = ReserveHoldStatus.HELD,

    @Column(name = "release_at", nullable = false)
    val releaseAt: Instant,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Version
    @Column(nullable = false)
    var version: Long = 0
) {
    /** HELD -> RELEASED, once. The guard makes a double release throw, not double-post. */
    fun markReleased() {
        check(status == ReserveHoldStatus.HELD) { "Hold $id is already $status" }
        status = ReserveHoldStatus.RELEASED
        updatedAt = Instant.now()
    }
}

enum class ReserveHoldStatus {
    HELD,
    RELEASED
}
