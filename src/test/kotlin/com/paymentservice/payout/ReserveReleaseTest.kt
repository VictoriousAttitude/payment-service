package com.paymentservice.payout

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.ledger.AccountType
import com.paymentservice.ledger.EntryType
import com.paymentservice.ledger.LedgerRepository
import com.paymentservice.payment.PaymentService
import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.payment.outbox.OutboxDispatcher
import com.paymentservice.settlement.SettlementProcessor
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The reserve release half of the rolling reserve: a matured hold moves
 * RESERVE -> PAYABLE as a treasury posting (no payment transaction, posting
 * group = hold id) and flips to RELEASED exactly once. Test profile:
 * hold-days 0, so a fresh hold is immediately matured; the batch scheduler is
 * parked and driven directly.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class ReserveReleaseTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var settlementProcessor: SettlementProcessor
    @Autowired lateinit var reserveReleaseBatch: ReserveReleaseBatch
    @Autowired lateinit var reserveService: ReserveService
    @Autowired lateinit var reserveHoldRepository: ReserveHoldRepository
    @Autowired lateinit var ledgerRepository: LedgerRepository

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

    private fun settledHold(): ReserveHold {
        val request = CreatePaymentRequest(merchantId, 10_000L, "EUR", "release test")
        val txn = paymentService.createPayment(request, "rel-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending()
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")
        paymentService.capturePayment(txn.id)
        settlementProcessor.settle(txn.id)
        return assertNotNull(reserveHoldRepository.findByTransactionId(txn.id))
    }

    @Test
    fun `release posts a balanced treasury pair keyed on the hold id`() {
        val hold = settledHold()

        reserveReleaseBatch.releaseMatured()

        val released = reserveHoldRepository.findById(hold.id).orElseThrow()
        assertEquals(ReserveHoldStatus.RELEASED, released.status)

        val entries = ledgerRepository.findByPostingGroupId(hold.id)
        assertEquals(2, entries.size)
        val debit = entries.single { it.entryType == EntryType.DEBIT }
        val credit = entries.single { it.entryType == EntryType.CREDIT }
        assertEquals(AccountType.MERCHANT_RESERVE, debit.accountType)
        assertEquals(AccountType.MERCHANT_PAYABLE, credit.accountType)
        assertEquals(hold.amount, debit.amount)
        assertEquals(hold.amount, credit.amount)
        assertNull(debit.transactionId) // treasury posting: no payment transaction
        assertNull(credit.transactionId)
    }

    @Test
    fun `a second release is a no-op`() {
        val hold = settledHold()

        assertEquals("EUR", reserveService.release(hold.id))
        assertNull(reserveService.release(hold.id))

        assertEquals(2, ledgerRepository.findByPostingGroupId(hold.id).size)
    }
}
