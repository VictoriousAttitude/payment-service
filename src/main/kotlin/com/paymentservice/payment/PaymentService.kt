package com.paymentservice.payment

import com.paymentservice.ledger.LedgerService
import com.paymentservice.merchant.MerchantRepository
import com.paymentservice.merchant.MerchantStatus
import com.paymentservice.payment.dto.CreatePaymentRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PaymentService(
    private val transactionRepository: TransactionRepository,
    private val merchantRepository: MerchantRepository,
    private val ledgerService: LedgerService
) {

    /**
     * Layer 1 (Gate): idempotency check — duplicate key returns existing transaction.
     * Layer 2 (Core): creates transaction with validated state.
     */
    @Transactional
    fun createPayment(request: CreatePaymentRequest, idempotencyKey: String): Transaction {
        // Gate: check idempotency
        val existing = transactionRepository.findByMerchantIdAndIdempotencyKey(
            request.merchantId, idempotencyKey
        )
        if (existing != null) return existing

        // Validate merchant exists and is active
        val merchant = merchantRepository.findById(request.merchantId)
            .orElseThrow { MerchantNotFoundException(request.merchantId) }

        if (merchant.status != MerchantStatus.ACTIVE) {
            throw MerchantNotActiveException(merchant.id)
        }

        // Create transaction
        val transaction = Transaction(
            merchantId = merchant.id,
            idempotencyKey = idempotencyKey,
            amount = request.amount,
            currency = request.currency.uppercase(),
            description = request.description,
            paymentMethod = request.paymentMethod
        )

        // Transition CREATED → PENDING (sent to provider)
        transaction.transitionTo(PaymentStatus.PENDING)

        return transactionRepository.save(transaction)
    }

    /**
     * Handles async callback from payment provider.
     * Provider tells us: authorized or failed.
     */
    @Transactional
    fun handleProviderCallback(transactionId: UUID, authorized: Boolean, providerReference: String?) {
        val transaction = findTransaction(transactionId)

        if (authorized) {
            transaction.transitionTo(PaymentStatus.AUTHORIZED)
            transaction.providerReference = providerReference
        } else {
            transaction.transitionTo(PaymentStatus.FAILED)
            transaction.failureReason = "Provider declined"
        }

        transactionRepository.save(transaction)
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

// Domain exceptions
class MerchantNotFoundException(val merchantId: UUID) :
    RuntimeException("Merchant not found: $merchantId")

class MerchantNotActiveException(val merchantId: UUID) :
    RuntimeException("Merchant is not active: $merchantId")

class TransactionNotFoundException(val transactionId: UUID) :
    RuntimeException("Transaction not found: $transactionId")
