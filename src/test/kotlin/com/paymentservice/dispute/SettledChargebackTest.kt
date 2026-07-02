package com.paymentservice.dispute

import com.fasterxml.jackson.databind.ObjectMapper
import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.auth.ApiKeyAuthFilter
import com.paymentservice.auth.ApiKeyHasher
import com.paymentservice.ledger.AccountType
import com.paymentservice.ledger.EntryType
import com.paymentservice.ledger.LedgerService
import com.paymentservice.merchant.Merchant
import com.paymentservice.merchant.MerchantRepository
import com.paymentservice.payment.PaymentService
import com.paymentservice.payment.Transaction
import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.payment.outbox.OutboxDispatcher
import com.paymentservice.payout.InvalidPayoutAmountException
import com.paymentservice.payout.PayoutService
import com.paymentservice.payout.ReserveHoldRepository
import com.paymentservice.payout.ReserveService
import com.paymentservice.settlement.MovementKind
import com.paymentservice.settlement.SettlementExtractor
import com.paymentservice.settlement.SettlementProcessor
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A chargeback lost AFTER settlement must claw the money back from the pot
 * that holds it: MERCHANT_PAYABLE, not the (already cleared) pending MERCHANT
 * account. Payable MAY go negative - the merchant owes the platform - and the
 * rolling reserve exists precisely to cover that hole. Fresh merchant per test
 * (capture 10_000 -> settle: payable 8_820, reserve 980) so the shared
 * accumulated test DB cannot skew balance assertions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class SettledChargebackTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var settlementProcessor: SettlementProcessor
    @Autowired lateinit var disputeService: DisputeService
    @Autowired lateinit var payoutService: PayoutService
    @Autowired lateinit var reserveService: ReserveService
    @Autowired lateinit var reserveHoldRepository: ReserveHoldRepository
    @Autowired lateinit var ledgerService: LedgerService
    @Autowired lateinit var merchantRepository: MerchantRepository
    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var objectMapper: ObjectMapper

    private fun newMerchant(): Pair<Merchant, String> {
        val rawKey = "key-${UUID.randomUUID()}"
        val merchant = merchantRepository.save(
            Merchant(name = "settled-cb-test", apiKeyHash = ApiKeyHasher.hash(rawKey))
        )
        return merchant to rawKey
    }

    private fun capture(merchantId: UUID, amount: Long): Transaction {
        val request = CreatePaymentRequest(merchantId, amount, "EUR", "settled cb funding")
        val txn = paymentService.createPayment(request, "cb-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending()
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")
        return paymentService.capturePayment(txn.id)
    }

    /** Capture 10_000 EUR and settle: payable 8_820, reserve 980, pending 0. */
    private fun fundSettled(merchantId: UUID): Transaction {
        val txn = capture(merchantId, 10_000L)
        settlementProcessor.settle(txn.id)
        return txn
    }

    private fun loseDispute(txnId: UUID, amount: Long? = null) {
        val dispute = disputeService.openDispute(txnId, DisputeReason.FRAUDULENT, amount = amount)
        disputeService.resolve(dispute.id, won = false)
    }

    @Test
    fun `a lost dispute on a settled payment debits payable, not the pending account`() {
        val (merchant, _) = newMerchant()
        val txn = fundSettled(merchant.id)

        loseDispute(txn.id) // full 10_000 clawback + 1_500 fee

        val entries = ledgerService.getEntriesForTransaction(txn.id)
        val payableDebits = entries.filter {
            it.accountType == AccountType.MERCHANT_PAYABLE && it.entryType == EntryType.DEBIT
        }
        assertEquals(listOf(1_500L, 10_000L), payableDebits.map { it.amount }.sorted())

        // the pending MERCHANT account is untouched by the settled clawback
        assertEquals(0L, ledgerService.getMerchantBalance(merchant.id, "EUR"))
        // 8_820 - 10_000 - 1_500: the merchant owes the platform
        assertEquals(-2_680L, ledgerService.getPayableBalance(merchant.id, "EUR"))
    }

    @Test
    fun `a payout is rejected while payable is negative`() {
        val (merchant, _) = newMerchant()
        val txn = fundSettled(merchant.id)
        loseDispute(txn.id) // payable -2_680

        assertFailsWith<InvalidPayoutAmountException> {
            payoutService.createPayout(merchant.id, "EUR")
        }
        assertEquals(-2_680L, ledgerService.getPayableBalance(merchant.id, "EUR"))
    }

    @Test
    fun `a released reserve covers the chargeback hole and re-enables payouts`() {
        val (merchant, _) = newMerchant()
        val txn = fundSettled(merchant.id)
        // partial clawback: 8_820 - 8_000 - 1_500 = -680, within the 980 reserve
        loseDispute(txn.id, amount = 8_000L)
        assertEquals(-680L, ledgerService.getPayableBalance(merchant.id, "EUR"))

        val hold = reserveHoldRepository.findByTransactionId(txn.id)
        assertNotNull(hold)
        reserveService.release(hold.id) // +980 -> payable 300

        assertEquals(300L, ledgerService.getPayableBalance(merchant.id, "EUR"))
        val payout = payoutService.createPayout(merchant.id, "EUR")
        assertEquals(300L, payout.amount)
        assertEquals(0L, ledgerService.getPayableBalance(merchant.id, "EUR"))
    }

    @Test
    fun `the balance endpoint splits pending, available and reserve through the lifecycle`() {
        val (merchant, rawKey) = newMerchant()
        val txn = capture(merchant.id, 10_000L)

        val captured = balanceEntry(merchant.id, rawKey)
        assertEquals(9_800L, captured.get("pending").asLong())
        assertEquals(0L, captured.get("available").asLong())
        assertEquals(0L, captured.get("reserve").asLong())

        settlementProcessor.settle(txn.id)

        val settled = balanceEntry(merchant.id, rawKey)
        assertEquals(0L, settled.get("pending").asLong())
        assertEquals(8_820L, settled.get("available").asLong())
        assertEquals(980L, settled.get("reserve").asLong())

        loseDispute(txn.id)

        val clawedBack = balanceEntry(merchant.id, rawKey)
        assertEquals(0L, clawedBack.get("pending").asLong())
        assertEquals(-2_680L, clawedBack.get("available").asLong())
        assertEquals(980L, clawedBack.get("reserve").asLong())
    }

    private fun balanceEntry(merchantId: UUID, apiKey: String): com.fasterxml.jackson.databind.JsonNode {
        val headers = HttpHeaders().apply { set(ApiKeyAuthFilter.API_KEY_HEADER, apiKey) }
        val response = restTemplate.exchange(
            "/api/v1/merchants/$merchantId/balance",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            String::class.java
        )
        val balances = objectMapper.readTree(response.body).get("balances")
        assertEquals(1, balances.size(), "expected exactly one currency, got: ${response.body}")
        val entry = balances.first()
        assertEquals("EUR", entry.get("currency").asText())
        return entry
    }

    @Test
    fun `the settlement extract still emits the chargeback line after a settled clawback`() {
        val (merchant, _) = newMerchant()
        val txn = fundSettled(merchant.id)
        loseDispute(txn.id)

        val lines = SettlementExtractor.extract("ref", ledgerService.getEntriesForTransaction(txn.id))
        val chargeback = lines.single { it.kind == MovementKind.CHARGEBACK }
        assertEquals(-10_000L, chargeback.grossMinor)
        assertEquals(1_500L, chargeback.feeMinor)
        assertTrue(lines.any { it.kind == MovementKind.CAPTURE })
    }
}
