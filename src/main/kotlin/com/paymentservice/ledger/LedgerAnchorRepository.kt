package com.paymentservice.ledger

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface LedgerAnchorRepository : JpaRepository<LedgerAnchor, Long> {

    fun findTopByOrderByEpochDesc(): LedgerAnchor?

    fun findAllByOrderByEpochAsc(): List<LedgerAnchor>
}

interface LedgerAnchorLeafRepository : JpaRepository<LedgerAnchorLeaf, UUID> {

    fun findByEpochOrderByLeafIndexAsc(epoch: Long): List<LedgerAnchorLeaf>

    /**
     * Entries not yet sealed into any epoch, old enough per the safety lag.
     * The ORDER BY only fixes the leaf order at sealing time; once written,
     * leaf_index is the durable order the root commits to.
     */
    @Query(
        """
        SELECT e FROM LedgerEntry e
        WHERE e.createdAt < :cutoff
          AND NOT EXISTS (SELECT 1 FROM LedgerAnchorLeaf l WHERE l.entryId = e.id)
        ORDER BY e.createdAt ASC, e.id ASC
        """
    )
    fun findUnanchored(cutoff: Instant): List<LedgerEntry>

    @Query(
        """
        SELECT count(e) FROM LedgerEntry e
        WHERE e.createdAt < :cutoff
          AND NOT EXISTS (SELECT 1 FROM LedgerAnchorLeaf l WHERE l.entryId = e.id)
        """
    )
    fun countUnanchored(cutoff: Instant): Long
}
