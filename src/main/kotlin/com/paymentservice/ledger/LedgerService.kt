package com.paymentservice.ledger

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class LedgerService(
    private val ledgerRepository: LedgerRepository
) {

    companion object {
        const val PLATFORM_FEE_BPS = 200L // 2.00% in basis points (1 bp = 0.01%)
        val PLATFORM_ACCOUNT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun createCaptureEntries(transactionId: UUID, merchantId: UUID, amount: Long, currency: String) {
        val fee = amount * PLATFORM_FEE_BPS / 10_000
        val merchantAmount = amount - fee

        val entries = listOf(
            LedgerEntry(
                transactionId = transactionId,
                accountType = AccountType.INCOMING,
                accountId = transactionId, // incoming funds linked to this transaction
                entryType = EntryType.DEBIT,
                amount = amount,
                currency = currency,
                description = "Payment captured"
            ),
            LedgerEntry(
                transactionId = transactionId,
                accountType = AccountType.MERCHANT,
                accountId = merchantId,
                entryType = EntryType.CREDIT,
                amount = merchantAmount,
                currency = currency,
                description = "Merchant payout (net of fee)"
            ),
            LedgerEntry(
                transactionId = transactionId,
                accountType = AccountType.PLATFORM,
                accountId = PLATFORM_ACCOUNT_ID,
                entryType = EntryType.CREDIT,
                amount = fee,
                currency = currency,
                description = "Platform fee"
            )
        )

        validateBalance(entries)
        ledgerRepository.saveAll(entries)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun createRefundEntries(transactionId: UUID, merchantId: UUID, amount: Long, currency: String) {
        val fee = amount * PLATFORM_FEE_BPS / 10_000
        val merchantAmount = amount - fee

        val entries = listOf(
            LedgerEntry(
                transactionId = transactionId,
                accountType = AccountType.MERCHANT,
                accountId = merchantId,
                entryType = EntryType.DEBIT,
                amount = merchantAmount,
                currency = currency,
                description = "Refund: debit merchant"
            ),
            LedgerEntry(
                transactionId = transactionId,
                accountType = AccountType.PLATFORM,
                accountId = PLATFORM_ACCOUNT_ID,
                entryType = EntryType.DEBIT,
                amount = fee,
                currency = currency,
                description = "Refund: return platform fee"
            ),
            LedgerEntry(
                transactionId = transactionId,
                accountType = AccountType.OUTGOING,
                accountId = transactionId,
                entryType = EntryType.CREDIT,
                amount = amount,
                currency = currency,
                description = "Refund issued"
            )
        )

        validateBalance(entries)
        ledgerRepository.saveAll(entries)
    }

    fun getMerchantBalance(merchantId: UUID): Long {
        return ledgerRepository.computeBalance(AccountType.MERCHANT, merchantId)
    }

    fun getEntriesForTransaction(transactionId: UUID): List<LedgerEntry> {
        return ledgerRepository.findByTransactionId(transactionId)
    }

    private fun validateBalance(entries: List<LedgerEntry>) {
        val totalDebits = entries.filter { it.entryType == EntryType.DEBIT }.sumOf { it.amount }
        val totalCredits = entries.filter { it.entryType == EntryType.CREDIT }.sumOf { it.amount }

        if (totalDebits != totalCredits) {
            throw LedgerImbalanceException(totalDebits, totalCredits)
        }
    }
}

class LedgerImbalanceException(
    val debits: Long,
    val credits: Long
) : RuntimeException("Ledger imbalance: debits=$debits credits=$credits")
