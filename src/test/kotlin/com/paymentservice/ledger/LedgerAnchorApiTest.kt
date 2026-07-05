package com.paymentservice.ledger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.payment.PaymentService
import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.payment.outbox.OutboxDispatcher
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HTTP surface of the anchor chain plus the end-to-end tamper drill: the
 * Testcontainers user is a superuser, so the test can do what the V7/V22
 * triggers exist to expose - disable them, rewrite history, and prove the
 * verifier flags the exact epoch. Every mutation is restored in a finally
 * block (trigger re-enabled, original value written back): the database is
 * shared across suites and a leaked tamper would corrupt every later verdict.
 * Assertions are relative (own epoch, own entries), never absolute counts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class LedgerAnchorApiTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var anchorProcessor: AnchorProcessor
    @Autowired lateinit var ledgerRepository: LedgerRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

    /** Captures a fresh payment and seals everything pending into a new epoch. */
    private fun sealFreshEpoch(): Pair<LedgerAnchor, Set<UUID>> {
        val request = CreatePaymentRequest(merchantId, 10_000L, "EUR", "anchor api test")
        val txn = paymentService.createPayment(request, "aapi-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending()
        val reference = "aapi-${UUID.randomUUID()}"
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = reference)
        paymentService.capturePayment(txn.id)
        val entryIds = ledgerRepository.findByTransactionId(txn.id).map { it.id }.toSet()
        val anchor = assertNotNull(anchorProcessor.anchorPending(), "fresh entries must seal a new epoch")
        return anchor to entryIds
    }

    private fun verifyReport(): JsonNode {
        val response = restTemplate.getForEntity("/api/v1/ledger-anchors/verify", String::class.java)
        assertEquals(HttpStatus.OK, response.statusCode)
        return objectMapper.readTree(response.body)
    }

    private fun failuresAt(report: JsonNode, epoch: Long): List<String> =
        report.get("failures")
            .filter { it.get("epoch").asLong() == epoch }
            .map { it.get("reason").asText() }

    @Test
    fun `anchors list includes the sealed epoch and leaves csv exports its entries`() {
        val (anchor, entryIds) = sealFreshEpoch()

        val list = restTemplate.getForEntity("/api/v1/ledger-anchors", String::class.java)
        assertEquals(HttpStatus.OK, list.statusCode)
        val mine = objectMapper.readTree(list.body).single { it.get("epoch").asLong() == anchor.epoch }
        assertEquals(anchor.root, mine.get("root").asText())
        assertEquals(anchor.chainHash, mine.get("chainHash").asText())
        assertEquals(anchor.leafCount, mine.get("leafCount").asInt())

        val csv = restTemplate.getForEntity("/api/v1/ledger-anchors/${anchor.epoch}/leaves", String::class.java)
        assertEquals(HttpStatus.OK, csv.statusCode)
        val lines = csv.body!!.trimEnd('\n').lines()
        assertEquals(AnchorLeafCsv.HEADER, lines.first())
        assertEquals(anchor.leafCount, lines.size - 1)
        val exportedEntryIds = lines.drop(1).map { it.split(",")[1] }.toSet()
        assertTrue(exportedEntryIds.containsAll(entryIds.map { it.toString() }))
    }

    @Test
    fun `unknown epoch is a 404`() {
        val response = restTemplate.getForEntity("/api/v1/ledger-anchors/999999999/leaves", String::class.java)
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("ANCHOR_NOT_FOUND", objectMapper.readTree(response.body).get("error").asText())
    }

    @Test
    fun `verify reports a healthy chain when nothing is tampered`() {
        val (anchor, _) = sealFreshEpoch()
        val report = verifyReport()
        assertTrue(report.get("healthy").asBoolean())
        assertTrue(report.get("verifiedEpochs").asInt() >= anchor.epoch.toInt())
        assertTrue(report.get("failures").isEmpty)
    }

    @Test
    fun `rewriting an anchored entry amount is flagged as a root mismatch at its epoch`() {
        val (anchor, entryIds) = sealFreshEpoch()
        val entryId = entryIds.first()
        val originalAmount = jdbcTemplate.queryForObject(
            "SELECT amount FROM ledger_entries WHERE id = ?", Long::class.java, entryId
        )

        jdbcTemplate.execute("ALTER TABLE ledger_entries DISABLE TRIGGER trg_ledger_entries_immutable")
        try {
            jdbcTemplate.update("UPDATE ledger_entries SET amount = amount + 1 WHERE id = ?", entryId)
            val report = verifyReport()
            assertFalse(report.get("healthy").asBoolean())
            assertTrue("ROOT_MISMATCH" in failuresAt(report, anchor.epoch))
        } finally {
            jdbcTemplate.update("UPDATE ledger_entries SET amount = ? WHERE id = ?", originalAmount, entryId)
            jdbcTemplate.execute("ALTER TABLE ledger_entries ENABLE TRIGGER trg_ledger_entries_immutable")
        }

        assertTrue(verifyReport().get("healthy").asBoolean(), "restored ledger must verify clean")
    }

    @Test
    fun `rewriting an anchor chain hash is flagged as a chain mismatch at its epoch`() {
        val (anchor, _) = sealFreshEpoch()

        jdbcTemplate.execute("ALTER TABLE ledger_anchors DISABLE TRIGGER trg_ledger_anchors_immutable")
        try {
            jdbcTemplate.update(
                "UPDATE ledger_anchors SET chain_hash = ? WHERE epoch = ?",
                AnchorChain.GENESIS, anchor.epoch
            )
            val report = verifyReport()
            assertFalse(report.get("healthy").asBoolean())
            assertTrue("CHAIN_MISMATCH" in failuresAt(report, anchor.epoch))
        } finally {
            jdbcTemplate.update(
                "UPDATE ledger_anchors SET chain_hash = ? WHERE epoch = ?",
                anchor.chainHash, anchor.epoch
            )
            jdbcTemplate.execute("ALTER TABLE ledger_anchors ENABLE TRIGGER trg_ledger_anchors_immutable")
        }

        assertTrue(verifyReport().get("healthy").asBoolean(), "restored chain must verify clean")
    }
}
