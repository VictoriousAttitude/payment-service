package com.paymentservice.ledger

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.payment.PaymentService
import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.payment.outbox.OutboxDispatcher
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Epoch anchoring against the live schema. The Testcontainers database is
 * shared across suites, so every assertion is relative (own entry ids, own
 * epoch, predecessor deltas) - never absolute epoch numbers or global counts.
 * Test lag-seconds is 0, so freshly committed entries anchor immediately.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class LedgerAnchorIntegrationTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var anchorProcessor: AnchorProcessor
    @Autowired lateinit var anchorRepository: LedgerAnchorRepository
    @Autowired lateinit var leafRepository: LedgerAnchorLeafRepository
    @Autowired lateinit var ledgerRepository: LedgerRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

    /** Creates and captures a 10_000 EUR payment, posting three ledger entries. */
    private fun capturedTransactionId(): UUID {
        val request = CreatePaymentRequest(merchantId, 10_000L, "EUR", "anchor test")
        val txn = paymentService.createPayment(request, "a-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending() // CREATED -> PENDING
        val reference = "anch-${UUID.randomUUID()}"
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = reference)
        paymentService.capturePayment(txn.id)
        return txn.id
    }

    @Test
    fun `anchor covers fresh capture entries and its root recomputes from the database`() {
        val txnId = capturedTransactionId()
        val entryIds = ledgerRepository.findByTransactionId(txnId).map { it.id }.toSet()
        assertTrue(entryIds.isNotEmpty())

        val anchor = assertNotNull(anchorProcessor.anchorPending())

        val leaves = leafRepository.findByEpochOrderByLeafIndexAsc(anchor.epoch)
        assertEquals(anchor.leafCount, leaves.size)
        assertEquals((leaves.indices).toList(), leaves.map { it.leafIndex }, "leaf indexes must be contiguous")
        assertTrue(leaves.map { it.entryId }.containsAll(entryIds), "own capture entries must be sealed")

        // independent recomputation: reload every member entry from the
        // database in leaf order and rebuild the root
        val encoded = leaves.map { leaf ->
            CanonicalLeafCodec.encode(ledgerRepository.findById(leaf.entryId).orElseThrow())
        }
        assertEquals(anchor.root, MerkleTree.rootHex(encoded))
    }

    @Test
    fun `anchoring with no new entries is a no-op`() {
        capturedTransactionId()
        var drained = anchorProcessor.anchorPending()
        while (drained != null) {
            drained = anchorProcessor.anchorPending()
        }
        assertNull(anchorProcessor.anchorPending())
    }

    @Test
    fun `a new epoch links to its predecessor by consecutive epoch and chain hash`() {
        capturedTransactionId()
        anchorProcessor.anchorPending()
        val previous = assertNotNull(anchorRepository.findTopByOrderByEpochDesc())

        capturedTransactionId()
        val next = assertNotNull(anchorProcessor.anchorPending())

        assertEquals(previous.epoch + 1, next.epoch)
        assertEquals(AnchorChain.next(previous.chainHash, next.root, next.epoch), next.chainHash)
    }

    @Test
    fun `anchor tables reject update and delete`() {
        capturedTransactionId()
        anchorProcessor.anchorPending()
        val anchor = assertNotNull(anchorRepository.findTopByOrderByEpochDesc())

        val update = assertFailsWith<DataAccessException> {
            jdbcTemplate.update(
                "UPDATE ledger_anchors SET root = ? WHERE epoch = ?",
                AnchorChain.GENESIS, anchor.epoch
            )
        }
        assertTrue(
            generateSequence(update as Throwable?) { it.cause }
                .any { it.message?.contains("append-only") == true },
            "expected the append-only trigger, got: $update"
        )

        val delete = assertFailsWith<DataAccessException> {
            jdbcTemplate.update("DELETE FROM ledger_anchor_leaves WHERE epoch = ?", anchor.epoch)
        }
        assertTrue(
            generateSequence(delete as Throwable?) { it.cause }
                .any { it.message?.contains("append-only") == true },
            "expected the append-only trigger, got: $delete"
        )
    }
}
