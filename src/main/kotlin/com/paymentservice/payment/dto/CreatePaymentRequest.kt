package com.paymentservice.payment.dto

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class CreatePaymentRequest(

    val merchantId: UUID,

    @field:Min(value = 1, message = "Amount must be positive")
    val amount: Long,

    @field:NotBlank(message = "Currency is required")
    @field:Size(min = 3, max = 3, message = "Currency must be ISO 4217 (3 chars)")
    val currency: String,

    val description: String? = null,

    val paymentMethod: String? = null
)
