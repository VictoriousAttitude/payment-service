package com.paymentservice.ledger

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Materialized debit/credit checkpoint for one (account, currency), folded up
 * to the snapshot cursor. DERIVED and rebuildable: not part of the immutable
 * ledger (no append-only trigger), so reconciliation re-derives it from
 * ledger_entries and flags any drift. A balance read is snapshot.net plus a
 * live SUM over only the entries created after the cursor, never a source of
 * truth on its own.
 */
@Entity
@Table(name = "ledger_balance_snapshots")
class BalanceSnapshot(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "account_type", nullable = false)
    @Enumerated(EnumType.STRING)
    val accountType: AccountType,

    @Column(name = "account_id", nullable = false)
    val accountId: UUID,

    @Column(nullable = false, length = 3)
    val currency: String,

    @Column(name = "total_debits", nullable = false)
    val totalDebits: Long,

    @Column(name = "total_credits", nullable = false)
    val totalCredits: Long
) {
    val net: Long get() = totalCredits - totalDebits
}

/**
 * Single-row cursor (id = 1): the instant up to which the snapshots are folded.
 * A live balance sums only entries created after this, so read cost is bounded
 * by the snapshot cadence, not by total ledger size. Seeded at the epoch, so
 * before the first fold every read degenerates to the full SUM (exact, just
 * unaccelerated).
 */
@Entity
@Table(name = "ledger_snapshot_cursor")
class SnapshotCursor(

    @Id
    val id: Short = 1,

    @Column(name = "as_of", nullable = false)
    val asOf: Instant
)
