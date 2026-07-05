package com.paymentservice.ledger

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * One sealed epoch of the ledger Merkle chain. [root] is the RFC 6962 tree
 * root over the canonical encodings of the member entries in leaf order;
 * [chainHash] links epochs so history cannot be rewritten by replacing a
 * single anchor. Append-only, DB-trigger enforced (V22).
 */
@Entity
@Table(name = "ledger_anchors")
class LedgerAnchor(

    @Id
    val epoch: Long,

    @Column(nullable = false, length = 64)
    val root: String,

    @Column(name = "chain_hash", nullable = false, length = 64)
    val chainHash: String,

    @Column(name = "leaf_count", nullable = false)
    val leafCount: Int,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)

/**
 * Membership of one ledger entry in one epoch, at one leaf position. Explicit
 * membership (instead of a serial range) is what makes epoch boundaries safe
 * under concurrent commits: a late-committing entry lands in the next epoch
 * rather than inside a sealed one.
 */
@Entity
@Table(name = "ledger_anchor_leaves")
class LedgerAnchorLeaf(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val epoch: Long,

    @Column(name = "leaf_index", nullable = false)
    val leafIndex: Int,

    @Column(name = "entry_id", nullable = false)
    val entryId: UUID
)
