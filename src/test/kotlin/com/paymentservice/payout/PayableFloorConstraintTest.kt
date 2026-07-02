package com.paymentservice.payout

import com.paymentservice.TestcontainersConfiguration
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
 * The deferred payable-floor CONSTRAINT TRIGGER (V20), exercised with raw SQL
 * so the application-level guard (merchant row lock + amount check in
 * PayoutService) is out of the picture: this proves the invariant holds even
 * against a writer that bypasses the service. Deferred to COMMIT, so the tests
 * drive an explicit transaction via TransactionTemplate.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class PayableFloorConstraintTest {

    @Autowired lateinit var jdbcTemplate: JdbcTemplate
    @Autowired lateinit var txManager: PlatformTransactionManager

    private val txTemplate by lazy { TransactionTemplate(txManager) }

    private fun insertEntry(
        postingGroupId: UUID,
        accountType: String,
        accountId: UUID,
        entryType: String,
        amount: Long
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO ledger_entries
                (id, transaction_id, posting_group_id, account_type, account_id, entry_type, amount, currency)
            VALUES (?, NULL, ?, ?, ?, ?, ?, 'EUR')
            """.trimIndent(),
            UUID.randomUUID(), postingGroupId, accountType, accountId, entryType, amount
        )
    }

    /** Funds payable via a settlement-split-shaped group (no clearing leg). */
    private fun fundPayable(accountId: UUID, amount: Long) {
        txTemplate.executeWithoutResult {
            val group = UUID.randomUUID()
            insertEntry(group, "MERCHANT", accountId, "DEBIT", amount)
            insertEntry(group, "MERCHANT_PAYABLE", accountId, "CREDIT", amount)
        }
    }

    private fun payoutShaped(accountId: UUID, amount: Long) {
        val group = UUID.randomUUID()
        insertEntry(group, "MERCHANT_PAYABLE", accountId, "DEBIT", amount)
        insertEntry(group, "PAYOUT_CLEARING", accountId, "CREDIT", amount)
    }

    private fun payableBalance(accountId: UUID): Long =
        jdbcTemplate.queryForObject(
            """
            SELECT coalesce(sum(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE -amount END), 0)
            FROM ledger_entries
            WHERE account_type = 'MERCHANT_PAYABLE' AND account_id = ?
            """.trimIndent(),
            Long::class.java, accountId
        )!!

    @Test
    fun `a balanced payout-shaped group that overdraws payable is rejected at commit`() {
        val accountId = UUID.randomUUID() // fresh account: payable balance 0

        val error = assertFailsWith<Exception> {
            txTemplate.executeWithoutResult {
                payoutShaped(accountId, 5_000L) // balanced, but payable -> -5_000
            }
        }

        assertTrue(
            generateSequence(error as Throwable?) { it.cause }
                .any { it.message?.contains("payable floor") == true },
            "expected a payable floor failure, got: $error"
        )
        assertEquals(0L, payableBalance(accountId), "the rejected posting must not persist")
    }

    @Test
    fun `a payout within the payable balance commits`() {
        val accountId = UUID.randomUUID()
        fundPayable(accountId, 10_000L)

        txTemplate.executeWithoutResult {
            payoutShaped(accountId, 6_000L)
        }

        assertEquals(4_000L, payableBalance(accountId))
    }

    @Test
    fun `a chargeback-shaped debit may push payable negative`() {
        val accountId = UUID.randomUUID()
        fundPayable(accountId, 1_000L)

        // settled-chargeback shape: PAYABLE DEBIT with no clearing leg
        txTemplate.executeWithoutResult {
            val group = UUID.randomUUID()
            insertEntry(group, "MERCHANT_PAYABLE", accountId, "DEBIT", 20_000L)
            insertEntry(group, "CHARGEBACK", accountId, "CREDIT", 20_000L)
        }

        assertEquals(-19_000L, payableBalance(accountId))
    }
}
