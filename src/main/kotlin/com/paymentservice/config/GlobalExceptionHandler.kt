package com.paymentservice.config

import com.paymentservice.ledger.LedgerImbalanceException
import com.paymentservice.payment.InvalidStateTransitionException
import com.paymentservice.payment.MerchantNotActiveException
import com.paymentservice.payment.MerchantNotFoundException
import com.paymentservice.payment.TransactionNotFoundException
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

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleOptimisticLock(e: OptimisticLockingFailureException): ResponseEntity<ErrorResponse> {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(
                "CONCURRENT_MODIFICATION",
                "Transaction was modified concurrently; retry with current state"
            )
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

data class ErrorResponse(
    val error: String,
    val message: String,
    val details: Map<String, String>? = null
)
