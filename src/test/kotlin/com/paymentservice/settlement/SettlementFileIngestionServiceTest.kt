package com.paymentservice.settlement

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.payment.PaymentService
import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.payment.outbox.OutboxDispatcher
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ingestion against the live ledger: a real captured payment reconciled from
 * an uploaded acquirer CSV. The Testcontainers database is shared across the
 * suite, so movements from other tests are always in the extract: recent ones
 * land in pending, but many suites reuse providerReference "ref", which
 * surfaces as ledger-side DUPLICATE_REFERENCE in every verdict (duplicate
 * detection bypasses the window by design). No test here may assert
 * global-zero discrepancies or a clean verdict; every targeted assertion
 * filters by this test's provider-ref prefix.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class SettlementFileIngestionServiceTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var ingestionService: SettlementFileIngestionService
    @Autowired lateinit var discrepancyRepository: SettlementFileDiscrepancyRepository
    @Autowired lateinit var meterRegistry: MeterRegistry

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
    private val header = "reference,reporting_category,currency,gross,fee"

    /** Creates and captures a 10_000 EUR payment; fee is 200 (2%). */
    private fun capturedPaymentRef(): String {
        val providerRef = "sfing-${UUID.randomUUID()}"
        val request = CreatePaymentRequest(merchantId, 10_000L, "EUR", "settlement file test")
        val txn = paymentService.createPayment(request, "e-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending() // CREATED -> PENDING
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = providerRef)
        paymentService.capturePayment(txn.id)
        return providerRef
    }

    @Test
    fun `clean file for a captured payment matches its line with no discrepancy at its reference`() {
        val ref = capturedPaymentRef()

        val outcome = ingestionService.ingest("clean.csv", "$header\n$ref,charge,EUR,100.00,2.00\n")

        assertTrue(outcome.created)
        val file = outcome.file
        assertEquals(SettlementFileStatus.PROCESSED, file.status)
        assertEquals(1, file.lineCount)
        assertEquals(1, file.matchedCount)
        assertNotNull(file.processedAt)
        // shared-DB pollution may add unrelated ledger-side dup discrepancies; ours must be absent
        assertTrue(
            discrepancyRepository.findByFileIdOrderByReference(file.id)
                .none { it.reference.startsWith("sfing-") }
        )
    }

    @Test
    fun `re-uploading identical content returns the existing verdict without reprocessing`() {
        val ref = capturedPaymentRef()
        val content = "$header\n$ref,charge,EUR,100.00,2.00\n"

        val first = ingestionService.ingest("first.csv", content)
        val second = ingestionService.ingest("renamed.csv", content)

        assertTrue(first.created)
        assertFalse(second.created)
        assertEquals(first.file.id, second.file.id)
        // dedup is by content sha, so the original filename is retained
        assertEquals("first.csv", second.file.filename)
        assertEquals(SettlementFileStatus.PROCESSED, second.file.status)
    }

    @Test
    fun `malformed content lands failed with a reason and no discrepancy rows`() {
        val outcome = ingestionService.ingest(
            "bad.csv",
            "$header\nsfing-bad-${UUID.randomUUID()},topup,EUR,1.00,0.00\n"
        )

        val file = outcome.file
        assertEquals(SettlementFileStatus.FAILED, file.status)
        assertTrue(file.failureReason!!.contains("line 2"))
        assertNotNull(file.processedAt)
        assertEquals(0, file.discrepancyCount)
        assertTrue(discrepancyRepository.findByFileIdOrderByReference(file.id).isEmpty())
    }

    @Test
    fun `wrong fee is persisted as a fee mismatch carrying both values`() {
        val ref = capturedPaymentRef()

        val outcome = ingestionService.ingest("fee.csv", "$header\n$ref,charge,EUR,100.00,2.50\n")

        val file = outcome.file
        assertEquals(SettlementFileStatus.PROCESSED, file.status)
        assertTrue(file.discrepancyCount >= 1)
        val mine = discrepancyRepository.findByFileIdOrderByReference(file.id)
            .filter { it.reference == ref }
        val discrepancy = mine.single()
        assertEquals(FileDiscrepancyType.FEE_MISMATCH, discrepancy.type)
        assertEquals("200", discrepancy.ledgerValue)
        assertEquals("250", discrepancy.settlementValue)
    }

    @Test
    fun `discrepant file flips the healthy gauge and increments typed counters`() {
        // The gauge restoring to 1 on a clean file is untestable here: reused
        // "ref" provider references from other suites make every verdict
        // carry ledger-side DUPLICATE_REFERENCE rows, so no file is clean.
        val ref = capturedPaymentRef()
        ingestionService.ingest("gauge-bad.csv", "$header\n$ref,charge,EUR,100.01,2.00\n")

        val gauge = meterRegistry.get("settlement.file.healthy").gauge()
        assertEquals(0.0, gauge.value())
        val grossCounter = meterRegistry.get("settlement.file.discrepancies")
            .tag("type", "GROSS_MISMATCH").counter()
        assertTrue(grossCounter.count() >= 1.0)
        val ingestedCounter = meterRegistry.get("settlement.files.ingested")
            .tag("result", "discrepancies").counter()
        assertTrue(ingestedCounter.count() >= 1.0)
    }
}
