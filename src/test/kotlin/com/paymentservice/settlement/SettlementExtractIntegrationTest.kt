package com.paymentservice.settlement

import com.paymentservice.TestcontainersConfiguration
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
 * Runs the extract against the live ledger: a full capture and a partial refund
 * on a real transaction must project to the exact movement lines and CSV the
 * recon oracle parses. Proves the extract reads the real database, not a
 * fixture.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class SettlementExtractIntegrationTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var extractService: SettlementExtractService

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

    @Test
    fun `capture then partial refund projects to signed movement lines`() {
        val providerRef = "ref-${UUID.randomUUID()}"
        val request = CreatePaymentRequest(merchantId, 10_000L, "EUR", "extract test")
        val txn = paymentService.createPayment(request, "e-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending() // CREATED -> PENDING
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = providerRef)
        paymentService.capturePayment(txn.id)
        paymentService.refundPayment(txn.id, 3_000L)

        val mine = extractService.extract().filter { it.reference.startsWith(providerRef) }
        assertEquals(2, mine.size)

        val capture = mine.first { it.kind == MovementKind.CAPTURE }
        assertEquals(providerRef, capture.reference)
        assertEquals(10_000, capture.grossMinor)
        assertEquals(200, capture.feeMinor) // 2% of 10000
        assertEquals("EUR", capture.currency)

        val refund = mine.first { it.kind == MovementKind.REFUND }
        assertEquals("$providerRef:refund", refund.reference)
        assertEquals(-3_000, refund.grossMinor)
        assertEquals(-60, refund.feeMinor) // 2% of 3000 returned

        val csv = extractService.toCsv(mine)
        assertTrue(csv.startsWith("reference,kind,gross_minor,fee_minor,currency,occurred_at\n"))
        assertTrue(csv.contains("$providerRef,CAPTURE,10000,200,EUR,"))
        assertTrue(csv.contains("$providerRef:refund,REFUND,-3000,-60,EUR,"))
        assertTrue(csv.trimEnd().lines().last().endsWith("Z")) // ISO seconds, Z offset
    }
}
