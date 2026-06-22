package com.paymentservice.payment.dto

import jakarta.validation.constraints.Positive

/**
 * Optional body for capture/refund. A null [amount] means "the rest": full
 * remaining capturable on capture, full remaining refundable on refund. A
 * present amount must be positive; the service bounds it against the ledger.
 */
data class CaptureRefundRequest(
    @field:Positive(message = "Amount must be positive")
    val amount: Long? = null
)
