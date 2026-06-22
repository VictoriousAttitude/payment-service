package com.paymentservice.payment

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Simulates an external payment provider (e.g. Stripe, Adyen) behind the
 * PaymentProviderPort. In production this logic lives on the provider's side
 * and they call our webhook; here it stands in for them — it decides the
 * outcome, signs the payload with the shared HMAC secret, and POSTs through the
 * real signed-callback path via the resilient ProviderCallbackClient.
 *
 * The decide-and-sign step lives here; the resilient HTTP edge (retry, circuit
 * breaker, timeout) lives in ProviderCallbackClient, so the @Resilience4j
 * aspects fire across the bean boundary instead of being bypassed by a
 * self-invocation.
 */
@Component
class PaymentProviderSimulator(
    private val webhookSigner: WebhookSigner,
    private val objectMapper: ObjectMapper,
    private val callbackClient: ProviderCallbackClient,
    @Value("\${payment.webhook.callback-url}") private val callbackUrl: String
) : PaymentProviderPort {

    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    override fun requestAuthorization(transactionId: UUID) {
        try {
            Thread.sleep(500) // simulate network latency

            // 90% success rate to demonstrate both paths
            val authorized = Math.random() > 0.1
            val providerRef = if (authorized) "prov_${UUID.randomUUID().toString().take(8)}" else null

            val body = objectMapper.writeValueAsString(
                ProviderCallbackRequest(transactionId, authorized, providerRef)
            )
            val signature = webhookSigner.signedHeader(body)

            log.info("Posting provider callback txn=$transactionId authorized=$authorized")

            callbackClient.postSignedCallback(callbackUrl, body, signature)
        } catch (e: Exception) {
            log.error("Provider simulation failed for txn=$transactionId", e)
        }
    }
}
