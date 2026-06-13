package com.paymentservice.payment

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.payment.outbox.OutboxDispatcher
import com.paymentservice.payment.dto.CreatePaymentRequest
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Verifies domain metrics increment on the real capture/callback path. The
 * shared test DB accumulates counts across tests, so assertions are deltas.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class PaymentMetricsTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var meterRegistry: MeterRegistry

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

    private fun capturedCount() =
        meterRegistry.counter("payments.captured", "currency", "EUR").count()

    private fun callbackCount(outcome: String) =
        meterRegistry.counter("payments.callbacks", "outcome", outcome).count()

    private fun authorizedPayment(): Transaction {
        val request = CreatePaymentRequest(merchantId, 10_000L, "EUR", "metrics test")
        val txn = paymentService.createPayment(request, "m-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending() // CREATED -> PENDING
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")
        return paymentService.getPayment(txn.id)
    }

    @Test
    fun `capture increments the captured counter and the applied-callback counter`() {
        val capturedBefore = capturedCount()
        val appliedBefore = callbackCount("APPLIED")

        val txn = authorizedPayment()
        paymentService.capturePayment(txn.id)

        assertEquals(capturedBefore + 1.0, capturedCount())
        assertEquals(appliedBefore + 1.0, callbackCount("APPLIED"))
    }

    @Test
    fun `duplicate callback increments the duplicate counter, not applied`() {
        val txn = authorizedPayment()
        val duplicateBefore = callbackCount("DUPLICATE")

        // provider re-delivers the same authorization: already AUTHORIZED
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")

        assertEquals(duplicateBefore + 1.0, callbackCount("DUPLICATE"))
    }
}
