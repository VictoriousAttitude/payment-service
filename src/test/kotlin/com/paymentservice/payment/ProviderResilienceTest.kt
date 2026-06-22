package com.paymentservice.payment

import com.paymentservice.TestcontainersConfiguration
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.retry.RetryRegistry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The outbound provider call is wrapped in a retry + circuit breaker bound by a
 * timeout. These assert the policy is wired (config bound to the named instance)
 * and that a call to an unreachable provider actually exercises the retry.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class ProviderResilienceTest {

    @Autowired lateinit var callbackClient: ProviderCallbackClient
    @Autowired lateinit var circuitBreakerRegistry: CircuitBreakerRegistry
    @Autowired lateinit var retryRegistry: RetryRegistry

    @Test
    fun `circuit breaker is configured for the provider instance`() {
        val cb = circuitBreakerRegistry.circuitBreaker(ProviderCallbackClient.PROVIDER)

        assertEquals(50f, cb.circuitBreakerConfig.failureRateThreshold)
        assertEquals(10, cb.circuitBreakerConfig.minimumNumberOfCalls)
    }

    @Test
    fun `retry is configured for the provider instance`() {
        val retry = retryRegistry.retry(ProviderCallbackClient.PROVIDER)

        assertEquals(3, retry.retryConfig.maxAttempts)
    }

    @Test
    fun `call to unreachable provider fails and exhausts the retry`() {
        val retry = retryRegistry.retry(ProviderCallbackClient.PROVIDER)
        val before = retry.metrics.numberOfFailedCallsWithRetryAttempt

        // test profile callback-url points at localhost:1 (unreachable)
        assertThrows<Exception> {
            callbackClient.postSignedCallback(
                "http://localhost:1/api/v1/webhooks/provider-callback",
                """{"transactionId":"${UUID.randomUUID()}","authorized":true,"providerReference":"r"}""",
                "t=0,v1=deadbeef"
            )
        }

        assertTrue(
            retry.metrics.numberOfFailedCallsWithRetryAttempt > before,
            "a failing call must have gone through the retry"
        )
    }
}
