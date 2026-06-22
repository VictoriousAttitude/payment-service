package com.paymentservice.ledger

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "ledger_entries")
class LedgerEntry(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "transaction_id", nullable = false)
    val transactionId: UUID,

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
    CHARGEBACK
}
