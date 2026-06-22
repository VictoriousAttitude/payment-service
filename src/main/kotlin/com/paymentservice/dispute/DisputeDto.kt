package com.paymentservice.dispute

import jakarta.validation.constraints.Positive
import java.time.Instant
import java.util.UUID

data class OpenDisputeRequest(
    val reason: DisputeReason,
    @field:Positive val amount: Long? = null,
    val providerReference: String? = null
)

data class ResolveDisputeRequest(
    val won: Boolean
)

data class DisputeResponse(
    val id: UUID,
    val transactionId: UUID,
    val reason: DisputeReason,
    val amount: Long,
    val currency: String,
    val status: DisputeStatus,
    val providerReference: String?,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun from(dispute: Dispute) = DisputeResponse(
            id = dispute.id,
            transactionId = dispute.transactionId,
            reason = dispute.reason,
            amount = dispute.amount,
            currency = dispute.currency,
            status = dispute.status,
            providerReference = dispute.providerReference,
            createdAt = dispute.createdAt,
            updatedAt = dispute.updatedAt
        )
    }
}
