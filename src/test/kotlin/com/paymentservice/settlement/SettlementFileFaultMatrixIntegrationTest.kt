package com.paymentservice.settlement

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.dispute.DisputeReason
import com.paymentservice.dispute.DisputeService
import com.paymentservice.payment.PaymentService
import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.payment.outbox.OutboxDispatcher
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fault matrix against the live ledger: one payment driven through capture,
 * partial refund and lost chargeback (all three movement kinds under one
 * provider-ref prefix), then per fault class an acquirer file mutated by
 * [FaultyFileFixture] is ingested and must yield exactly the expected
 * [FileDiscrepancyType] at the targeted reference.
 *
 * Every assertion filters by this test's prefix: the shared suite DB
 * pollutes each verdict with unrelated ledger-side duplicates (reused "ref"
 * provider references in other suites). MISSING_IN_SETTLEMENT is asserted
 * only at the pure reconciler level because ledger created_at cannot be
 * backdated through the API; the integration drop case asserts the movement
 * lands in pending instead.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class SettlementFileFaultMatrixIntegrationTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var disputeService: DisputeService
    @Autowired lateinit var extractService: SettlementExtractService
    @Autowired lateinit var ingestionService: SettlementFileIngestionService
    @Autowired lateinit var discrepancyRepository: SettlementFileDiscrepancyRepository

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

    /**
     * Capture 10_000 EUR (fee 200), refund 3_000 (fee -60), lose the dispute
     * (chargeback of the net captured plus the flat fee): three movements
     * under one fresh prefix, extracted from the live ledger.
     */
    private fun settledMovements(): Pair<String, List<MovementLine>> {
        val prefix = "sfmx-${UUID.randomUUID()}"
        val request = CreatePaymentRequest(merchantId, 10_000L, "EUR", "fault matrix")
        val txn = paymentService.createPayment(request, "e-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending() // CREATED -> PENDING
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = prefix)
        paymentService.capturePayment(txn.id)
        paymentService.refundPayment(txn.id, 3_000L)
        val dispute = disputeService.openDispute(txn.id, DisputeReason.FRAUDULENT)
        disputeService.resolve(dispute.id, won = false)
        val mine = extractService.extract().filter { it.reference.startsWith(prefix) }
        assertEquals(3, mine.size)
        return prefix to mine
    }

    private fun ingest(filename: String, content: String): SettlementFile =
        ingestionService.ingest(filename, content).file

    private fun discrepanciesAt(fileId: UUID, prefix: String): List<SettlementFileDiscrepancy> =
        discrepancyRepository.findByFileIdOrderByReference(fileId)
            .filter { it.reference.startsWith(prefix) }

    @Test
    fun `clean file matches all three movement kinds with no discrepancy at its references`() {
        val (prefix, lines) = settledMovements()

        val file = ingest("mx-clean.csv", FaultyFileFixture.clean(lines))

        assertEquals(SettlementFileStatus.PROCESSED, file.status)
        assertEquals(3, file.matchedCount)
        assertTrue(discrepanciesAt(file.id, prefix).isEmpty())
    }

    @Test
    fun `dropped line lands in pending, not in discrepancies`() {
        val (prefix, lines) = settledMovements()

        val baseline = ingest("mx-drop-base.csv", FaultyFileFixture.clean(lines))
        val dropped = ingest("mx-drop.csv", FaultyFileFixture.withDroppedLine(lines, prefix))

        assertEquals(baseline.matchedCount - 1, dropped.matchedCount)
        assertEquals(baseline.pendingCount + 1, dropped.pendingCount)
        assertTrue(discrepanciesAt(dropped.id, prefix).isEmpty())
    }

    @Test
    fun `phantom line is missing in ledger`() {
        val (prefix, lines) = settledMovements()
        val phantomRef = "$prefix-phantom"

        val file = ingest("mx-phantom.csv", FaultyFileFixture.withPhantomLine(lines, phantomRef))

        val discrepancy = discrepanciesAt(file.id, prefix).single()
        assertEquals(FileDiscrepancyType.MISSING_IN_LEDGER, discrepancy.type)
        assertEquals(phantomRef, discrepancy.reference)
    }

    @Test
    fun `duplicated line is flagged and excluded from matching`() {
        val (prefix, lines) = settledMovements()

        val file = ingest("mx-dup.csv", FaultyFileFixture.withDuplicatedLine(lines, prefix))

        val discrepancy = discrepanciesAt(file.id, prefix).single()
        assertEquals(FileDiscrepancyType.DUPLICATE_REFERENCE, discrepancy.type)
        assertEquals(prefix, discrepancy.reference)
    }

    @Test
    fun `wrong reporting category is a kind mismatch carrying both kinds`() {
        val (prefix, lines) = settledMovements()

        val file = ingest("mx-kind.csv", FaultyFileFixture.withWrongKind(lines, prefix))

        val discrepancy = discrepanciesAt(file.id, prefix).single()
        assertEquals(FileDiscrepancyType.KIND_MISMATCH, discrepancy.type)
        assertEquals(prefix, discrepancy.reference)
        assertEquals(MovementKind.CAPTURE.name, discrepancy.ledgerValue)
        assertEquals(MovementKind.REFUND.name, discrepancy.settlementValue)
    }

    @Test
    fun `wrong currency is flagged alone, short-circuiting the amount checks`() {
        val (prefix, lines) = settledMovements()

        val file = ingest("mx-currency.csv", FaultyFileFixture.withWrongCurrency(lines, prefix))

        // single() proves no gross/fee discrepancy piggybacked on the swap
        val discrepancy = discrepanciesAt(file.id, prefix).single()
        assertEquals(FileDiscrepancyType.CURRENCY_MISMATCH, discrepancy.type)
        assertEquals("EUR", discrepancy.ledgerValue)
        assertEquals("USD", discrepancy.settlementValue)
    }

    @Test
    fun `one minor unit off the refund gross is a gross mismatch`() {
        val (prefix, lines) = settledMovements()
        val ref = "$prefix:refund"
        val ledgerGross = lines.first { it.reference == ref }.grossMinor

        val file = ingest("mx-gross.csv", FaultyFileFixture.withWrongGross(lines, ref))

        val discrepancy = discrepanciesAt(file.id, prefix).single()
        assertEquals(FileDiscrepancyType.GROSS_MISMATCH, discrepancy.type)
        assertEquals(ref, discrepancy.reference)
        assertEquals(ledgerGross.toString(), discrepancy.ledgerValue)
        assertEquals((ledgerGross + 1).toString(), discrepancy.settlementValue)
    }

    @Test
    fun `one minor unit off the chargeback fee is a fee mismatch`() {
        val (prefix, lines) = settledMovements()
        val ref = "$prefix:chargeback"
        val ledgerFee = lines.first { it.reference == ref }.feeMinor

        val file = ingest("mx-fee.csv", FaultyFileFixture.withWrongFee(lines, ref))

        val discrepancy = discrepanciesAt(file.id, prefix).single()
        assertEquals(FileDiscrepancyType.FEE_MISMATCH, discrepancy.type)
        assertEquals(ref, discrepancy.reference)
        assertEquals(ledgerFee.toString(), discrepancy.ledgerValue)
        assertEquals((ledgerFee + 1).toString(), discrepancy.settlementValue)
    }
}
