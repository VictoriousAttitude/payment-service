package com.paymentservice.payment

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.outbox.OutboxDispatcher
import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.settlement.SettlementProcessor
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Verifies the append-only transition history: every status change is recorded
 * in order, and the records are immutable at the DB level.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class TransactionHistoryTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var settlementProcessor: SettlementProcessor
    @Autowired lateinit var transactionEventRepository: TransactionEventRepository

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

    @Test
    fun `full lifecycle records every transition in order`() {
        val request = CreatePaymentRequest(merchantId, 10_000L, "EUR", "history test")
        val txn = paymentService.createPayment(request, "h-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending() // CREATED -> PENDING
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref") // -> AUTHORIZED
        paymentService.capturePayment(txn.id) // -> CAPTURED
        settlementProcessor.settle(txn.id) // -> SETTLED

        val history = paymentService.getHistory(txn.id)
        val transitions = history.map { it.fromStatus to it.toStatus }

        assertEquals(
            listOf(
                null to PaymentStatus.CREATED,
                PaymentStatus.CREATED to PaymentStatus.PENDING,
                PaymentStatus.PENDING to PaymentStatus.AUTHORIZED,
                PaymentStatus.AUTHORIZED to PaymentStatus.CAPTURED,
                PaymentStatus.CAPTURED to PaymentStatus.SETTLED
            ),
            transitions
        )
        assertNull(history.first().fromStatus)
    }

    @Test
    fun `transition events are immutable - db trigger rejects delete`() {
        val request = CreatePaymentRequest(merchantId, 10_000L, "EUR", "history immutability")
        val txn = paymentService.createPayment(request, "h-${UUID.randomUUID()}").transaction
        val event = paymentService.getHistory(txn.id).first()

        assertFailsWith<Exception> {
            transactionEventRepository.delete(event)
            transactionEventRepository.flush()
        }
    }
}
