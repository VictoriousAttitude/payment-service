package com.paymentservice.config

import com.paymentservice.ledger.LedgerImbalanceException
import com.paymentservice.merchant.MerchantNotActiveException
import com.paymentservice.merchant.MerchantNotFoundException
import com.paymentservice.payment.IdempotencyKeyReuseException
import com.paymentservice.payment.InvalidPaymentAmountException
import com.paymentservice.payment.InvalidStateTransitionException
import com.paymentservice.payment.TransactionNotFoundException
import com.paymentservice.shared.ErrorResponse
import com.paymentservice.shared.PaymentAccessDeniedException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(TransactionNotFoundException::class)
    fun handleNotFound(e: TransactionNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse("TRANSACTION_NOT_FOUND", e.message ?: "Transaction not found")
        )
    }

    @ExceptionHandler(MerchantNotFoundException::class)
    fun handleMerchantNotFound(e: MerchantNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse("MERCHANT_NOT_FOUND", e.message ?: "Merchant not found")
        )
    }

    @ExceptionHandler(MerchantNotActiveException::class)
    fun handleMerchantNotActive(e: MerchantNotActiveException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse("MERCHANT_NOT_ACTIVE", e.message ?: "Merchant is not active")
        )
    }

    @ExceptionHandler(InvalidStateTransitionException::class)
    fun handleInvalidTransition(e: InvalidStateTransitionException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(
                error = "INVALID_STATE_TRANSITION",
                message = e.message ?: "Invalid state transition",
                details = mapOf("from" to e.from.name, "to" to e.to.name)
            )
        )
    }

    @ExceptionHandler(LedgerImbalanceException::class)
    fun handleLedgerImbalance(e: LedgerImbalanceException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ErrorResponse("LEDGER_IMBALANCE", e.message ?: "Ledger entries do not balance")
        )
    }

    // 422 per IETF httpapi idempotency-key-header draft: key reuse with a
    // different payload is unprocessable, not a successful replay
    @ExceptionHandler(IdempotencyKeyReuseException::class)
    fun handleIdempotencyKeyReuse(e: IdempotencyKeyReuseException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ErrorResponse("IDEMPOTENCY_KEY_REUSE", e.message ?: "Idempotency key reused with a different payload")
        )
    }

    // 422: a syntactically valid capture/refund amount that breaks a domain
    // bound (over-capture, over-refund, nothing left). Not a 400 — the request
    // is well-formed; it's the state that makes it unprocessable.
    @ExceptionHandler(InvalidPaymentAmountException::class)
    fun handleInvalidAmount(e: InvalidPaymentAmountException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(
            ErrorResponse("INVALID_PAYMENT_AMOUNT", e.message ?: "Invalid payment amount")
        )
    }

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLock(e: OptimisticLockingFailureException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(
                "CONCURRENT_MODIFICATION",
                "Transaction was modified concurrently; retry with current state"
            )
        )
    }

    @ExceptionHandler(PaymentAccessDeniedException::class)
    fun handleAccessDenied(e: PaymentAccessDeniedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
            ErrorResponse("ACCESS_DENIED", e.message ?: "Access denied")
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val errors = e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "invalid") }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse("VALIDATION_ERROR", "Request validation failed", errors)
        )
    }
}
