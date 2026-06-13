package com.paymentservice.payment.outbox

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.payment.PaymentService
import com.paymentservice.payment.PaymentStatus
import com.paymentservice.payment.dto.CreatePaymentRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Exercises the transactional outbox: create writes a PENDING event alongside a
 * CREATED payment in one transaction; the dispatcher drains it to PENDING and
 * marks the event DISPATCHED; re-dispatch is a no-op (idempotent).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class OutboxIntegrationTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var outboxRepository: OutboxEventRepository
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var outboxProcessor: OutboxProcessor

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11") // seeded in V1

    private fun createRequest() = CreatePaymentRequest(
        merchantId = merchantId,
        amount = 10_000L,
        currency = "EUR",
        description = "outbox test"
    )

    @Test
    fun `create writes a pending outbox event and leaves the payment CREATED`() {
        val txn = paymentService.createPayment(createRequest(), "outbox-${UUID.randomUUID()}").transaction

        assertEquals(PaymentStatus.CREATED, paymentService.getPayment(txn.id).status)

        val events = outboxRepository.findByAggregateId(txn.id)
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(OutboxStatus.PENDING, event.status)
        assertEquals(OutboxEvent.PROVIDER_AUTHORIZATION, event.type)
        assertNull(event.dispatchedAt)
    }

    @Test
    fun `dispatch moves the payment to PENDING and marks the event dispatched`() {
        val txn = paymentService.createPayment(createRequest(), "outbox-${UUID.randomUUID()}").transaction

        outboxDispatcher.dispatchPending()

        assertEquals(PaymentStatus.PENDING, paymentService.getPayment(txn.id).status)
        val event = outboxRepository.findByAggregateId(txn.id).single()
        assertEquals(OutboxStatus.DISPATCHED, event.status)
        assertNotNull(event.dispatchedAt)
    }

    @Test
    fun `re-dispatch is idempotent - dispatched event is not reprocessed`() {
        val txn = paymentService.createPayment(createRequest(), "outbox-${UUID.randomUUID()}").transaction

        outboxDispatcher.dispatchPending()
        val firstDispatchedAt = outboxRepository.findByAggregateId(txn.id).single().dispatchedAt

        outboxDispatcher.dispatchPending()
        val event = outboxRepository.findByAggregateId(txn.id).single()

        assertEquals(OutboxStatus.DISPATCHED, event.status)
        assertEquals(firstDispatchedAt, event.dispatchedAt)
        assertEquals(PaymentStatus.PENDING, paymentService.getPayment(txn.id).status)
    }

    @Test
    fun `concurrent dispatch of the same event - SKIP LOCKED yields exactly one winner`() {
        val txn = paymentService.createPayment(createRequest(), "outbox-${UUID.randomUUID()}").transaction
        val eventId = outboxRepository.findByAggregateId(txn.id).single().id

        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..2).map {
                pool.submit<UUID?> {
                    barrier.await(5, TimeUnit.SECONDS)
                    outboxProcessor.dispatch(eventId)
                }
            }
            val results = tasks.map { it.get(10, TimeUnit.SECONDS) }

            // exactly one transaction claimed the row; the other was skipped (null)
            assertEquals(1, results.count { it != null })
            assertEquals(1, results.count { it == null })
        } finally {
            pool.shutdownNow()
        }

        val event = outboxRepository.findByAggregateId(txn.id).single()
        assertEquals(OutboxStatus.DISPATCHED, event.status)
        assertEquals(PaymentStatus.PENDING, paymentService.getPayment(txn.id).status)
    }

    @Test
    fun `recordFailure backs off then dead-letters at max attempts`() {
        val txn = paymentService.createPayment(createRequest(), "outbox-${UUID.randomUUID()}").transaction
        val eventId = outboxRepository.findByAggregateId(txn.id).single().id

        // first failure: still PENDING, pushed into the future, not yet dispatchable
        outboxProcessor.recordFailure(eventId, "provider timeout")
        val afterFirst = outboxRepository.findById(eventId).get()
        assertEquals(OutboxStatus.PENDING, afterFirst.status)
        assertEquals(1, afterFirst.attempts)
        assertTrue(afterFirst.nextAttemptAt.isAfter(java.time.Instant.now()))
        assertFalse(outboxRepository.findDispatchable(100).any { it.id == eventId })

        // drive to the dead-letter threshold
        repeat(OutboxProcessor.MAX_ATTEMPTS - 1) {
            outboxProcessor.recordFailure(eventId, "provider timeout")
        }
        val dead = outboxRepository.findById(eventId).get()
        assertEquals(OutboxStatus.FAILED, dead.status)
        assertEquals(OutboxProcessor.MAX_ATTEMPTS, dead.attempts)
        assertFalse(outboxRepository.findDispatchable(100).any { it.id == eventId })
    }
}
