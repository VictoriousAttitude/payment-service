package com.paymentservice.payment.dto

import com.paymentservice.shared.Iso4217Currency
import com.paymentservice.shared.JsonDocument
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.util.UUID

data class CreatePaymentRequest(

    val merchantId: UUID,

    @field:Min(value = 1, message = "Amount must be positive")
    val amount: Long,

    @field:NotBlank(message = "Currency is required")
    @field:Iso4217Currency
    val currency: String,

    val description: String? = null,

    // persisted into a jsonb column: must be a parseable JSON document or the
    // INSERT fails after validation has already passed (500 instead of 400)
    @field:JsonDocument(message = "paymentMethod must be a valid JSON document")
    val paymentMethod: String? = null
)
