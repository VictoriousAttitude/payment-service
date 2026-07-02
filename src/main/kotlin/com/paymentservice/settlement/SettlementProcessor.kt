package com.paymentservice.settlement

import com.paymentservice.ledger.LedgerService
import com.paymentservice.payment.PaymentStatus
import com.paymentservice.payment.TransactionEvent
import com.paymentservice.payment.TransactionEventRepository
import com.paymentservice.payment.TransactionRepository
import com.paymentservice.payout.ReserveService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Per-transaction settlement unit of work. Separate bean from the batch so each
 * settles in its own transaction (the @Transactional proxy only applies across
 * bean boundaries, not on self-invocation).
 *
 * Settlement is both the lifecycle milestone (funds cleared with the acquirer,
 * T+N) and a money movement: the merchant's captured net leaves the pending
 * MERCHANT account and splits into MERCHANT_PAYABLE (available for payout) and
 * MERCHANT_RESERVE (rolling reserve withheld against chargeback exposure),
 * atomically with the CAPTURED -> SETTLED transition.
 */
@Component
class SettlementProcessor(
    private val transactionRepository: TransactionRepository,
    private val transactionEventRepository: TransactionEventRepository,
    private val ledgerService: LedgerService,
    private val reserveService: ReserveService
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Transitions CAPTURED -> SETTLED and posts the settlement split. Idempotent:
     * a row that already left CAPTURED (settled by a prior run, or refunded) is
     * skipped. Returns the currency on success for metric tagging, null if
     * nothing was done. The @Version column rejects a concurrent writer's stale
     * update, and uq_reserve_holds_txn is the DB backstop against a double split.
     */
    @Transactional
    fun settle(transactionId: UUID): String? {
        val transaction = transactionRepository.findById(transactionId).orElse(null) ?: return null
        if (transaction.status != PaymentStatus.CAPTURED) return null

        transaction.transitionTo(PaymentStatus.SETTLED)
        transactionRepository.save(transaction)
        transactionEventRepository.save(
            TransactionEvent(
                transactionId = transaction.id,
                fromStatus = PaymentStatus.CAPTURED,
                toStatus = PaymentStatus.SETTLED
            )
        )

        // A pre-settlement chargeback (or refunds) can leave the merchant net at
        // or below zero: there is nothing to clear, and a DEBIT MERCHANT leg of
        // a non-positive amount would violate CHECK (amount > 0). Settle the
        // status only and post nothing.
        val net = ledgerService.merchantNetForTransaction(transaction.id)
        if (net <= 0) {
            log.info("Settlement split skipped for txn={}: merchant net {} <= 0", transaction.id, net)
            return transaction.currency
        }

        val reserve = reserveService.reserveAmount(net)
        ledgerService.createSettlementSplitEntries(
            transactionId = transaction.id,
            merchantId = transaction.merchantId,
            net = net,
            reserve = reserve,
            currency = transaction.currency
        )
        if (reserve > 0) {
            reserveService.createHold(transaction.id, transaction.merchantId, reserve, transaction.currency)
        }
        return transaction.currency
    }
}
