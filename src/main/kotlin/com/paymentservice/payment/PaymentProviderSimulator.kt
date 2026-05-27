package com.paymentservice.payment

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Simulates an external payment provider (e.g., Stripe, Adyen).
 * In production, this would be an HTTP call to the provider's API.
 * The provider responds asynchronously via webhook callback.
 */
@Component
class PaymentProviderSimulator(
    private val paymentService: PaymentService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Simulates async provider authorization.
     * In reality: we'd send an HTTP request to the provider,
     * and they'd call our webhook endpoint hours/seconds later.
     * Here we simulate with a brief delay + direct service call.
     */
    @Async
    fun simulateAuthorization(transactionId: UUID) {
        try {
            Thread.sleep(500) // simulate network latency

            // 90% success rate to demonstrate both paths
            val authorized = Math.random() > 0.1
            val providerRef = if (authorized) "prov_${UUID.randomUUID().toString().take(8)}" else null

            log.info("Provider callback for txn=$transactionId authorized=$authorized ref=$providerRef")

            paymentService.handleProviderCallback(transactionId, authorized, providerRef)
        } catch (e: Exception) {
            log.error("Provider simulation failed for txn=$transactionId", e)
        }
    }
}
