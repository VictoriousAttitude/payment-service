package com.paymentservice.settlement

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.dispute.DisputeReason
import com.paymentservice.dispute.DisputeService
import com.paymentservice.ledger.AccountType
import com.paymentservice.ledger.EntryType
import com.paymentservice.ledger.LedgerService
import com.paymentservice.payment.PaymentService
import com.paymentservice.payment.PaymentStatus
import com.paymentservice.payment.Transaction
import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.payment.outbox.OutboxDispatcher
import com.paymentservice.payout.ReserveHoldRepository
import com.paymentservice.payout.ReserveHoldStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The settlement split: at CAPTURED -> SETTLED the merchant's captured net
 * leaves the pending MERCHANT account into MERCHANT_PAYABLE, less the rolling
 * reserve slice withheld in MERCHANT_RESERVE (test profile: 1000 bps, 0-day
 * hold). Known limitation, deliberate: PARTIALLY_REFUNDED has no SETTLED edge
 * in the state machine, so "partial refund then settle" is unreachable and not
 * covered here.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class SettlementSplitTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var settlementProcessor: SettlementProcessor
    @Autowired lateinit var ledgerService: LedgerService
    @Autowired lateinit var disputeService: DisputeService
    @Autowired lateinit var reserveHoldRepository: ReserveHoldRepository

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

    private fun authorize(amount: Long): Transaction {
        val request = CreatePaymentRequest(merchantId, amount, "EUR", "split test")
        val txn = paymentService.createPayment(request, "split-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending() // CREATED -> PENDING
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")
        return txn
    }

    private fun capture(amount: Long): Transaction {
        val txn = authorize(amount)
        return paymentService.capturePayment(txn.id)
    }

    @Test
    fun `settle splits the merchant net into reserve and payable and records a hold`() {
        val txn = capture(10_000L) // fee 200 -> merchant net 9_800

        assertEquals("EUR", settlementProcessor.settle(txn.id))

        val entries = ledgerService.getEntriesForTransaction(txn.id)
        val merchantDebit = entries.single {
            it.accountType == AccountType.MERCHANT && it.entryType == EntryType.DEBIT
        }
        val reserveCredit = entries.single { it.accountType == AccountType.MERCHANT_RESERVE }
        val payableCredit = entries.single { it.accountType == AccountType.MERCHANT_PAYABLE }

        assertEquals(9_800L, merchantDebit.amount)
        assertEquals(980L, reserveCredit.amount)   // floorDiv(9_800 * 1000, 10_000)
        assertEquals(8_820L, payableCredit.amount) // net - reserve
        assertEquals(EntryType.CREDIT, reserveCredit.entryType)
        assertEquals(EntryType.CREDIT, payableCredit.entryType)

        // the pending (MERCHANT) position of this transaction is fully cleared
        assertEquals(0L, ledgerService.merchantNetForTransaction(txn.id))

        val hold = reserveHoldRepository.findByTransactionId(txn.id)
        assertNotNull(hold)
        assertEquals(980L, hold.amount)
        assertEquals(ReserveHoldStatus.HELD, hold.status)
    }

    @Test
    fun `a reserve floored to zero posts no reserve leg and no hold`() {
        // A tiny net cannot be produced by a small capture (a sub-50 amount
        // floors the platform fee to 0 and a zero fee leg violates
        // CHECK (amount > 0)), so shrink the net with a partial lost
        // chargeback, which leaves the payment CAPTURED and settlable:
        // 9_800 (capture net) - 8_291 (clawback) - 1_500 (fee) = 9.
        val txn = capture(10_000L)
        val dispute = disputeService.openDispute(txn.id, DisputeReason.FRAUDULENT, amount = 8_291L)
        disputeService.resolve(dispute.id, won = false)

        settlementProcessor.settle(txn.id) // net 9 -> reserve floorDiv(9 * 1000, 10_000) = 0

        val entries = ledgerService.getEntriesForTransaction(txn.id)
        assertTrue(entries.none { it.accountType == AccountType.MERCHANT_RESERVE })
        assertEquals(9L, entries.single { it.accountType == AccountType.MERCHANT_PAYABLE }.amount)
        assertNull(reserveHoldRepository.findByTransactionId(txn.id))
    }

    @Test
    fun `multi-capture settles the combined merchant net`() {
        val txn = authorize(10_000L)
        paymentService.capturePayment(txn.id, 6_000L) // fee 120 -> net 5_880
        paymentService.capturePayment(txn.id, 4_000L) // fee 80  -> net 3_920

        settlementProcessor.settle(txn.id)

        val entries = ledgerService.getEntriesForTransaction(txn.id)
        val merchantDebit = entries.single {
            it.accountType == AccountType.MERCHANT && it.entryType == EntryType.DEBIT
        }
        assertEquals(9_800L, merchantDebit.amount) // 5_880 + 3_920
        assertEquals(980L, entries.single { it.accountType == AccountType.MERCHANT_RESERVE }.amount)
        assertEquals(8_820L, entries.single { it.accountType == AccountType.MERCHANT_PAYABLE }.amount)
    }

    @Test
    fun `a pre-settlement lost chargeback leaves nothing to split - settle transitions only`() {
        val txn = capture(10_000L)
        // full clawback (10_000) + 1_500 fee pushes the merchant net to -1_700
        val dispute = disputeService.openDispute(txn.id, DisputeReason.FRAUDULENT)
        disputeService.resolve(dispute.id, won = false)

        assertEquals("EUR", settlementProcessor.settle(txn.id))

        assertEquals(PaymentStatus.SETTLED, paymentService.getPayment(txn.id).status)
        val entries = ledgerService.getEntriesForTransaction(txn.id)
        // 3 capture legs + 4 chargeback legs, no split legs
        assertEquals(7, entries.size)
        assertTrue(entries.none { it.accountType == AccountType.MERCHANT_PAYABLE })
        assertNull(reserveHoldRepository.findByTransactionId(txn.id))
    }

    @Test
    fun `second settle is a no-op and does not double-post the split`() {
        val txn = capture(10_000L)

        assertEquals("EUR", settlementProcessor.settle(txn.id))
        val afterFirst = ledgerService.getEntriesForTransaction(txn.id).size

        assertNull(settlementProcessor.settle(txn.id))
        assertEquals(afterFirst, ledgerService.getEntriesForTransaction(txn.id).size)
    }
}
