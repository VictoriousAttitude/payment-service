package com.paymentservice.payment

import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.payment.dto.PaymentResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/payments")
class PaymentController(
    private val paymentService: PaymentService
) {

    @PostMapping
    fun createPayment(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: CreatePaymentRequest
    ): ResponseEntity<PaymentResponse> {
        // Provider dispatch is owned by the outbox dispatcher, not fired here —
        // the create + dispatch intent commit atomically, so a crash can't lose
        // the provider call.
        val result = paymentService.createPayment(request, idempotencyKey)

        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(PaymentResponse.from(result.transaction))
    }

    @GetMapping("/{id}")
    fun getPayment(@PathVariable id: UUID): PaymentResponse {
        return PaymentResponse.from(paymentService.getPayment(id))
    }

    @PostMapping("/{id}/capture")
    fun capturePayment(@PathVariable id: UUID): PaymentResponse {
        return PaymentResponse.from(paymentService.capturePayment(id))
    }

    @PostMapping("/{id}/refund")
    fun refundPayment(@PathVariable id: UUID): PaymentResponse {
        return PaymentResponse.from(paymentService.refundPayment(id))
    }
}
