package com.paymentservice.payout

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.auth.ApiKeyHasher
import com.paymentservice.ledger.LedgerService
import com.paymentservice.merchant.Merchant
import com.paymentservice.merchant.MerchantRepository
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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The double-spend race the merchant row lock exists for: two concurrent
 * full-available payouts. Without SELECT ... FOR UPDATE both would read the
 * same balance and both would pass the amount guard; with it the loser blocks
 * behind the winner's commit, re-reads an empty balance and is rejected.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class PayoutConcurrencyTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var settlementProcessor: SettlementProcessor
    @Autowired lateinit var payoutService: PayoutService
    @Autowired lateinit var ledgerService: LedgerService
    @Autowired lateinit var merchantRepository: MerchantRepository

    @Test
    fun `two concurrent full payouts - exactly one wins, payable ends at zero`() {
        val merchant = merchantRepository.save(
            Merchant(name = "race", apiKeyHash = ApiKeyHasher.hash("key-${UUID.randomUUID()}"))
        )
        val request = CreatePaymentRequest(merchant.id, 10_000L, "EUR", "race funding")
        val txn = paymentService.createPayment(request, "race-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending()
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")
        paymentService.capturePayment(txn.id)
        settlementProcessor.settle(txn.id) // payable 8_820

        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        val outcomes = (1..2).map {
            executor.submit<Throwable?> {
                barrier.await()
                try {
                    payoutService.createPayout(merchant.id, "EUR") // full available
                    null
                } catch (e: Throwable) {
                    e
                }
            }
        }.map { it.get(30, TimeUnit.SECONDS) }
        executor.shutdown()

        val failures = outcomes.filterNotNull()
        assertEquals(1, failures.size, "exactly one payout must fail, got: $outcomes")
        assertTrue(
            failures.single() is InvalidPayoutAmountException,
            "loser must fail the amount guard, got: ${failures.single()}"
        )
        assertEquals(0L, ledgerService.getPayableBalance(merchant.id, "EUR"))
        assertEquals(1, payoutService.getPayoutsForMerchant(merchant.id).size)
    }
}
