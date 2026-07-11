package com.paymentservice.ledger

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.payment.PaymentService
import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.payment.outbox.OutboxDispatcher
import com.paymentservice.reconciliation.ReconciliationService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rolling balance snapshots against the live schema. The Testcontainers
 * database is shared and accumulated across suites, so assertions are relative
 * (accelerated == naive full SUM, own before/after deltas), never absolute
 * balances. Test lag-seconds is 0, so freshly committed entries are foldable
 * immediately and the batch is parked - the test drives advance() directly.
 *
 * The load-bearing invariant is that the accelerated read (snapshot + live
 * delta) equals the naive SUM over all history at EVERY cursor position, so
 * routing balance reads through the snapshot can never change a result.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class BalanceSnapshotTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var snapshotProcessor: SnapshotProcessor
    @Autowired lateinit var ledgerService: LedgerService
    @Autowired lateinit var ledgerRepository: LedgerRepository
    @Autowired lateinit var snapshotRepository: BalanceSnapshotRepository
    @Autowired lateinit var reconciliationService: ReconciliationService
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

    /** Captures a EUR payment, posting the three capture ledger entries. */
    private fun captureEur(amount: Long): UUID {
        val request = CreatePaymentRequest(merchantId, amount, "EUR", "snapshot test")
        val txn = paymentService.createPayment(request, "s-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending()
        paymentService.handleProviderCallback(
            txn.id, authorized = true, providerReference = "snap-${UUID.randomUUID()}"
        )
        paymentService.capturePayment(txn.id)
        return txn.id
    }

    private fun naiveMerchant(): Long =
        ledgerRepository.computeBalance(AccountType.MERCHANT, merchantId, "EUR")

    private fun merchantSnapshotNet(): Long =
        snapshotRepository
            .findByAccountTypeAndAccountIdAndCurrency(AccountType.MERCHANT, merchantId, "EUR")
            ?.net ?: 0L

    /** The load-bearing invariant: the accelerated read equals the naive SUM. */
    private fun assertAcceleratedMatchesNaive(message: String) =
        assertEquals(naiveMerchant(), ledgerService.getMerchantBalance(merchantId, "EUR"), message)

    @Test
    fun `accelerated balance equals the naive full sum before and after folds`() {
        captureEur(10_000)
        assertAcceleratedMatchesNaive("pre-fold: degenerates to full SUM")

        snapshotProcessor.advance()
        assertAcceleratedMatchesNaive("post-fold: snapshot + empty delta")

        captureEur(10_000)
        assertAcceleratedMatchesNaive("post-capture: snapshot + live delta")

        snapshotProcessor.advance()
        assertAcceleratedMatchesNaive("second fold: delta absorbed into snapshot")
    }

    /**
     * Same invariant for the per-currency read the balance endpoint serves:
     * the union of snapshot rows and post-cursor tail must equal the naive
     * full-history GROUP BY - same currency set, same totals - at every
     * cursor position.
     */
    @Test
    fun `per-currency accelerated balances equal the naive grouped sums across folds`() {
        fun assertMatchesNaive(message: String) {
            for (accountType in listOf(AccountType.MERCHANT, AccountType.INCOMING, AccountType.PLATFORM)) {
                val naive = ledgerRepository.computeBalancesByCurrency(accountType, merchantId)
                    .associateBy { it.currency }
                val accelerated = ledgerService.getBalancesByCurrency(accountType, merchantId)
                    .associateBy { it.currency }
                assertEquals(naive, accelerated, "$message ($accountType)")
            }
        }

        captureEur(10_000)
        assertMatchesNaive("pre-fold: no snapshot rows, pure tail")

        snapshotProcessor.advance()
        assertMatchesNaive("post-fold: pure snapshot rows, empty tail")

        captureEur(10_000)
        assertMatchesNaive("post-capture: snapshot rows merged with a live tail")
    }

    @Test
    fun `folding moves a merchant credit from the live delta into the snapshot row`() {
        snapshotProcessor.advance()
        val before = merchantSnapshotNet()

        // 10_000 EUR capture: fee floor(10_000 * 200 / 10_000) = 200, merchant net credit 9_800.
        captureEur(10_000)
        assertEquals(before, merchantSnapshotNet(), "capture lands in the live delta, snapshot untouched")

        snapshotProcessor.advance()
        assertEquals(before + 9_800, merchantSnapshotNet(), "the fold absorbs 9_800 into the snapshot")
    }

    @Test
    fun `reconciliation finds no drift after a fold and flags an injected divergence`() {
        captureEur(10_000)
        snapshotProcessor.advance()
        assertTrue(
            reconciliationService.findSnapshotDrift().isEmpty(),
            "a freshly folded snapshot agrees with the ledger"
        )

        // A snapshot row for an account with zero ledger entries: pure drift the
        // ledger re-derivation must catch. Cleaned up so it cannot leak into
        // another suite's reconciliation.
        val ghost = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO ledger_balance_snapshots " +
                "(id, account_type, account_id, currency, total_debits, total_credits) " +
                "VALUES (?, 'MERCHANT', ?, 'EUR', 0, 5000)",
            UUID.randomUUID(),
            ghost
        )
        try {
            assertTrue(
                reconciliationService.findSnapshotDrift().any { it.contains(ghost.toString()) },
                "a snapshot row with no matching ledger entries must be flagged as drift"
            )
        } finally {
            jdbcTemplate.update("DELETE FROM ledger_balance_snapshots WHERE account_id = ?", ghost)
        }
    }
}
