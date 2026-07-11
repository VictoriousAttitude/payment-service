package com.paymentservice.ledger

import com.paymentservice.shared.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class LedgerService(
    private val ledgerRepository: LedgerRepository,
    private val snapshotRepository: BalanceSnapshotRepository,
    private val cursorRepository: SnapshotCursorRepository
) {

    companion object {
        const val PLATFORM_FEE_BPS = 200L // 2.00% in basis points (1 bp = 0.01%)

        // Flat dispute fee levied on a lost chargeback, in the minor unit. Real
        // acquirers charge a fixed fee (e.g. Stripe $15), not a percentage — it
        // covers the network's fixed handling cost regardless of ticket size.
        const val CHARGEBACK_FEE = 1_500L
        val PLATFORM_ACCOUNT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

        // Fee-leg descriptions. The platform account is credited on both a
        // capture and a chargeback and debited on a refund, so account type
        // alone cannot tell the three fee movements apart. The settlement
        // extract keys on these to attribute each fee to its movement, so they
        // are constants rather than inline literals: a silent edit here would
        // misclassify fees in the exported clearing file.
        const val DESC_PLATFORM_FEE = "Platform fee"
        const val DESC_REFUND_FEE = "Refund: return platform fee"
        const val DESC_CHARGEBACK_FEE = "Chargeback fee"

        // Treasury-posting descriptions (settlement split, reserve lifecycle).
        const val DESC_SETTLEMENT_CLEARED = "Settlement: funds cleared"
        const val DESC_RESERVE_HELD = "Reserve: withheld at settlement"
        const val DESC_RESERVE_RELEASED = "Reserve: released to payable"
        const val DESC_PAYOUT = "Payout: disbursed to merchant"
        const val DESC_PAYOUT_REVERSAL = "Payout failed: funds returned to payable"

        /**
         * Platform fee in the minor unit, floored. Rounding is deliberate and in
         * the merchant's favor: the fractional minor unit is never charged, it
         * stays with the merchant via merchantAmount = amount - fee. Two
         * consequences we rely on:
         *  - the platform never over-collects on a fractional cent;
         *  - the entry set always balances exactly (no residual to reconcile),
         *    because the merchant leg absorbs the truncated remainder.
         * floorDiv (not `/`) states the direction explicitly; amounts are
         * non-negative so the two agree, but the intent must not be implicit.
         */
        fun platformFee(amount: Long): Long = Math.floorDiv(amount * PLATFORM_FEE_BPS, 10_000)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun createCaptureEntries(transactionId: UUID, merchantId: UUID, amount: Long, currency: String) {
        val fee = platformFee(amount)
        val merchantAmount = amount - fee

        val entries = listOf(
            LedgerEntry(
                transactionId = transactionId,
                postingGroupId = transactionId,
                accountType = AccountType.INCOMING,
                accountId = transactionId, // incoming funds linked to this transaction
                entryType = EntryType.DEBIT,
                amount = amount,
                currency = currency,
                description = "Payment captured"
            ),
            LedgerEntry(
                transactionId = transactionId,
                postingGroupId = transactionId,
                accountType = AccountType.MERCHANT,
                accountId = merchantId,
                entryType = EntryType.CREDIT,
                amount = merchantAmount,
                currency = currency,
                description = "Merchant payout (net of fee)"
            ),
            LedgerEntry(
                transactionId = transactionId,
                postingGroupId = transactionId,
                accountType = AccountType.PLATFORM,
                accountId = PLATFORM_ACCOUNT_ID,
                entryType = EntryType.CREDIT,
                amount = fee,
                currency = currency,
                description = DESC_PLATFORM_FEE
            )
        )

        validateBalance(entries)
        ledgerRepository.saveAll(entries)
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun createRefundEntries(transactionId: UUID, merchantId: UUID, amount: Long, currency: String) {
        val fee = platformFee(amount)
        val merchantAmount = amount - fee

        val entries = listOf(
            LedgerEntry(
                transactionId = transactionId,
                postingGroupId = transactionId,
                accountType = AccountType.MERCHANT,
                accountId = merchantId,
                entryType = EntryType.DEBIT,
                amount = merchantAmount,
                currency = currency,
                description = "Refund: debit merchant"
            ),
            LedgerEntry(
                transactionId = transactionId,
                postingGroupId = transactionId,
                accountType = AccountType.PLATFORM,
                accountId = PLATFORM_ACCOUNT_ID,
                entryType = EntryType.DEBIT,
                amount = fee,
                currency = currency,
                description = DESC_REFUND_FEE
            ),
            LedgerEntry(
                transactionId = transactionId,
                postingGroupId = transactionId,
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

    /**
     * Posts the money movement for a lost chargeback. The cardholder's bank
     * reverses the sale: the merchant is debited the full disputed amount (the
     * original platform fee is NOT returned — the merchant eats it, as acquirers
     * do) and the funds leave via the CHARGEBACK flow account to the cardholder.
     * On top, a flat dispute fee is charged: debit merchant, credit platform.
     * The set balances (debits = amount + fee = credits) and posts nothing that
     * sumRefunded/sumCaptured would miscount.
     *
     * [settled] selects which merchant pot pays: before settlement the funds
     * still sit in the pending MERCHANT account; after the settlement split
     * they have moved to MERCHANT_PAYABLE, so a settled chargeback debits
     * PAYABLE instead - which MAY go negative (the merchant owes the platform;
     * the payable floor trigger exempts groups without a PAYOUT_CLEARING leg).
     * The CHARGEBACK/PLATFORM credit legs are identical in both cases, so the
     * settlement extract keeps matching them.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun createChargebackEntries(
        transactionId: UUID,
        merchantId: UUID,
        amount: Long,
        currency: String,
        settled: Boolean
    ) {
        val fee = CHARGEBACK_FEE
        val merchantSide = if (settled) AccountType.MERCHANT_PAYABLE else AccountType.MERCHANT

        val entries = listOf(
            LedgerEntry(
                transactionId = transactionId,
                postingGroupId = transactionId,
                accountType = merchantSide,
                accountId = merchantId,
                entryType = EntryType.DEBIT,
                amount = amount,
                currency = currency,
                description = "Chargeback: funds reversed"
            ),
            LedgerEntry(
                transactionId = transactionId,
                postingGroupId = transactionId,
                accountType = AccountType.CHARGEBACK,
                accountId = transactionId,
                entryType = EntryType.CREDIT,
                amount = amount,
                currency = currency,
                description = "Chargeback: returned to cardholder"
            ),
            LedgerEntry(
                transactionId = transactionId,
                postingGroupId = transactionId,
                accountType = merchantSide,
                accountId = merchantId,
                entryType = EntryType.DEBIT,
                amount = fee,
                currency = currency,
                description = DESC_CHARGEBACK_FEE
            ),
            LedgerEntry(
                transactionId = transactionId,
                postingGroupId = transactionId,
                accountType = AccountType.PLATFORM,
                accountId = PLATFORM_ACCOUNT_ID,
                entryType = EntryType.CREDIT,
                amount = fee,
                currency = currency,
                description = DESC_CHARGEBACK_FEE
            )
        )

        validateBalance(entries)
        ledgerRepository.saveAll(entries)
    }

    /**
     * Posts the settlement split: the merchant's captured net moves out of the
     * pending MERCHANT account into MERCHANT_PAYABLE (available for payout),
     * less the rolling reserve slice withheld in MERCHANT_RESERVE. Runs inside
     * the settle() transaction (MANDATORY), atomic with CAPTURED -> SETTLED.
     * The reserve leg is skipped when the floor rounds it to zero - a
     * zero-amount entry would violate CHECK (amount > 0).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun createSettlementSplitEntries(
        transactionId: UUID,
        merchantId: UUID,
        net: Long,
        reserve: Long,
        currency: String
    ) {
        val entries = buildList {
            add(
                LedgerEntry(
                    transactionId = transactionId,
                    postingGroupId = transactionId,
                    accountType = AccountType.MERCHANT,
                    accountId = merchantId,
                    entryType = EntryType.DEBIT,
                    amount = net,
                    currency = currency,
                    description = DESC_SETTLEMENT_CLEARED
                )
            )
            if (reserve > 0) {
                add(
                    LedgerEntry(
                        transactionId = transactionId,
                        postingGroupId = transactionId,
                        accountType = AccountType.MERCHANT_RESERVE,
                        accountId = merchantId,
                        entryType = EntryType.CREDIT,
                        amount = reserve,
                        currency = currency,
                        description = DESC_RESERVE_HELD
                    )
                )
            }
            add(
                LedgerEntry(
                    transactionId = transactionId,
                    postingGroupId = transactionId,
                    accountType = AccountType.MERCHANT_PAYABLE,
                    accountId = merchantId,
                    entryType = EntryType.CREDIT,
                    amount = net - reserve,
                    currency = currency,
                    description = DESC_SETTLEMENT_CLEARED
                )
            )
        }

        validateBalance(entries)
        ledgerRepository.saveAll(entries)
    }

    /**
     * Moves a matured reserve hold back into the payable balance. A treasury
     * posting: no payment transaction, the posting group is the hold id.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun createReserveReleaseEntries(holdId: UUID, merchantId: UUID, amount: Long, currency: String) {
        val entries = listOf(
            LedgerEntry(
                transactionId = null,
                postingGroupId = holdId,
                accountType = AccountType.MERCHANT_RESERVE,
                accountId = merchantId,
                entryType = EntryType.DEBIT,
                amount = amount,
                currency = currency,
                description = DESC_RESERVE_RELEASED
            ),
            LedgerEntry(
                transactionId = null,
                postingGroupId = holdId,
                accountType = AccountType.MERCHANT_PAYABLE,
                accountId = merchantId,
                entryType = EntryType.CREDIT,
                amount = amount,
                currency = currency,
                description = DESC_RESERVE_RELEASED
            )
        )

        validateBalance(entries)
        ledgerRepository.saveAll(entries)
    }

    /**
     * The merchant's net position from one transaction (credits - debits on the
     * MERCHANT account): captures minus refunds minus pre-settlement
     * chargebacks. This is exactly what the settlement split moves.
     */
    fun merchantNetForTransaction(transactionId: UUID): Long =
        ledgerRepository.merchantNetForTransaction(transactionId)

    /**
     * Moves a payout out of the payable balance into the clearing account
     * (funds in transit to the merchant's bank). Treasury posting: no payment
     * transaction, the posting group is the payout id. The clearing account is
     * keyed by merchant so in-transit funds stay attributable per merchant.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun createPayoutEntries(payoutId: UUID, merchantId: UUID, amount: Long, currency: String) {
        val entries = listOf(
            LedgerEntry(
                transactionId = null,
                postingGroupId = payoutId,
                accountType = AccountType.MERCHANT_PAYABLE,
                accountId = merchantId,
                entryType = EntryType.DEBIT,
                amount = amount,
                currency = currency,
                description = DESC_PAYOUT
            ),
            LedgerEntry(
                transactionId = null,
                postingGroupId = payoutId,
                accountType = AccountType.PAYOUT_CLEARING,
                accountId = merchantId,
                entryType = EntryType.CREDIT,
                amount = amount,
                currency = currency,
                description = DESC_PAYOUT
            )
        )

        validateBalance(entries)
        ledgerRepository.saveAll(entries)
    }

    /**
     * Compensates a failed payout: the in-transit funds return from clearing to
     * the payable balance. Posted into the SAME posting group as the original
     * payout, so the group stays balanced and the payout's full money history
     * reads under one key.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun createPayoutReversalEntries(payoutId: UUID, merchantId: UUID, amount: Long, currency: String) {
        val entries = listOf(
            LedgerEntry(
                transactionId = null,
                postingGroupId = payoutId,
                accountType = AccountType.PAYOUT_CLEARING,
                accountId = merchantId,
                entryType = EntryType.DEBIT,
                amount = amount,
                currency = currency,
                description = DESC_PAYOUT_REVERSAL
            ),
            LedgerEntry(
                transactionId = null,
                postingGroupId = payoutId,
                accountType = AccountType.MERCHANT_PAYABLE,
                accountId = merchantId,
                entryType = EntryType.CREDIT,
                amount = amount,
                currency = currency,
                description = DESC_PAYOUT_REVERSAL
            )
        )

        validateBalance(entries)
        ledgerRepository.saveAll(entries)
    }

    /** Net payable (available-for-payout) balance for a merchant in one currency. */
    @Transactional(readOnly = true)
    fun getPayableBalance(merchantId: UUID, currency: String): Long =
        accountBalance(AccountType.MERCHANT_PAYABLE, merchantId, currency.uppercase())

    /**
     * Per-currency balances of any account - pending, payable, reserve,
     * clearing. Reads via the rolling checkpoint like [accountBalance]: the
     * folded snapshot rows merged with a per-currency SUM over only the
     * entries after the cursor. The currency set is the union of both sides —
     * a snapshot row exists iff the currency had entries at or before the
     * cursor, a tail row iff it had entries after, so the union equals the
     * currencies a full-history GROUP BY would return. Same MVCC rule as
     * [accountBalance]: one read-only transaction so a concurrent fold cannot
     * advance the cursor between the reads and double-count the folded window.
     */
    @Transactional(readOnly = true)
    fun getBalancesByCurrency(accountType: AccountType, accountId: UUID): List<CurrencyBalance> {
        val cursor = cursorRepository.findById(SnapshotProcessor.CURSOR_ID).orElseThrow().asOf
        val folded = snapshotRepository
            .findByAccountTypeAndAccountId(accountType, accountId)
            .map { CurrencyBalance(it.currency, it.totalDebits, it.totalCredits) }
        val tail = ledgerRepository.balancesByCurrencySince(accountType, accountId, cursor)
        return (folded + tail)
            .groupBy { it.currency }
            .map { (currency, parts) ->
                CurrencyBalance(currency, parts.sumOf { it.totalDebits }, parts.sumOf { it.totalCredits })
            }
            .sortedBy { it.currency }
    }

    /** Merchants whose payable balance can fund at least [minimum], per currency. */
    fun payableBalancesAtLeast(minimum: Long): List<AccountCurrencyBalance> =
        ledgerRepository.payableBalancesAtLeast(minimum)

    @Transactional(readOnly = true)
    fun getMerchantBalance(merchantId: UUID, currency: String): Long =
        accountBalance(AccountType.MERCHANT, merchantId, currency.uppercase())

    /**
     * Balance via the rolling checkpoint: the folded snapshot (all entries at or
     * before the cursor) plus a live SUM over only the entries after the cursor.
     * Exact at every cursor value - before the first fold there is no snapshot
     * row and the cursor sits at the epoch, so it degenerates to the full SUM.
     * Read in one (read-only) transaction so cursor, snapshot and delta share a
     * single MVCC snapshot: a concurrent fold cannot advance the cursor between
     * the three reads and double-count the folded window.
     */
    private fun accountBalance(accountType: AccountType, accountId: UUID, currency: String): Long {
        val cursor = cursorRepository.findById(SnapshotProcessor.CURSOR_ID).orElseThrow().asOf
        val folded = snapshotRepository
            .findByAccountTypeAndAccountIdAndCurrency(accountType, accountId, currency)
            ?.net ?: 0L
        return folded + ledgerRepository.netSince(accountType, accountId, currency, cursor)
    }

    fun getMerchantBalances(merchantId: UUID): List<CurrencyBalance> {
        return ledgerRepository.computeBalancesByCurrency(AccountType.MERCHANT, merchantId)
    }

    fun getEntriesForTransaction(transactionId: UUID): List<LedgerEntry> {
        return ledgerRepository.findByTransactionId(transactionId)
    }

    /** Amount captured to date, derived from the ledger (source of truth). */
    fun capturedTotal(transactionId: UUID): Long = ledgerRepository.sumCaptured(transactionId)

    /** Amount refunded to date, derived from the ledger (source of truth). */
    fun refundedTotal(transactionId: UUID): Long = ledgerRepository.sumRefunded(transactionId)

    /**
     * Asserts the entry set balances within a single currency. Folding with
     * [Money] makes both invariants explicit and machine-checked: `+` rejects a
     * cross-currency entry, so a mixed-currency set can never net to zero and
     * pass, and the debit/credit totals must match exactly.
     */
    private fun validateBalance(entries: List<LedgerEntry>) {
        val zero = Money.ofMinor(0, entries.first().currency)
        val debits = entries.filter { it.entryType == EntryType.DEBIT }
            .fold(zero) { acc, e -> acc + Money.ofMinor(e.amount, e.currency) }
        val credits = entries.filter { it.entryType == EntryType.CREDIT }
            .fold(zero) { acc, e -> acc + Money.ofMinor(e.amount, e.currency) }

        if (debits != credits) {
            throw LedgerImbalanceException(debits.minorUnits, credits.minorUnits)
        }
    }
}

class LedgerImbalanceException(
    val debits: Long,
    val credits: Long
) : RuntimeException("Ledger imbalance: debits=$debits credits=$credits")
