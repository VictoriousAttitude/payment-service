package com.paymentservice.settlement

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.payment.outbox.OutboxDispatcher
import com.paymentservice.payment.PaymentService
import com.paymentservice.payment.PaymentStatus
import com.paymentservice.payment.Transaction
import com.paymentservice.payment.dto.CreatePaymentRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Verifies the settlement batch resolves the CAPTURED -> SETTLED transition.
 * The scheduler is parked in the test profile (delay-minutes 0 makes a fresh
 * capture immediately settlable); the test drives settleEligible() directly.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class SettlementBatchTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var settlementBatch: SettlementBatch
    @Autowired lateinit var settlementProcessor: SettlementProcessor

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

    private fun capture(): Transaction {
        val request = CreatePaymentRequest(merchantId, 10_000L, "EUR", "settlement test")
        val txn = paymentService.createPayment(request, "s-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending() // CREATED -> PENDING
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")
        return paymentService.capturePayment(txn.id) // -> CAPTURED
    }

    @Test
    fun `batch settles an eligible captured transaction`() {
        val txn = capture()
        assertEquals(PaymentStatus.CAPTURED, paymentService.getPayment(txn.id).status)

        settlementBatch.settleEligible()

        assertEquals(PaymentStatus.SETTLED, paymentService.getPayment(txn.id).status)
    }

    @Test
    fun `settle is idempotent - a non-captured transaction is skipped`() {
        val txn = capture()
        assertEquals("EUR", settlementProcessor.settle(txn.id)) // first settles
        assertEquals(null, settlementProcessor.settle(txn.id)) // already SETTLED -> no-op
        assertEquals(PaymentStatus.SETTLED, paymentService.getPayment(txn.id).status)
    }
}
