package com.paymentservice.payment

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.payment.dto.CreatePaymentRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The V16 BEFORE UPDATE trigger makes terminal statuses absorbing in the DB: a
 * terminal transaction's status can never change again, even via a raw write that
 * bypasses PaymentStatus.canTransitionTo. The trigger fires at statement execution
 * (a BEFORE trigger, not deferred), so a plain UPDATE is rejected immediately - no
 * explicit commit needed. Statuses are forced via raw JDBC precisely to bypass the
 * application state machine and exercise the DB guard in isolation.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class TransactionTerminalStatusTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

    private fun newTransactionId(): UUID {
        val request = CreatePaymentRequest(
            merchantId = merchantId, amount = 10_000L, currency = "EUR", description = "terminal"
        )
        return paymentService.createPayment(request, "k-${UUID.randomUUID()}").transaction.id
    }

    private fun forceStatus(id: UUID, status: String) {
        jdbcTemplate.update("UPDATE transactions SET status = ? WHERE id = ?", status, id)
    }

    private fun statusOf(id: UUID): String =
        jdbcTemplate.queryForObject("SELECT status FROM transactions WHERE id = ?", String::class.java, id)!!

    @Test
    fun `a terminal status cannot transition to another status`() {
        val id = newTransactionId()
        forceStatus(id, "SETTLED") // CREATED -> SETTLED: lawful, source is not terminal

        val error = assertFailsWith<Exception> { forceStatus(id, "REFUNDED") }

        assertTrue(
            generateSequence(error as Throwable?) { it.cause }
                .any { it.message?.contains("terminal") == true },
            "expected a terminal-status failure, got: $error"
        )
        assertEquals("SETTLED", statusOf(id), "the rejected transition must not persist")
    }

    @Test
    fun `a non-status write on a terminal row is allowed`() {
        val id = newTransactionId()
        forceStatus(id, "REFUNDED")

        // unrelated column write and a same-status no-op update both pass: only a
        // status CHANGE out of a terminal state is blocked
        jdbcTemplate.update("UPDATE transactions SET provider_reference = ? WHERE id = ?", "late-ref", id)
        forceStatus(id, "REFUNDED")

        assertEquals("REFUNDED", statusOf(id))
    }
}
