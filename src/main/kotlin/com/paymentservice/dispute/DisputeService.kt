package com.paymentservice.dispute

import com.paymentservice.ledger.LedgerService
import com.paymentservice.payment.PaymentService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class DisputeService(
    private val disputeRepository: DisputeRepository,
    private val paymentService: PaymentService,
    private val ledgerService: LedgerService,
    private val meterRegistry: MeterRegistry
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Opens a chargeback against a captured payment. The disputable amount is
     * the net captured money (captured minus already refunded): an authorization,
     * a void, or a fully refunded payment has nothing to claw back. [amount] null
     * disputes the full net-captured balance. No ledger movement here — an open
     * dispute is a contingent liability, realized only if it is lost.
     */
    @Transactional
    fun openDispute(
        transactionId: UUID,
        reason: DisputeReason,
        amount: Long? = null,
        providerReference: String? = null
    ): Dispute {
        MDC.putCloseable(MDC_TXN_ID, transactionId.toString()).use {
            val transaction = paymentService.getPayment(transactionId)

            if (disputeRepository.existsByTransactionIdAndStatusIn(transactionId, NON_TERMINAL)) {
                throw DisputeAlreadyOpenException(transactionId)
            }

            val net = ledgerService.capturedTotal(transactionId) -
                ledgerService.refundedTotal(transactionId)
            val disputed = amount ?: net

            if (net <= 0 || disputed <= 0 || disputed > net) {
                throw DisputeNotAllowedException(
                    "Dispute amount $disputed invalid; disputable (net captured) is $net"
                )
            }

            val dispute = disputeRepository.save(
                Dispute(
                    transactionId = transactionId,
                    reason = reason,
                    amount = disputed,
                    currency = transaction.currency,
                    providerReference = providerReference
                )
            )
            log.info("Dispute opened id={} txn={} amount={}", dispute.id, transactionId, disputed)
            meterRegistry.counter("disputes.opened", "currency", transaction.currency).increment()
            return dispute
        }
    }

    /** Merchant contests the chargeback: OPEN -> UNDER_REVIEW. */
    @Transactional
    fun submitEvidence(disputeId: UUID): Dispute {
        val dispute = findDispute(disputeId)
        dispute.transitionTo(DisputeStatus.UNDER_REVIEW)
        return disputeRepository.save(dispute)
    }

    /**
     * Settles the dispute. On LOST, the money is clawed back from the merchant
     * plus a flat dispute fee — posted atomically with the status change. On WON,
     * the merchant keeps the funds and nothing moves. Terminal-state guards make
     * a re-resolution throw rather than double-post.
     */
    @Transactional
    fun resolve(disputeId: UUID, won: Boolean): Dispute {
        val dispute = findDispute(disputeId)
        MDC.putCloseable(MDC_TXN_ID, dispute.transactionId.toString()).use {
            val target = if (won) DisputeStatus.WON else DisputeStatus.LOST
            dispute.transitionTo(target)

            if (!won) {
                val transaction = paymentService.getPayment(dispute.transactionId)
                ledgerService.createChargebackEntries(
                    transactionId = dispute.transactionId,
                    merchantId = transaction.merchantId,
                    amount = dispute.amount,
                    currency = dispute.currency
                )
            }

            val saved = disputeRepository.save(dispute)
            log.info("Dispute resolved id={} outcome={}", disputeId, target)
            meterRegistry.counter("disputes.resolved", "outcome", target.name).increment()
            return saved
        }
    }

    fun getDispute(disputeId: UUID): Dispute = findDispute(disputeId)

    fun getDisputesForTransaction(transactionId: UUID): List<Dispute> =
        disputeRepository.findByTransactionIdOrderByCreatedAtAsc(transactionId)

    private fun findDispute(disputeId: UUID): Dispute =
        disputeRepository.findById(disputeId)
            .orElseThrow { DisputeNotFoundException(disputeId) }

    companion object {
        private const val MDC_TXN_ID = "txnId"
        private val NON_TERMINAL = setOf(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW)
    }
}

class DisputeNotFoundException(val disputeId: UUID) :
    RuntimeException("Dispute not found: $disputeId")

class DisputeAlreadyOpenException(val transactionId: UUID) :
    RuntimeException("A live dispute already exists for transaction $transactionId")

class DisputeNotAllowedException(message: String) : RuntimeException(message)
