package com.paymentservice.payment

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.util.UUID

/**
 * Simulates an external payment provider (e.g. Stripe, Adyen).
 * In production this logic lives on the provider's side and they call our
 * webhook. Here it stands in for the provider: it decides the outcome, signs
 * the payload with the shared HMAC secret, and POSTs through the real webhook
 * endpoint — exercising the full signed callback path rather than shortcutting
 * to the service.
 */
@Component
class PaymentProviderSimulator(
    private val webhookSigner: WebhookSigner,
    private val objectMapper: ObjectMapper,
    @Value("\${payment.webhook.callback-url}") private val callbackUrl: String
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val restClient = RestClient.create()

    @Async
    fun simulateAuthorization(transactionId: UUID) {
        try {
            Thread.sleep(500) // simulate network latency

            // 90% success rate to demonstrate both paths
            val authorized = Math.random() > 0.1
            val providerRef = if (authorized) "prov_${UUID.randomUUID().toString().take(8)}" else null

            val body = objectMapper.writeValueAsString(
                ProviderCallbackRequest(transactionId, authorized, providerRef)
            )
            val signature = webhookSigner.sign(body)

            log.info("Posting provider callback txn=$transactionId authorized=$authorized")

            restClient.post()
                .uri(callbackUrl)
                .header("X-Webhook-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity()
        } catch (e: Exception) {
            log.error("Provider simulation failed for txn=$transactionId", e)
        }
    }
}
