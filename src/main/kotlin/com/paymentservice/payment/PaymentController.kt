package com.paymentservice.payment

import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.payment.dto.PaymentResponse
import com.paymentservice.shared.MERCHANT_ID_ATTRIBUTE
import com.paymentservice.shared.PaymentAccessDeniedException
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
        @RequestAttribute(MERCHANT_ID_ATTRIBUTE) merchantId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: CreatePaymentRequest
    ): ResponseEntity<PaymentResponse> {
        // The merchant is the authenticated caller, never the body. A mismatched
        // body merchantId is a client trying to act for someone else.
        if (request.merchantId != merchantId) {
            throw PaymentAccessDeniedException(merchantId)
        }

        // Provider dispatch is owned by the outbox dispatcher, not fired here —
        // the create + dispatch intent commit atomically, so a crash can't lose
        // the provider call.
        val result = paymentService.createPayment(request, idempotencyKey)

        val status = if (result.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(PaymentResponse.from(result.transaction))
    }

    @GetMapping("/{id}")
    fun getPayment(
        @RequestAttribute(MERCHANT_ID_ATTRIBUTE) merchantId: UUID,
        @PathVariable id: UUID
    ): PaymentResponse {
        return PaymentResponse.from(ownedTransaction(id, merchantId))
    }

    @PostMapping("/{id}/capture")
    fun capturePayment(
        @RequestAttribute(MERCHANT_ID_ATTRIBUTE) merchantId: UUID,
        @PathVariable id: UUID
    ): PaymentResponse {
        ownedTransaction(id, merchantId)
        return PaymentResponse.from(paymentService.capturePayment(id))
    }

    @PostMapping("/{id}/refund")
    fun refundPayment(
        @RequestAttribute(MERCHANT_ID_ATTRIBUTE) merchantId: UUID,
        @PathVariable id: UUID
    ): PaymentResponse {
        ownedTransaction(id, merchantId)
        return PaymentResponse.from(paymentService.refundPayment(id))
    }

    /**
     * Loads a transaction and asserts the authenticated merchant owns it.
     * A cross-merchant id is reported as not-found so the endpoint can't be
     * used to probe whether another merchant's transaction id exists.
     */
    private fun ownedTransaction(id: UUID, merchantId: UUID): Transaction {
        val transaction = paymentService.getPayment(id)
        if (transaction.merchantId != merchantId) {
            throw TransactionNotFoundException(id)
        }
        return transaction
    }
}
