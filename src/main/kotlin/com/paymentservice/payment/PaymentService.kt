package com.paymentservice.payment

import com.paymentservice.ledger.LedgerService
import com.paymentservice.merchant.MerchantNotActiveException
import com.paymentservice.merchant.MerchantNotFoundException
import com.paymentservice.merchant.MerchantRepository
import com.paymentservice.merchant.MerchantStatus
import com.paymentservice.payment.dto.CreatePaymentRequest
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.UUID

@Service
class PaymentService(
    private val transactionRepository: TransactionRepository,
    private val merchantRepository: MerchantRepository,
    private val ledgerService: LedgerService,
    private val paymentCreator: PaymentCreator,
    private val transactionEventRepository: TransactionEventRepository,
    private val paymentOperationRepository: PaymentOperationRepository,
    private val meterRegistry: MeterRegistry
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Layer 1 (Gate): idempotency.
     * - Replayed key with identical payload returns the existing transaction.
     * - Replayed key with a different payload is rejected (client bug, not a retry).
     * - Concurrent requests with the same key race on the DB unique constraint;
     *   the loser catches the violation and returns the winner's row.
     *
     * Deliberately NOT @Transactional: after a unique violation the surrounding
     * DB transaction would be aborted and the recovery re-fetch impossible.
     * The single write (save) is atomic on its own.
     */
    fun createPayment(request: CreatePaymentRequest, idempotencyKey: String): PaymentResult {
        val requestHash = fingerprint(request)

        // Gate: replay check
        transactionRepository.findByMerchantIdAndIdempotencyKey(request.merchantId, idempotencyKey)
            ?.let { return replayed(it, requestHash, idempotencyKey) }

        // Validate merchant exists and is active
        val merchant = merchantRepository.findById(request.merchantId)
            .orElseThrow { MerchantNotFoundException(request.merchantId) }

        if (merchant.status != MerchantStatus.ACTIVE) {
            throw MerchantNotActiveException(merchant.id)
        }

        return try {
            // Persists the transaction (CREATED) and its provider-dispatch outbox
            // event atomically. The dispatcher transitions it to PENDING.
            val transaction = paymentCreator.create(merchant.id, idempotencyKey, requestHash, request)
            PaymentResult(transaction, created = true)
        } catch (e: DataIntegrityViolationException) {
            // Lost the race: a concurrent request with the same key committed first.
            // The unique constraint is the ultimate gate; return the winner's row.
            val winner = transactionRepository.findByMerchantIdAndIdempotencyKey(
                request.merchantId, idempotencyKey
            ) ?: throw e // violation was not the idempotency constraint
            replayed(winner, requestHash, idempotencyKey)
        }
    }

    private fun replayed(existing: Transaction, requestHash: String, idempotencyKey: String): PaymentResult {
        // Empty stored hash = pre-fingerprint row; skip the payload check
        if (existing.requestHash.isNotEmpty() && existing.requestHash != requestHash) {
            throw IdempotencyKeyReuseException(idempotencyKey)
        }
        return PaymentResult(existing, created = false)
    }

    private fun fingerprint(request: CreatePaymentRequest): String {
        val canonical = listOf(
            request.merchantId,
            request.amount,
            request.currency.uppercase(),
            request.description,
            request.paymentMethod
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Handles async callback from payment provider. Idempotent by design:
     * providers retry on any non-2xx (Stripe retries for days), so this must
     * never throw on a duplicate or late callback — that would 500 and trigger
     * an endless retry storm.
     *
     * - Already in target state -> duplicate, ack and no-op.
     * - Valid forward transition -> apply.
     * - Invalid/late (e.g. callback arrives after we already CAPTURED) -> warn,
     *   ack and no-op.
     */
    @Transactional
    fun handleProviderCallback(
        transactionId: UUID,
        authorized: Boolean,
        providerReference: String?
    ): CallbackOutcome {
        MDC.putCloseable(MDC_TXN_ID, transactionId.toString()).use {
            val outcome = applyProviderCallback(transactionId, authorized, providerReference)
            meterRegistry.counter("payments.callbacks", "outcome", outcome.name).increment()
            return outcome
        }
    }

    private fun applyProviderCallback(
        transactionId: UUID,
        authorized: Boolean,
        providerReference: String?
    ): CallbackOutcome {
        val transaction = findTransaction(transactionId)
        val target = if (authorized) PaymentStatus.AUTHORIZED else PaymentStatus.FAILED

        if (transaction.status == target) {
            log.debug("Duplicate provider callback txn={} already {}", transactionId, target)
            return CallbackOutcome.DUPLICATE
        }

        if (!transaction.status.canTransitionTo(target)) {
            log.warn(
                "Ignoring late provider callback txn={} status={} target={}",
                transactionId, transaction.status, target
            )
            return CallbackOutcome.IGNORED_LATE
        }

        val from = transaction.status
        transaction.transitionTo(target)
        if (authorized) {
            transaction.providerReference = providerReference
        } else {
            transaction.failureReason = "Provider declined"
        }
        transactionRepository.save(transaction)
        recordTransition(transaction.id, from, target)
        return CallbackOutcome.APPLIED
    }

    /**
     * Layer 2 (Core): capture is the critical financial operation.
     * Status change + ledger entries in ONE database transaction.
     * Both succeed or both rollback. No partial state possible.
     *
     * Supports partial and multi-capture: [amount] null captures the full
     * remaining authorized headroom; a value captures that much, up to the
     * remaining. Captured-to-date is derived from the ledger (source of truth),
     * never from a mutable counter. The transaction lands in CAPTURED once the
     * running total reaches the authorized amount, otherwise PARTIALLY_CAPTURED.
     * A non-null [idempotencyKey] makes a single capture safely retryable.
     */
    @Transactional
    fun capturePayment(
        transactionId: UUID,
        amount: Long? = null,
        idempotencyKey: String? = null
    ): Transaction {
        MDC.putCloseable(MDC_TXN_ID, transactionId.toString()).use {
            val transaction = findTransaction(transactionId)

            // Replay: this exact capture already applied -> return current state.
            if (idempotencyKey != null) {
                paymentOperationRepository
                    .findByTransactionIdAndIdempotencyKey(transactionId, idempotencyKey)
                    ?.let { return transaction }
            }

            val capturedTotal = ledgerService.capturedTotal(transactionId)
            val remaining = transaction.amount - capturedTotal
            val captureAmount = amount ?: remaining

            if (captureAmount <= 0 || captureAmount > remaining) {
                throw InvalidPaymentAmountException(
                    "Capture amount $captureAmount invalid; remaining capturable is $remaining"
                )
            }

            val from = transaction.status
            val target = if (capturedTotal + captureAmount == transaction.amount)
                PaymentStatus.CAPTURED else PaymentStatus.PARTIALLY_CAPTURED
            transaction.transitionTo(target)

            // Ledger: create double-entry records (validates balance before persist)
            ledgerService.createCaptureEntries(
                transactionId = transaction.id,
                merchantId = transaction.merchantId,
                amount = captureAmount,
                currency = transaction.currency
            )
            paymentOperationRepository.save(
                PaymentOperation(
                    transactionId = transactionId,
                    type = OperationType.CAPTURE,
                    amount = captureAmount,
                    idempotencyKey = idempotencyKey
                )
            )

            val saved = transactionRepository.save(transaction)
            recordTransition(saved.id, from, target)
            meterRegistry.counter("payments.captured", "currency", transaction.currency).increment()
            return saved
        }
    }

    /**
     * Layer 2 (Core): refund reverses ledger entries.
     * Same atomic guarantee as capture.
     *
     * Supports partial refund: [amount] null refunds the full remaining
     * captured-but-not-refunded balance; a value refunds that much, up to the
     * remaining. Refunded-to-date is derived from the ledger. The transaction
     * lands in REFUNDED once refunds equal the captured total, otherwise
     * PARTIALLY_REFUNDED. A non-null [idempotencyKey] makes a single refund
     * safely retryable.
     */
    @Transactional
    fun refundPayment(
        transactionId: UUID,
        amount: Long? = null,
        idempotencyKey: String? = null
    ): Transaction {
        MDC.putCloseable(MDC_TXN_ID, transactionId.toString()).use {
            val transaction = findTransaction(transactionId)

            if (idempotencyKey != null) {
                paymentOperationRepository
                    .findByTransactionIdAndIdempotencyKey(transactionId, idempotencyKey)
                    ?.let { return transaction }
            }

            val capturedTotal = ledgerService.capturedTotal(transactionId)
            val refundedTotal = ledgerService.refundedTotal(transactionId)
            val remaining = capturedTotal - refundedTotal
            val refundAmount = amount ?: remaining

            if (refundAmount <= 0 || refundAmount > remaining) {
                throw InvalidPaymentAmountException(
                    "Refund amount $refundAmount invalid; remaining refundable is $remaining"
                )
            }

            val from = transaction.status
            val target = if (refundedTotal + refundAmount == capturedTotal)
                PaymentStatus.REFUNDED else PaymentStatus.PARTIALLY_REFUNDED
            transaction.transitionTo(target)

            // Ledger: create reverse entries (validates balance before persist)
            ledgerService.createRefundEntries(
                transactionId = transaction.id,
                merchantId = transaction.merchantId,
                amount = refundAmount,
                currency = transaction.currency
            )
            paymentOperationRepository.save(
                PaymentOperation(
                    transactionId = transactionId,
                    type = OperationType.REFUND,
                    amount = refundAmount,
                    idempotencyKey = idempotencyKey
                )
            )

            val saved = transactionRepository.save(transaction)
            recordTransition(saved.id, from, target)
            meterRegistry.counter("payments.refunded", "currency", transaction.currency).increment()
            return saved
        }
    }

    fun getPayment(transactionId: UUID): Transaction {
        return findTransaction(transactionId)
    }

    /**
     * Read model with the captured/refunded breakdown derived from the ledger.
     * The transaction row carries only the lifecycle label; the amounts come
     * from the entries so the view can never disagree with the money.
     */
    fun getPaymentView(transactionId: UUID): PaymentView {
        val transaction = findTransaction(transactionId)
        return PaymentView(
            transaction = transaction,
            capturedAmount = ledgerService.capturedTotal(transactionId),
            refundedAmount = ledgerService.refundedTotal(transactionId)
        )
    }

    fun getHistory(transactionId: UUID): List<TransactionEvent> {
        return transactionEventRepository.findByTransactionIdOrderByCreatedAtAsc(transactionId)
    }

    private fun recordTransition(transactionId: UUID, from: PaymentStatus, to: PaymentStatus) {
        transactionEventRepository.save(
            TransactionEvent(transactionId = transactionId, fromStatus = from, toStatus = to)
        )
    }

    private fun findTransaction(id: UUID): Transaction {
        return transactionRepository.findById(id)
            .orElseThrow { TransactionNotFoundException(id) }
    }

    companion object {
        private const val MDC_TXN_ID = "txnId"
    }
}

/**
 * Result of payment creation: [created] distinguishes a new transaction from
 * an idempotent replay, so callers fire side effects (provider dispatch,
 * 201 vs 200) exactly once.
 */
data class PaymentResult(
    val transaction: Transaction,
    val created: Boolean
)

/**
 * Outcome of a provider callback. All outcomes are acked with 200 — the
 * distinction is for logging/metrics, not for the HTTP response.
 */
enum class CallbackOutcome {
    APPLIED,
    DUPLICATE,
    IGNORED_LATE
}

/**
 * Read model: the transaction plus its captured/refunded totals, both derived
 * from the ledger rather than stored on the row.
 */
data class PaymentView(
    val transaction: Transaction,
    val capturedAmount: Long,
    val refundedAmount: Long
)

// Domain exceptions
class TransactionNotFoundException(val transactionId: UUID) :
    RuntimeException("Transaction not found: $transactionId")

class IdempotencyKeyReuseException(val idempotencyKey: String) :
    RuntimeException("Idempotency key reused with a different payload: $idempotencyKey")

class InvalidPaymentAmountException(message: String) : RuntimeException(message)
