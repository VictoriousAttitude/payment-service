package com.paymentservice.payout

import com.fasterxml.jackson.databind.ObjectMapper
import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.auth.ApiKeyAuthFilter
import com.paymentservice.auth.ApiKeyHasher
import com.paymentservice.ledger.LedgerRepository
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
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Payout lifecycle against a live ledger. Every test funds a FRESH merchant
 * (capture 10_000 -> settle: payable 8_820, reserve 980) so the shared
 * accumulated test DB cannot skew balance assertions.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class PayoutLifecycleTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var settlementProcessor: SettlementProcessor
    @Autowired lateinit var payoutService: PayoutService
    @Autowired lateinit var payoutConfirmBatch: PayoutConfirmBatch
    @Autowired lateinit var ledgerService: LedgerService
    @Autowired lateinit var ledgerRepository: LedgerRepository
    @Autowired lateinit var merchantRepository: MerchantRepository
    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var objectMapper: ObjectMapper

    private fun newMerchant(): Pair<Merchant, String> {
        val rawKey = "key-${UUID.randomUUID()}"
        val merchant = merchantRepository.save(
            Merchant(name = "payout-test", apiKeyHash = ApiKeyHasher.hash(rawKey))
        )
        return merchant to rawKey
    }

    /** Capture 10_000 EUR and settle: payable 8_820, reserve 980. */
    private fun fund(merchantId: UUID) {
        val request = CreatePaymentRequest(merchantId, 10_000L, "EUR", "payout funding")
        val txn = paymentService.createPayment(request, "pay-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending()
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")
        paymentService.capturePayment(txn.id)
        settlementProcessor.settle(txn.id)
    }

    @Test
    fun `full payout drains the payable balance into clearing`() {
        val (merchant, _) = newMerchant()
        fund(merchant.id)

        val payout = payoutService.createPayout(merchant.id, "EUR")

        assertEquals(8_820L, payout.amount)
        assertEquals(PayoutStatus.PENDING, payout.status)
        assertEquals(0L, ledgerService.getPayableBalance(merchant.id, "EUR"))
        assertEquals(2, ledgerRepository.findByPostingGroupId(payout.id).size)
    }

    @Test
    fun `partial payout leaves the remainder available`() {
        val (merchant, _) = newMerchant()
        fund(merchant.id)

        val payout = payoutService.createPayout(merchant.id, "EUR", 5_000L)

        assertEquals(5_000L, payout.amount)
        assertEquals(3_820L, ledgerService.getPayableBalance(merchant.id, "EUR"))
    }

    @Test
    fun `zero, negative and over-available amounts are rejected`() {
        val (merchant, _) = newMerchant()
        fund(merchant.id)

        assertFailsWith<InvalidPayoutAmountException> {
            payoutService.createPayout(merchant.id, "EUR", 0L)
        }
        assertFailsWith<InvalidPayoutAmountException> {
            payoutService.createPayout(merchant.id, "EUR", -1L)
        }
        assertFailsWith<InvalidPayoutAmountException> {
            payoutService.createPayout(merchant.id, "EUR", 8_821L)
        }
        // an unfunded currency has nothing to disburse
        assertFailsWith<InvalidPayoutAmountException> {
            payoutService.createPayout(merchant.id, "USD")
        }
        assertEquals(8_820L, ledgerService.getPayableBalance(merchant.id, "EUR"))
    }

    @Test
    fun `confirm batch marks a matured pending payout paid without ledger movement`() {
        val (merchant, _) = newMerchant()
        fund(merchant.id)
        val payout = payoutService.createPayout(merchant.id, "EUR")

        payoutConfirmBatch.confirmSettled() // confirm-delay-minutes 0 in test profile

        assertEquals(PayoutStatus.PAID, payoutService.getPayout(payout.id).status)
        assertEquals(2, ledgerRepository.findByPostingGroupId(payout.id).size) // no new entries
        assertEquals(0L, ledgerService.getPayableBalance(merchant.id, "EUR"))
    }

    @Test
    fun `failing a paid payout is rejected`() {
        val (merchant, _) = newMerchant()
        fund(merchant.id)
        val payout = payoutService.createPayout(merchant.id, "EUR")
        payoutService.confirm(payout.id)

        assertFailsWith<InvalidPayoutTransitionException> {
            payoutService.fail(payout.id)
        }
    }

    @Test
    fun `failing a pending payout reverses the funds back to payable`() {
        val (merchant, _) = newMerchant()
        fund(merchant.id)
        val payout = payoutService.createPayout(merchant.id, "EUR")
        assertEquals(0L, ledgerService.getPayableBalance(merchant.id, "EUR"))

        payoutService.fail(payout.id)

        assertEquals(PayoutStatus.FAILED, payoutService.getPayout(payout.id).status)
        assertEquals(8_820L, ledgerService.getPayableBalance(merchant.id, "EUR"))
        // original pair + compensating pair, one posting group
        assertEquals(4, ledgerRepository.findByPostingGroupId(payout.id).size)
    }

    // ─── HTTP surface ────────────────────────────────────────────────

    private fun headers(apiKey: String) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        set(ApiKeyAuthFilter.API_KEY_HEADER, apiKey)
    }

    @Test
    fun `payout endpoint creates and lists payouts for the authenticated merchant`() {
        val (merchant, rawKey) = newMerchant()
        fund(merchant.id)

        val created = restTemplate.postForEntity(
            "/api/v1/merchants/${merchant.id}/payouts",
            HttpEntity("""{"currency":"EUR","amount":5000}""", headers(rawKey)),
            String::class.java
        )
        assertEquals(HttpStatus.CREATED, created.statusCode)
        assertEquals("PENDING", objectMapper.readTree(created.body).get("status").asText())

        val list = restTemplate.exchange(
            "/api/v1/merchants/${merchant.id}/payouts",
            HttpMethod.GET,
            HttpEntity<Void>(headers(rawKey)),
            String::class.java
        )
        assertEquals(HttpStatus.OK, list.statusCode)
        assertEquals(1, objectMapper.readTree(list.body).size())
    }

    @Test
    fun `a merchant cannot create a payout for another merchant`() {
        val (merchant, _) = newMerchant()
        fund(merchant.id)
        val (_, otherKey) = newMerchant()

        val response = restTemplate.postForEntity(
            "/api/v1/merchants/${merchant.id}/payouts",
            HttpEntity("""{"currency":"EUR"}""", headers(otherKey)),
            String::class.java
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `an over-available payout via the api is unprocessable`() {
        val (merchant, rawKey) = newMerchant()
        fund(merchant.id)

        val response = restTemplate.postForEntity(
            "/api/v1/merchants/${merchant.id}/payouts",
            HttpEntity("""{"currency":"EUR","amount":999999}""", headers(rawKey)),
            String::class.java
        )
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
    }
}
