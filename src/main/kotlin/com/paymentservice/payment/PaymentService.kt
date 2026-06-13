package com.paymentservice.payment

import com.paymentservice.ledger.LedgerService
import com.paymentservice.merchant.MerchantRepository
import com.paymentservice.merchant.MerchantStatus
import com.paymentservice.payment.dto.CreatePaymentRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.util.UUID

@Service
class PaymentService(
    private val transactionRepository: TransactionRepository,
    private val merchantRepository: MerchantRepository,
    private val ledgerService: LedgerService
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

        val transaction = Transaction(
            merchantId = merchant.id,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            amount = request.amount,
            currency = request.currency.uppercase(),
            description = request.description,
            paymentMethod = request.paymentMethod
        )

        // Transition CREATED → PENDING (sent to provider)
        transaction.transitionTo(PaymentStatus.PENDING)

        return try {
            PaymentResult(transactionRepository.save(transaction), created = true)
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

        transaction.transitionTo(target)
        if (authorized) {
            transaction.providerReference = providerReference
        } else {
            transaction.failureReason = "Provider declined"
        }
        transactionRepository.save(transaction)
        return CallbackOutcome.APPLIED
    }

    /**
     * Layer 2 (Core): capture is the critical financial operation.
     * Status change + ledger entries in ONE database transaction.
     * Both succeed or both rollback. No partial state possible.
     */
    @Transactional
    fun capturePayment(transactionId: UUID): Transaction {
        val transaction = findTransaction(transactionId)

        // State machine validates AUTHORIZED → CAPTURED
        transaction.transitionTo(PaymentStatus.CAPTURED)

        // Ledger: create double-entry records (validates balance before persist)
        ledgerService.createCaptureEntries(
            transactionId = transaction.id,
            merchantId = transaction.merchantId,
            amount = transaction.amount,
            currency = transaction.currency
        )

        return transactionRepository.save(transaction)
    }

    /**
     * Layer 2 (Core): refund reverses ledger entries.
     * Same atomic guarantee as capture.
     */
    @Transactional
    fun refundPayment(transactionId: UUID): Transaction {
        val transaction = findTransaction(transactionId)

        // State machine validates CAPTURED → REFUNDED
        transaction.transitionTo(PaymentStatus.REFUNDED)

        // Ledger: create reverse entries (validates balance before persist)
        ledgerService.createRefundEntries(
            transactionId = transaction.id,
            merchantId = transaction.merchantId,
            amount = transaction.amount,
            currency = transaction.currency
        )

        return transactionRepository.save(transaction)
    }

    fun getPayment(transactionId: UUID): Transaction {
        return findTransaction(transactionId)
    }

    private fun findTransaction(id: UUID): Transaction {
        return transactionRepository.findById(id)
            .orElseThrow { TransactionNotFoundException(id) }
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

// Domain exceptions
class MerchantNotFoundException(val merchantId: UUID) :
    RuntimeException("Merchant not found: $merchantId")

class MerchantNotActiveException(val merchantId: UUID) :
    RuntimeException("Merchant is not active: $merchantId")

class TransactionNotFoundException(val transactionId: UUID) :
    RuntimeException("Transaction not found: $transactionId")

class IdempotencyKeyReuseException(val idempotencyKey: String) :
    RuntimeException("Idempotency key reused with a different payload: $idempotencyKey")
