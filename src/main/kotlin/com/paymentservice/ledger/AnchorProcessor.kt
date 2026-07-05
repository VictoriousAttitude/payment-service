package com.paymentservice.ledger

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Seals every unanchored ledger entry older than the safety lag into a new
 * epoch: leaf memberships, RFC 6962 root, and the chain link are written in
 * one transaction, so an epoch either exists completely or not at all.
 *
 * Anchoring is attest-only: it never mutates payment or ledger state and
 * nothing gates on it. Entries are hashed as loaded from the database in this
 * transaction (microsecond timestamps), never from in-memory instances, so
 * the root commits to what the database durably holds.
 *
 * Split from [AnchorBatch] because @Transactional is proxy-based and would be
 * bypassed by self-invocation from the @Scheduled method.
 */
@Component
class AnchorProcessor(
    private val anchorRepository: LedgerAnchorRepository,
    private val leafRepository: LedgerAnchorLeafRepository,
    private val meterRegistry: MeterRegistry,
    @Value("\${payment.anchor.lag-seconds:60}") private val lagSeconds: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun anchorPending(): LedgerAnchor? {
        val cutoff = Instant.now().minusSeconds(lagSeconds)
        val entries = leafRepository.findUnanchored(cutoff)
        if (entries.isEmpty()) {
            return null
        }
        val previous = anchorRepository.findTopByOrderByEpochDesc()
        val epoch = (previous?.epoch ?: 0L) + 1L
        val root = MerkleTree.rootHex(entries.map(CanonicalLeafCodec::encode))
        val chainHash = AnchorChain.next(previous?.chainHash ?: AnchorChain.GENESIS, root, epoch)

        val anchor = anchorRepository.save(
            LedgerAnchor(epoch = epoch, root = root, chainHash = chainHash, leafCount = entries.size)
        )
        leafRepository.saveAll(
            entries.mapIndexed { index, entry ->
                LedgerAnchorLeaf(epoch = epoch, leafIndex = index, entryId = entry.id)
            }
        )
        meterRegistry.counter("ledger.anchors.created").increment()
        meterRegistry.counter("ledger.anchor.leaves").increment(entries.size.toDouble())
        log.info("Sealed ledger anchor epoch={} leaves={} root={}", epoch, entries.size, root)
        return anchor
    }
}
