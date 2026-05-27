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
    private val paymentService: PaymentService,
    private val providerSimulator: PaymentProviderSimulator
) {

    @PostMapping
    fun createPayment(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: CreatePaymentRequest
    ): ResponseEntity<PaymentResponse> {
        val transaction = paymentService.createPayment(request, idempotencyKey)

        // Trigger async provider simulation (in real system, this would be an external call)
        providerSimulator.simulateAuthorization(transaction.id)

        val isNew = transaction.status == PaymentStatus.PENDING
        val status = if (isNew) HttpStatus.CREATED else HttpStatus.OK

        return ResponseEntity.status(status).body(PaymentResponse.from(transaction))
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
