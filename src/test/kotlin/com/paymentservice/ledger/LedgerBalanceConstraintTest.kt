package com.paymentservice.ledger

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.payment.PaymentService
import com.paymentservice.payment.dto.CreatePaymentRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The deferred balance CONSTRAINT TRIGGER (V15): an unbalanced double-entry set
 * is impossible to COMMIT, not merely detected after the fact. Because the
 * constraint is DEFERRABLE INITIALLY DEFERRED it fires at commit, so the tests
 * drive an explicit transaction via TransactionTemplate rather than relying on
 * the rollback-only @Transactional test transaction (which never commits).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class LedgerBalanceConstraintTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var jdbcTemplate: JdbcTemplate
    @Autowired lateinit var txManager: PlatformTransactionManager

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
    private val txTemplate by lazy { TransactionTemplate(txManager) }

    private fun newTransactionId(): UUID {
        val request = CreatePaymentRequest(
            merchantId = merchantId, amount = 10_000L, currency = "EUR", description = "balance"
        )
        return paymentService.createPayment(request, "k-${UUID.randomUUID()}").transaction.id
    }

    private fun insertEntry(
        transactionId: UUID?,
        entryType: String,
        amount: Long,
        postingGroupId: UUID? = transactionId
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO ledger_entries
                (id, transaction_id, posting_group_id, account_type, account_id, entry_type, amount, currency)
            VALUES (?, ?, ?, 'MERCHANT', ?, ?, ?, 'EUR')
            """.trimIndent(),
            UUID.randomUUID(), transactionId, postingGroupId, UUID.randomUUID(), entryType, amount
        )
    }

    private fun entryCount(postingGroupId: UUID): Long =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ledger_entries WHERE posting_group_id = ?",
            Long::class.java, postingGroupId
        )!!

    @Test
    fun `an unbalanced entry set cannot be committed`() {
        val transactionId = newTransactionId()

        val error = assertFailsWith<Exception> {
            txTemplate.executeWithoutResult {
                insertEntry(transactionId, "DEBIT", 10_000L) // lone debit, no matching credit
            }
        }

        // the deferred constraint rejects the imbalance at COMMIT
        assertTrue(
            generateSequence(error as Throwable?) { it.cause }
                .any { it.message?.contains("imbalance") == true },
            "expected a ledger imbalance failure, got: $error"
        )
        assertEquals(0L, entryCount(transactionId), "the rejected insert must not persist")
    }

    @Test
    fun `a balanced entry set commits`() {
        val transactionId = newTransactionId()

        txTemplate.executeWithoutResult {
            insertEntry(transactionId, "DEBIT", 10_000L)
            insertEntry(transactionId, "CREDIT", 10_000L)
        }

        assertEquals(2L, entryCount(transactionId))
    }

    // Treasury postings (payouts, reserve releases) carry no transaction_id.
    // The V18 posting-group rewrite exists exactly so these are still covered:
    // grouping by transaction_id would make the check vacuous for NULL
    // (WHERE transaction_id = NULL matches nothing).

    @Test
    fun `a balanced posting with no transaction commits`() {
        val postingGroupId = UUID.randomUUID()

        txTemplate.executeWithoutResult {
            insertEntry(null, "DEBIT", 5_000L, postingGroupId)
            insertEntry(null, "CREDIT", 5_000L, postingGroupId)
        }

        assertEquals(2L, entryCount(postingGroupId))
    }

    @Test
    fun `an unbalanced posting with no transaction is rejected at commit`() {
        val postingGroupId = UUID.randomUUID()

        val error = assertFailsWith<Exception> {
            txTemplate.executeWithoutResult {
                insertEntry(null, "DEBIT", 5_000L, postingGroupId) // lone debit, no txn
            }
        }

        assertTrue(
            generateSequence(error as Throwable?) { it.cause }
                .any { it.message?.contains("imbalance") == true },
            "expected a ledger imbalance failure, got: $error"
        )
        assertEquals(0L, entryCount(postingGroupId), "the rejected insert must not persist")
    }
}
