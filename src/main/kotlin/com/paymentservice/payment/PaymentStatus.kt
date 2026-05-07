package com.paymentservice.payment

enum class PaymentStatus {
    CREATED,
    PENDING,
    AUTHORIZED,
    CAPTURED,
    SETTLED,
    FAILED,
    REFUNDED;

    fun canTransitionTo(target: PaymentStatus): Boolean = when (this) {
        CREATED    -> target in setOf(PENDING, FAILED)
        PENDING    -> target in setOf(AUTHORIZED, FAILED)
        AUTHORIZED -> target in setOf(CAPTURED, FAILED)
        CAPTURED   -> target in setOf(SETTLED, REFUNDED)
        SETTLED    -> false
        FAILED     -> false
        REFUNDED   -> false
    }

    fun transitionTo(target: PaymentStatus): PaymentStatus {
        if (!canTransitionTo(target)) {
            throw InvalidStateTransitionException(this, target)
        }
        return target
    }

    val isTerminal: Boolean
        get() = this in setOf(SETTLED, FAILED, REFUNDED)
}

class InvalidStateTransitionException(
    val from: PaymentStatus,
    val to: PaymentStatus
) : RuntimeException("Cannot transition from $from to $to")
