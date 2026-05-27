package com.paymentservice.payment

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Webhook endpoint for external payment provider callbacks.
 * In production: provider calls this URL when payment status changes.
 * Would include signature verification to prevent spoofed callbacks.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
class WebhookController(
    private val paymentService: PaymentService
) {

    @PostMapping("/provider-callback")
    fun handleProviderCallback(@RequestBody request: ProviderCallbackRequest): ResponseEntity<Void> {
        paymentService.handleProviderCallback(
            transactionId = request.transactionId,
            authorized = request.authorized,
            providerReference = request.providerReference
        )
        return ResponseEntity.ok().build()
    }
}

data class ProviderCallbackRequest(
    val transactionId: UUID,
    val authorized: Boolean,
    val providerReference: String? = null
)
