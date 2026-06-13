package com.paymentservice.payment

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * Webhook endpoint for external payment provider callbacks.
 * The provider POSTs here when a payment's status changes.
 *
 * Security boundary: the body is HMAC-signed (X-Webhook-Signature). Without
 * verification, any caller reaching this URL could authorize arbitrary
 * payments by guessing a transaction id. The signature is verified over the
 * RAW body before deserialization so it can't drift from what was signed.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
class WebhookController(
    private val paymentService: PaymentService,
    private val webhookSigner: WebhookSigner,
    private val objectMapper: ObjectMapper
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/provider-callback")
    fun handleProviderCallback(
        @RequestBody rawBody: String,
        @RequestHeader(name = "X-Webhook-Signature", required = false) signature: String?
    ): ResponseEntity<Void> {
        if (signature == null || !webhookSigner.isValid(rawBody, signature)) {
            log.warn("Rejected webhook: missing or invalid signature")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        val request = objectMapper.readValue(rawBody, ProviderCallbackRequest::class.java)
        paymentService.handleProviderCallback(
            transactionId = request.transactionId,
            authorized = request.authorized,
            providerReference = request.providerReference
        )
        // Always 200 on a verified callback — duplicates and late events are
        // acked, never retried.
        return ResponseEntity.ok().build()
    }
}

data class ProviderCallbackRequest(
    val transactionId: UUID,
    val authorized: Boolean,
    val providerReference: String? = null
)
