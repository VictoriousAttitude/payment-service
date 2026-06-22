package com.paymentservice.payment.dto

import com.paymentservice.payment.PaymentStatus
import com.paymentservice.payment.PaymentView
import com.paymentservice.payment.Transaction
import java.time.Instant
import java.util.UUID

data class PaymentResponse(
    val id: UUID,
    val merchantId: UUID,
    val amount: Long,
    val capturedAmount: Long,
    val refundedAmount: Long,
    val currency: String,
    val status: PaymentStatus,
    val description: String?,
    val providerReference: String?,
    val failureReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun from(
            transaction: Transaction,
            capturedAmount: Long = 0,
            refundedAmount: Long = 0
        ) = PaymentResponse(
            id = transaction.id,
            merchantId = transaction.merchantId,
            amount = transaction.amount,
            capturedAmount = capturedAmount,
            refundedAmount = refundedAmount,
            currency = transaction.currency,
            status = transaction.status,
            description = transaction.description,
            providerReference = transaction.providerReference,
            failureReason = transaction.failureReason,
            createdAt = transaction.createdAt,
            updatedAt = transaction.updatedAt
        )

        fun from(view: PaymentView) =
            from(view.transaction, view.capturedAmount, view.refundedAmount)
    }
}
