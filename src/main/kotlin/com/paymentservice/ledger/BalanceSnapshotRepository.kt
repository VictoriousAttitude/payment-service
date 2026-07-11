package com.paymentservice.ledger

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface BalanceSnapshotRepository : JpaRepository<BalanceSnapshot, UUID> {

    fun findByAccountTypeAndAccountIdAndCurrency(
        accountType: AccountType,
        accountId: UUID,
        currency: String
    ): BalanceSnapshot?

    /** All folded currencies of one account: the checkpoint side of a per-currency read. */
    fun findByAccountTypeAndAccountId(accountType: AccountType, accountId: UUID): List<BalanceSnapshot>

    /**
     * Additive upsert of one account's folded delta. Native ON CONFLICT so the
     * checkpoint accumulates across folds with no read-modify-write race: a
     * concurrent fold either creates the row or atomically adds to it. Keyed on
     * the (account_type, account_id, currency) unique constraint.
     */
    @Modifying
    @Query(
        value = """
        INSERT INTO ledger_balance_snapshots
            (id, account_type, account_id, currency, total_debits, total_credits)
        VALUES (gen_random_uuid(), :accountType, :accountId, :currency, :debits, :credits)
        ON CONFLICT (account_type, account_id, currency)
        DO UPDATE SET
            total_debits  = ledger_balance_snapshots.total_debits  + EXCLUDED.total_debits,
            total_credits = ledger_balance_snapshots.total_credits + EXCLUDED.total_credits
        """,
        nativeQuery = true
    )
    fun applyDelta(accountType: String, accountId: UUID, currency: String, debits: Long, credits: Long)
}

interface SnapshotCursorRepository : JpaRepository<SnapshotCursor, Short>
