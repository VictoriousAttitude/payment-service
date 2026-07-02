package com.paymentservice.ledger

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ledger_entries")
class LedgerEntry(

    @Id
    val id: UUID = UUID.randomUUID(),

    // NULL for treasury postings (payouts, reserve releases) that move money
    // between internal accounts with no originating payment transaction.
    @Column(name = "transaction_id")
    val transactionId: UUID?,

    // The atomicity unit of the double-entry invariant: every posting balances
    // within its group. Payment-driven postings use the transaction id;
    // treasury postings use the payout/hold id. Deliberately NO default value:
    // a `transactionId!!` default would NPE exactly on the postings this
    // column exists for, so every construction site is explicit.
    @Column(name = "posting_group_id", nullable = false)
    val postingGroupId: UUID,

    @Column(name = "account_type", nullable = false)
    @Enumerated(EnumType.STRING)
    val accountType: AccountType,

    @Column(name = "account_id", nullable = false)
    val accountId: UUID,

    @Column(name = "entry_type", nullable = false)
    @Enumerated(EnumType.STRING)
    val entryType: EntryType,

    @Column(nullable = false)
    val amount: Long,

    @Column(nullable = false, length = 3)
    val currency: String,

    val description: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
    // NO updatedAt: ledger entries are immutable
)

enum class EntryType {
    DEBIT, CREDIT
}

enum class AccountType {
    MERCHANT,
    PLATFORM,
    INCOMING,
    OUTGOING,

    // Money clawed back from the merchant to the cardholder when a chargeback is
    // lost. A distinct flow account from OUTGOING so a chargeback never counts as
    // a refund (sumRefunded keys on OUTGOING CREDIT) and the two reversal paths
    // stay independently auditable.
    CHARGEBACK,

    // Settled funds available for payout. MERCHANT holds captured-but-unsettled
    // (pending) funds; the settlement split moves the net into PAYABLE/RESERVE.
    MERCHANT_PAYABLE,

    // Rolling reserve withheld at settlement against chargeback exposure,
    // released to MERCHANT_PAYABLE after the hold period.
    MERCHANT_RESERVE,

    // Funds disbursed by a payout, in transit to the merchant's bank. A payout
    // failure reverses back to MERCHANT_PAYABLE from here.
    PAYOUT_CLEARING
}
