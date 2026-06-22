package com.paymentservice.payment

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.ledger.LedgerService
import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.payment.outbox.OutboxDispatcher
import com.paymentservice.reconciliation.ReconciliationService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Partial / multi-capture and partial refund. The running captured/refunded
 * totals are derived from the ledger, so these tests assert the lifecycle label
 * AND the money breakdown move together and stay within bounds.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class PartialCaptureRefundTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var ledgerService: LedgerService
    @Autowired lateinit var reconciliationService: ReconciliationService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

    /** Creates a payment, drives it to AUTHORIZED, returns its id. */
    private fun authorized(amount: Long): UUID {
        val request = CreatePaymentRequest(
            merchantId = merchantId,
            amount = amount,
            currency = "EUR",
            description = "partial test"
        )
        val txn = paymentService.createPayment(request, "k-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending()
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")
        return txn.id
    }

    @Test
    fun `partial capture then complete - lands PARTIALLY_CAPTURED then CAPTURED`() {
        val id = authorized(10_000L)

        val first = paymentService.capturePayment(id, amount = 4_000L)
        assertEquals(PaymentStatus.PARTIALLY_CAPTURED, first.status)
        assertEquals(4_000L, ledgerService.capturedTotal(id))

        val second = paymentService.capturePayment(id, amount = 6_000L)
        assertEquals(PaymentStatus.CAPTURED, second.status)
        assertEquals(10_000L, ledgerService.capturedTotal(id))
    }

    @Test
    fun `multi-capture across three operations completes`() {
        val id = authorized(9_000L)

        paymentService.capturePayment(id, amount = 3_000L)
        paymentService.capturePayment(id, amount = 3_000L)
        val last = paymentService.capturePayment(id, amount = 3_000L)

        assertEquals(PaymentStatus.CAPTURED, last.status)
        assertEquals(9_000L, ledgerService.capturedTotal(id))
    }

    @Test
    fun `null amount captures the full remaining headroom`() {
        val id = authorized(10_000L)
        paymentService.capturePayment(id, amount = 2_500L)

        val rest = paymentService.capturePayment(id) // remaining 7_500
        assertEquals(PaymentStatus.CAPTURED, rest.status)
        assertEquals(10_000L, ledgerService.capturedTotal(id))
    }

    @Test
    fun `over-capture beyond authorized amount is rejected`() {
        val id = authorized(5_000L)
        paymentService.capturePayment(id, amount = 4_000L)

        assertFailsWith<InvalidPaymentAmountException> {
            paymentService.capturePayment(id, amount = 2_000L) // only 1_000 left
        }
    }

    @Test
    fun `partial refund then complete - lands PARTIALLY_REFUNDED then REFUNDED`() {
        val id = authorized(10_000L)
        paymentService.capturePayment(id) // full capture

        val first = paymentService.refundPayment(id, amount = 3_000L)
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, first.status)
        assertEquals(3_000L, ledgerService.refundedTotal(id))

        val second = paymentService.refundPayment(id, amount = 7_000L)
        assertEquals(PaymentStatus.REFUNDED, second.status)
        assertEquals(10_000L, ledgerService.refundedTotal(id))
    }

    @Test
    fun `refund cannot exceed captured total`() {
        val id = authorized(10_000L)
        paymentService.capturePayment(id, amount = 6_000L) // captured 6_000 of 10_000

        assertFailsWith<InvalidPaymentAmountException> {
            paymentService.refundPayment(id, amount = 7_000L) // only 6_000 captured
        }
    }

    @Test
    fun `fully refunding a partial capture reaches REFUNDED`() {
        val id = authorized(10_000L)
        paymentService.capturePayment(id, amount = 4_000L) // PARTIALLY_CAPTURED

        val refunded = paymentService.refundPayment(id) // refund all captured (4_000)
        assertEquals(PaymentStatus.REFUNDED, refunded.status)
        assertEquals(4_000L, ledgerService.refundedTotal(id))
    }

    @Test
    fun `idempotent capture replay does not double-apply`() {
        val id = authorized(10_000L)
        val key = "cap-${UUID.randomUUID()}"

        paymentService.capturePayment(id, amount = 4_000L, idempotencyKey = key)
        // same key, even with a different amount, replays the original operation
        paymentService.capturePayment(id, amount = 9_999L, idempotencyKey = key)

        assertEquals(4_000L, ledgerService.capturedTotal(id))
        assertEquals(PaymentStatus.PARTIALLY_CAPTURED, paymentService.getPayment(id).status)
    }

    @Test
    fun `payment view exposes captured and refunded breakdown`() {
        val id = authorized(10_000L)
        paymentService.capturePayment(id)            // captured 10_000
        paymentService.refundPayment(id, amount = 2_500L)

        val view = paymentService.getPaymentView(id)
        assertEquals(10_000L, view.capturedAmount)
        assertEquals(2_500L, view.refundedAmount)
        assertEquals(PaymentStatus.PARTIALLY_REFUNDED, view.transaction.status)
    }

    @Test
    fun `partial lifecycle keeps the global ledger balanced and within invariants`() {
        val id = authorized(8_000L)
        paymentService.capturePayment(id, amount = 5_000L)
        paymentService.capturePayment(id, amount = 3_000L)
        paymentService.refundPayment(id, amount = 1_000L)

        assertTrue(reconciliationService.verifyGlobalLedgerBalance().balanced)
        assertTrue(id !in reconciliationService.findTransactionsWithMismatchedAmounts())
    }
}
