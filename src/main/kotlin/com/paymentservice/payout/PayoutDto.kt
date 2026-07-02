package com.paymentservice.payout

import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreatePayoutRequest(
    @field:Size(min = 3, max = 3) val currency: String,
    @field:Positive val amount: Long? = null
)

data class PayoutResponse(
    val id: UUID,
    val merchantId: UUID,
    val amount: Long,
    val currency: String,
    val status: PayoutStatus,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun from(payout: Payout) = PayoutResponse(
            id = payout.id,
            merchantId = payout.merchantId,
            amount = payout.amount,
            currency = payout.currency,
            status = payout.status,
            createdAt = payout.createdAt,
            updatedAt = payout.updatedAt
        )
    }
}
