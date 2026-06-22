package com.paymentservice.dispute

import com.paymentservice.payment.PaymentService
import com.paymentservice.payment.TransactionNotFoundException
import com.paymentservice.shared.MERCHANT_ID_ATTRIBUTE
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Disputes live under the payment they contest so the API-key filter and the
 * merchant-ownership check both apply unchanged. Open/resolve stand in for the
 * acquirer's chargeback events; in production these arrive as signed provider
 * webhooks, but the money model is identical.
 */
@RestController
@RequestMapping("/api/v1/payments/{transactionId}/disputes")
class DisputeController(
    private val disputeService: DisputeService,
    private val paymentService: PaymentService
) {

    @PostMapping
    fun openDispute(
        @RequestAttribute(MERCHANT_ID_ATTRIBUTE) merchantId: UUID,
        @PathVariable transactionId: UUID,
        @Valid @RequestBody request: OpenDisputeRequest
    ): ResponseEntity<DisputeResponse> {
        assertOwnership(transactionId, merchantId)
        val dispute = disputeService.openDispute(
            transactionId, request.reason, request.amount, request.providerReference
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(DisputeResponse.from(dispute))
    }

    @GetMapping
    fun listDisputes(
        @RequestAttribute(MERCHANT_ID_ATTRIBUTE) merchantId: UUID,
        @PathVariable transactionId: UUID
    ): List<DisputeResponse> {
        assertOwnership(transactionId, merchantId)
        return disputeService.getDisputesForTransaction(transactionId).map(DisputeResponse::from)
    }

    @PostMapping("/{disputeId}/evidence")
    fun submitEvidence(
        @RequestAttribute(MERCHANT_ID_ATTRIBUTE) merchantId: UUID,
        @PathVariable transactionId: UUID,
        @PathVariable disputeId: UUID
    ): DisputeResponse {
        assertOwnership(transactionId, merchantId)
        return DisputeResponse.from(disputeService.submitEvidence(ownedDispute(disputeId, transactionId)))
    }

    @PostMapping("/{disputeId}/resolve")
    fun resolveDispute(
        @RequestAttribute(MERCHANT_ID_ATTRIBUTE) merchantId: UUID,
        @PathVariable transactionId: UUID,
        @PathVariable disputeId: UUID,
        @Valid @RequestBody request: ResolveDisputeRequest
    ): DisputeResponse {
        assertOwnership(transactionId, merchantId)
        return DisputeResponse.from(disputeService.resolve(ownedDispute(disputeId, transactionId), request.won))
    }

    private fun assertOwnership(transactionId: UUID, merchantId: UUID) {
        val transaction = paymentService.getPayment(transactionId)
        if (transaction.merchantId != merchantId) {
            // Report as not-found so the path can't probe another merchant's ids.
            throw TransactionNotFoundException(transactionId)
        }
    }

    /** Ensures the dispute belongs to the path's transaction before acting on it. */
    private fun ownedDispute(disputeId: UUID, transactionId: UUID): UUID {
        val dispute = disputeService.getDispute(disputeId)
        if (dispute.transactionId != transactionId) {
            throw DisputeNotFoundException(disputeId)
        }
        return disputeId
    }
}
