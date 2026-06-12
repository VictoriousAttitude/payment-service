package com.paymentservice.payment

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.ledger.AccountType
import com.paymentservice.ledger.EntryType
import com.paymentservice.ledger.LedgerRepository
import com.paymentservice.ledger.LedgerService
import com.paymentservice.ledger.LedgerEntry
import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.reconciliation.ReconciliationService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests against real PostgreSQL (Testcontainers).
 * Tests all 3 safety layers: Gate (idempotency), Core (state + ledger), Guard (reconciliation).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PaymentIntegrationTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var ledgerService: LedgerService
    @Autowired lateinit var ledgerRepository: LedgerRepository
    @Autowired lateinit var transactionRepository: TransactionRepository
    @Autowired lateinit var reconciliationService: ReconciliationService

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11") // seeded in V1

    private fun createRequest(amount: Long = 10_000L) = CreatePaymentRequest(
        merchantId = merchantId,
        amount = amount,
        currency = "EUR",
        description = "test payment"
    )

    // ─── Layer 2 (Core): full payment lifecycle ───────────────────────

    @Test
    fun `full lifecycle - create, authorize, capture, verify ledger and balance`() {
        val key = "lifecycle-${UUID.randomUUID()}"
        val txn = paymentService.createPayment(createRequest(), key).transaction

        assertEquals(PaymentStatus.PENDING, txn.status)

        // provider authorizes
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "prov_test")
        val authorized = paymentService.getPayment(txn.id)
        assertEquals(PaymentStatus.AUTHORIZED, authorized.status)
        assertEquals("prov_test", authorized.providerReference)

        // capture: atomic status + ledger
        val captured = paymentService.capturePayment(txn.id)
        assertEquals(PaymentStatus.CAPTURED, captured.status)

        // verify ledger: 3 entries, balanced (10000 debit = 9800 merchant credit + 200 platform credit)
        val entries = ledgerService.getEntriesForTransaction(txn.id)
        assertEquals(3, entries.size)

        val debit = entries.single { it.entryType == EntryType.DEBIT }
        assertEquals(10_000L, debit.amount)
        assertEquals(AccountType.INCOMING, debit.accountType)

        val merchantCredit = entries.single { it.accountType == AccountType.MERCHANT }
        assertEquals(EntryType.CREDIT, merchantCredit.entryType)
        assertEquals(9_800L, merchantCredit.amount) // 10000 - 2% fee

        val platformCredit = entries.single { it.accountType == AccountType.PLATFORM }
        assertEquals(EntryType.CREDIT, platformCredit.entryType)
        assertEquals(200L, platformCredit.amount) // 2% of 10000

        // verify merchant balance = 9800
        val balance = ledgerService.getMerchantBalance(merchantId)
        assertTrue(balance > 0)
    }

    @Test
    fun `full lifecycle with refund - balance returns to zero`() {
        val key = "refund-${UUID.randomUUID()}"
        val txn = paymentService.createPayment(createRequest(), key).transaction

        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "prov_ref")
        paymentService.capturePayment(txn.id)

        val balanceBefore = ledgerService.getMerchantBalance(merchantId)

        // refund reverses the ledger
        val refunded = paymentService.refundPayment(txn.id)
        assertEquals(PaymentStatus.REFUNDED, refunded.status)

        // 6 entries total: 3 capture + 3 refund
        val entries = ledgerService.getEntriesForTransaction(txn.id)
        assertEquals(6, entries.size)

        // merchant balance decreased by the original net amount
        val balanceAfter = ledgerService.getMerchantBalance(merchantId)
        assertEquals(balanceBefore - 9_800L, balanceAfter)
    }

    // ─── Layer 1 (Gate): idempotency ────────────────────────────────

    @Test
    fun `idempotency - same key returns same transaction, no duplicate`() {
        val key = "idem-${UUID.randomUUID()}"

        val first = paymentService.createPayment(createRequest(), key)
        val second = paymentService.createPayment(createRequest(), key)

        assertEquals(first.transaction.id, second.transaction.id)
        assertEquals(first.transaction.amount, second.transaction.amount)

        // created flag drives side effects: provider dispatch + 201 only once
        assertTrue(first.created)
        assertFalse(second.created)

        // only one record in DB for this key
        val found = transactionRepository.findByMerchantIdAndIdempotencyKey(merchantId, key)
        assertNotNull(found)
        assertEquals(first.transaction.id, found.id)
    }

    @Test
    fun `idempotency - same key with different payload is rejected`() {
        val key = "idem-reuse-${UUID.randomUUID()}"

        paymentService.createPayment(createRequest(amount = 10_000L), key)

        org.junit.jupiter.api.assertThrows<IdempotencyKeyReuseException> {
            paymentService.createPayment(createRequest(amount = 99_999L), key)
        }
    }

    @Test
    fun `idempotency - concurrent create with same key yields one transaction`() {
        val key = "idem-race-${UUID.randomUUID()}"

        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        val results = (1..2).map {
            executor.submit<PaymentResult> {
                barrier.await()
                paymentService.createPayment(createRequest(), key)
            }
        }.map { it.get() }
        executor.shutdown()

        // both callers get the same transaction; exactly one created it
        assertEquals(results[0].transaction.id, results[1].transaction.id)
        assertEquals(1, results.count { it.created }, "exactly one request must win the insert")

        val found = transactionRepository.findByMerchantIdAndIdempotencyKey(merchantId, key)
        assertNotNull(found)
    }

    // ─── State machine enforcement ──────────────────────────────────

    @Test
    fun `capture on FAILED payment throws InvalidStateTransitionException`() {
        val key = "fail-capture-${UUID.randomUUID()}"
        val txn = paymentService.createPayment(createRequest(), key).transaction

        // provider declines
        paymentService.handleProviderCallback(txn.id, authorized = false, providerReference = null)
        val failed = paymentService.getPayment(txn.id)
        assertEquals(PaymentStatus.FAILED, failed.status)

        // capture should fail
        val ex = org.junit.jupiter.api.assertThrows<InvalidStateTransitionException> {
            paymentService.capturePayment(txn.id)
        }
        assertTrue(ex.message!!.contains("FAILED"))
    }

    @Test
    fun `capture on PENDING payment throws - must be AUTHORIZED first`() {
        val key = "pending-capture-${UUID.randomUUID()}"
        val txn = paymentService.createPayment(createRequest(), key).transaction

        assertEquals(PaymentStatus.PENDING, txn.status)

        org.junit.jupiter.api.assertThrows<InvalidStateTransitionException> {
            paymentService.capturePayment(txn.id)
        }
    }

    @Test
    fun `double capture throws - already CAPTURED`() {
        val key = "double-capture-${UUID.randomUUID()}"
        val txn = paymentService.createPayment(createRequest(), key).transaction
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")
        paymentService.capturePayment(txn.id)

        // second capture must fail
        org.junit.jupiter.api.assertThrows<InvalidStateTransitionException> {
            paymentService.capturePayment(txn.id)
        }
    }

    // ─── Concurrency: optimistic locking ────────────────────────────

    @Test
    fun `concurrent capture - exactly one succeeds, ledger entries not duplicated`() {
        val key = "concurrent-capture-${UUID.randomUUID()}"
        val txn = paymentService.createPayment(createRequest(), key).transaction
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")

        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        val outcomes = (1..2).map {
            executor.submit<Throwable?> {
                barrier.await()
                try {
                    paymentService.capturePayment(txn.id)
                    null
                } catch (e: Throwable) {
                    e
                }
            }
        }.map { it.get() }
        executor.shutdown()

        // exactly one capture must fail: either optimistic lock conflict
        // (true race) or state guard (loser read after winner committed)
        val failures = outcomes.filterNotNull()
        assertEquals(1, failures.size, "exactly one capture must fail, got: $outcomes")
        val failure = failures.single()
        assertTrue(
            failure is OptimisticLockingFailureException || failure is InvalidStateTransitionException,
            "unexpected failure type: $failure"
        )

        // the critical invariant: money was not duplicated
        assertEquals(PaymentStatus.CAPTURED, paymentService.getPayment(txn.id).status)
        assertEquals(3, ledgerService.getEntriesForTransaction(txn.id).size)
    }

    // ─── Layer 3 (Guard): reconciliation ────────────────────────────

    @Test
    fun `reconciliation detects duplicated balanced entry set via amount mismatch`() {
        val key = "dup-entries-${UUID.randomUUID()}"
        val txn = paymentService.createPayment(createRequest(), key).transaction
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")
        paymentService.capturePayment(txn.id)

        // simulate the double-capture bug: a second balanced capture entry set
        val duplicates = ledgerService.getEntriesForTransaction(txn.id).map {
            LedgerEntry(
                transactionId = it.transactionId,
                accountType = it.accountType,
                accountId = it.accountId,
                entryType = it.entryType,
                amount = it.amount,
                currency = it.currency,
                description = "duplicate"
            )
        }
        ledgerRepository.saveAll(duplicates)

        // debit==credit checks are blind: the duplicate set balances
        assertFalse(txn.id in reconciliationService.findUnbalancedTransactions())
        assertTrue(reconciliationService.verifyGlobalLedgerBalance().balanced)

        // amount check catches it: debits sum to 2x transaction amount
        assertTrue(txn.id in reconciliationService.findTransactionsWithMismatchedAmounts())
    }


    @Test
    fun `reconciliation reports healthy after valid lifecycle`() {
        val key = "recon-healthy-${UUID.randomUUID()}"
        val txn = paymentService.createPayment(createRequest(), key).transaction
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")
        paymentService.capturePayment(txn.id)

        val report = reconciliationService.runFullReconciliation()

        assertTrue(report.unbalancedTransactions.isEmpty(), "no unbalanced transactions")
        assertTrue(report.transactionsWithoutLedgerEntries.isEmpty(), "no missing ledger entries")
        assertTrue(report.globalBalance.balanced, "global ledger balanced")
    }

    @Test
    fun `global ledger balance holds after multiple captures and refunds`() {
        // capture 3 payments
        repeat(3) { i ->
            val key = "multi-$i-${UUID.randomUUID()}"
            val txn = paymentService.createPayment(createRequest(amount = 5_000L), key).transaction
            paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref-$i")
            paymentService.capturePayment(txn.id)
        }

        // refund 1 payment
        val refundKey = "multi-refund-${UUID.randomUUID()}"
        val refundTxn = paymentService.createPayment(createRequest(amount = 5_000L), refundKey).transaction
        paymentService.handleProviderCallback(refundTxn.id, authorized = true, providerReference = "ref-r")
        paymentService.capturePayment(refundTxn.id)
        paymentService.refundPayment(refundTxn.id)

        val balance = reconciliationService.verifyGlobalLedgerBalance()
        assertTrue(balance.balanced, "debits=${balance.totalDebits} credits=${balance.totalCredits}")
    }

    // ─── Edge cases ─────────────────────────────────────────────────

    @Test
    fun `payment with non-existent merchant throws`() {
        val fakeId = UUID.randomUUID()
        val request = CreatePaymentRequest(
            merchantId = fakeId,
            amount = 1000,
            currency = "EUR"
        )
        org.junit.jupiter.api.assertThrows<MerchantNotFoundException> {
            paymentService.createPayment(request, "fake-merchant-${UUID.randomUUID()}")
        }
    }

    @Test
    fun `provider decline sets failure reason`() {
        val key = "decline-${UUID.randomUUID()}"
        val txn = paymentService.createPayment(createRequest(), key).transaction

        paymentService.handleProviderCallback(txn.id, authorized = false, providerReference = null)

        val declined = paymentService.getPayment(txn.id)
        assertEquals(PaymentStatus.FAILED, declined.status)
        assertNotNull(declined.failureReason)
        assertFalse(declined.failureReason!!.isBlank())
    }
}
