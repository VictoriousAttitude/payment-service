package com.paymentservice.payment

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

/**
 * Resilient HTTP edge to the provider's callback endpoint. Wraps the signed
 * POST in a retry + circuit breaker and bounds it with connect/read timeouts.
 *
 * This is a SEPARATE bean from the caller on purpose: Resilience4j applies its
 * @Retry/@CircuitBreaker via an AOP proxy, and a self-invocation (this.method)
 * bypasses the proxy. The annotations only fire when the method is reached
 * through the injected bean reference, so the resilient call must cross a bean
 * boundary.
 */
@Component
class ProviderCallbackClient(
    @Value("\${payment.provider.timeout-ms:2000}") timeoutMs: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val restClient: RestClient = RestClient.builder()
        .requestFactory(
            JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(timeoutMs))
                    .build()
            ).apply { setReadTimeout(Duration.ofMillis(timeoutMs)) }
        )
        .build()

    @Retry(name = PROVIDER)
    @CircuitBreaker(name = PROVIDER)
    fun postSignedCallback(callbackUrl: String, signedBody: String, signature: String) {
        restClient.post()
            .uri(callbackUrl)
            .header("X-Webhook-Signature", signature)
            .contentType(MediaType.APPLICATION_JSON)
            .body(signedBody)
            .retrieve()
            .toBodilessEntity()
    }

    companion object {
        const val PROVIDER = "paymentProvider"
    }
}
