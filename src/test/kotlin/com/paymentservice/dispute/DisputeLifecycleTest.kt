package com.paymentservice.dispute

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.ledger.AccountType
import com.paymentservice.ledger.EntryType
import com.paymentservice.ledger.LedgerService
import com.paymentservice.payment.PaymentService
import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.payment.outbox.OutboxDispatcher
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Chargeback lifecycle and its money movement. A lost dispute claws the funds
 * back from the merchant plus a flat fee, posted as a balanced ledger set; a won
 * dispute moves nothing. Disputes only attach to a payment with net captured
 * money.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class DisputeLifecycleTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var ledgerService: LedgerService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var disputeService: DisputeService

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

    private fun authorized(amount: Long = 10_000L): UUID {
        val request = CreatePaymentRequest(
            merchantId = merchantId,
            amount = amount,
            currency = "EUR",
            description = "dispute test"
        )
        val txn = paymentService.createPayment(request, "k-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending()
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")
        return txn.id
    }

    private fun captured(amount: Long = 10_000L): UUID {
        val id = authorized(amount)
        paymentService.capturePayment(id)
        return id
    }

    @Test
    fun `lost chargeback claws back funds and charges a fee, balanced`() {
        val id = captured()

        val dispute = disputeService.openDispute(id, DisputeReason.FRAUDULENT)
        val resolved = disputeService.resolve(dispute.id, won = false)

        assertEquals(DisputeStatus.LOST, resolved.status)

        val entries = ledgerService.getEntriesForTransaction(id)
        val cbCredit = entries.single {
            it.accountType == AccountType.CHARGEBACK && it.entryType == EntryType.CREDIT
        }
        assertEquals(10_000L, cbCredit.amount, "full disputed amount returned to cardholder")

        val feeCredit = entries.single {
            it.accountType == AccountType.PLATFORM && it.entryType == EntryType.CREDIT &&
                it.amount == LedgerService.CHARGEBACK_FEE
        }
        assertEquals(LedgerService.CHARGEBACK_FEE, feeCredit.amount)

        val debits = entries.filter { it.entryType == EntryType.DEBIT }.sumOf { it.amount }
        val credits = entries.filter { it.entryType == EntryType.CREDIT }.sumOf { it.amount }
        assertEquals(debits, credits, "ledger stays balanced after a lost chargeback")

        // chargeback must not be miscounted as a refund
        assertEquals(0L, ledgerService.refundedTotal(id))
    }

    @Test
    fun `won chargeback moves no money`() {
        val id = captured()
        val before = ledgerService.getEntriesForTransaction(id).size

        val dispute = disputeService.openDispute(id, DisputeReason.PRODUCT_NOT_RECEIVED)
        val resolved = disputeService.resolve(dispute.id, won = true)

        assertEquals(DisputeStatus.WON, resolved.status)
        assertEquals(before, ledgerService.getEntriesForTransaction(id).size, "won dispute posts nothing")
    }

    @Test
    fun `cannot dispute an uncaptured authorization`() {
        val id = authorized() // no capture -> nothing to claw back

        assertThrows<DisputeNotAllowedException> {
            disputeService.openDispute(id, DisputeReason.FRAUDULENT)
        }
    }

    @Test
    fun `cannot open a second live dispute`() {
        val id = captured()
        disputeService.openDispute(id, DisputeReason.FRAUDULENT)

        assertThrows<DisputeAlreadyOpenException> {
            disputeService.openDispute(id, DisputeReason.DUPLICATE)
        }
    }

    @Test
    fun `dispute amount cannot exceed net captured`() {
        val id = captured(10_000L)

        assertThrows<DisputeNotAllowedException> {
            disputeService.openDispute(id, DisputeReason.FRAUDULENT, amount = 20_000L)
        }
    }

    @Test
    fun `resolved dispute is terminal and cannot be resolved again`() {
        val id = captured()
        val dispute = disputeService.openDispute(id, DisputeReason.GENERAL)
        disputeService.resolve(dispute.id, won = true)

        assertThrows<InvalidDisputeTransitionException> {
            disputeService.resolve(dispute.id, won = false)
        }
    }

    @Test
    fun `evidence moves the dispute under review before resolution`() {
        val id = captured()
        val dispute = disputeService.openDispute(id, DisputeReason.UNRECOGNIZED)

        val reviewing = disputeService.submitEvidence(dispute.id)
        assertEquals(DisputeStatus.UNDER_REVIEW, reviewing.status)

        val resolved = disputeService.resolve(dispute.id, won = false)
        assertEquals(DisputeStatus.LOST, resolved.status)
        assertTrue(ledgerService.getEntriesForTransaction(id).any { it.accountType == AccountType.CHARGEBACK })
    }
}
