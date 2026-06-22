package com.paymentservice.payment

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.expiry.AuthorizationExpiryBatch
import com.paymentservice.expiry.AuthorizationExpiryProcessor
import com.paymentservice.ledger.LedgerService
import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.payment.outbox.OutboxDispatcher
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
 * Authorization void (manual cancel) and expiry (time-bounded hold lapses).
 * Both release an uncaptured authorization and post nothing to the ledger.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class AuthVoidExpiryTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var ledgerService: LedgerService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var expiryBatch: AuthorizationExpiryBatch
    @Autowired lateinit var expiryProcessor: AuthorizationExpiryProcessor

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

    private fun authorized(amount: Long = 10_000L): UUID {
        val request = CreatePaymentRequest(
            merchantId = merchantId,
            amount = amount,
            currency = "EUR",
            description = "void/expiry test"
        )
        val txn = paymentService.createPayment(request, "k-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending()
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")
        return txn.id
    }

    @Test
    fun `void cancels an authorization and posts no ledger entries`() {
        val id = authorized()

        val voided = paymentService.voidPayment(id)

        assertEquals(PaymentStatus.VOIDED, voided.status)
        assertTrue(ledgerService.getEntriesForTransaction(id).isEmpty(), "void must not move money")
    }

    @Test
    fun `void on a captured payment is rejected - refund is its path`() {
        val id = authorized()
        paymentService.capturePayment(id)

        assertThrows<InvalidStateTransitionException> {
            paymentService.voidPayment(id)
        }
    }

    @Test
    fun `expiry sweep transitions a stale authorization to EXPIRED`() {
        val id = authorized()

        // test profile: validity-minutes 0 -> a fresh authorization is eligible
        expiryBatch.expireStaleAuthorizations()

        assertEquals(PaymentStatus.EXPIRED, paymentService.getPayment(id).status)
    }

    @Test
    fun `expiry is idempotent and skips non-authorized rows`() {
        val id = authorized()
        paymentService.capturePayment(id) // now CAPTURED, not AUTHORIZED

        // captured row is not expirable; processor no-ops and returns null
        assertEquals(null, expiryProcessor.expire(id))
        assertEquals(PaymentStatus.CAPTURED, paymentService.getPayment(id).status)
    }

    @Test
    fun `expired authorization is terminal - cannot be captured`() {
        val id = authorized()
        expiryProcessor.expire(id)

        assertThrows<InvalidStateTransitionException> {
            paymentService.capturePayment(id)
        }
    }
}
