package com.paymentservice.payment.dto

import com.paymentservice.shared.Iso4217Currency
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

    val paymentMethod: String? = null
)
