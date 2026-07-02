package com.paymentservice.payout

import com.paymentservice.shared.MERCHANT_ID_ATTRIBUTE
import com.paymentservice.shared.PaymentAccessDeniedException
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Payouts live under the merchant resource: already inside the API-key
 * filter's protected prefixes, with the same self-only ownership check as the
 * balance endpoint.
 */
@RestController
@RequestMapping("/api/v1/merchants/{id}/payouts")
class PayoutController(
    private val payoutService: PayoutService
) {

    @PostMapping
    fun createPayout(
        @RequestAttribute(MERCHANT_ID_ATTRIBUTE) merchantId: UUID,
        @PathVariable id: UUID,
        @Valid @RequestBody request: CreatePayoutRequest
    ): ResponseEntity<PayoutResponse> {
        assertOwnership(id, merchantId)
        val payout = payoutService.createPayout(id, request.currency, request.amount)
        return ResponseEntity.status(HttpStatus.CREATED).body(PayoutResponse.from(payout))
    }

    @GetMapping
    fun listPayouts(
        @RequestAttribute(MERCHANT_ID_ATTRIBUTE) merchantId: UUID,
        @PathVariable id: UUID
    ): List<PayoutResponse> {
        assertOwnership(id, merchantId)
        return payoutService.getPayoutsForMerchant(id).map(PayoutResponse::from)
    }

    private fun assertOwnership(id: UUID, merchantId: UUID) {
        // A merchant may only move or read its own money.
        if (id != merchantId) {
            throw PaymentAccessDeniedException(merchantId)
        }
    }
}
