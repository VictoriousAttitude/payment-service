package com.paymentservice.payment

enum class PaymentStatus {
    CREATED,
    PENDING,
    AUTHORIZED,
    PARTIALLY_CAPTURED,
    CAPTURED,
    SETTLED,
    FAILED,
    PARTIALLY_REFUNDED,
    REFUNDED,
    VOIDED,
    EXPIRED;

    // Partial capture/refund self-loops: each successive partial operation
    // re-enters the same state until the running total reaches the boundary
    // (captured == amount -> CAPTURED; refunded == captured -> REFUNDED).
    // The money-safety invariants (captured <= amount, refunded <= captured)
    // are enforced from the ledger in PaymentService, not by this coarse label.
    // Capture and refund are sequential phases: once a refund starts, no further
    // capture is permitted (no PARTIALLY_REFUNDED -> capture edge).
    // VOIDED/EXPIRED release an authorization that was never captured: VOIDED is
    // the merchant cancelling it, EXPIRED is the time-bounded auth hold lapsing.
    // Both apply to a clean AUTHORIZED only (nothing posted to the ledger yet);
    // once any capture exists, the path forward is refund, not void.
    fun canTransitionTo(target: PaymentStatus): Boolean = when (this) {
        CREATED            -> target in setOf(PENDING, FAILED)
        PENDING            -> target in setOf(AUTHORIZED, FAILED)
        AUTHORIZED         -> target in setOf(PARTIALLY_CAPTURED, CAPTURED, FAILED, VOIDED, EXPIRED)
        PARTIALLY_CAPTURED -> target in setOf(PARTIALLY_CAPTURED, CAPTURED, PARTIALLY_REFUNDED, REFUNDED)
        CAPTURED           -> target in setOf(SETTLED, PARTIALLY_REFUNDED, REFUNDED)
        PARTIALLY_REFUNDED -> target in setOf(PARTIALLY_REFUNDED, REFUNDED)
        SETTLED            -> false
        FAILED             -> false
        REFUNDED           -> false
        VOIDED             -> false
        EXPIRED            -> false
    }

    fun transitionTo(target: PaymentStatus): PaymentStatus {
        if (!canTransitionTo(target)) {
            throw InvalidStateTransitionException(this, target)
        }
        return target
    }

    val isTerminal: Boolean
        get() = this in setOf(SETTLED, FAILED, REFUNDED, VOIDED, EXPIRED)
}

class InvalidStateTransitionException(
    val from: PaymentStatus,
    val to: PaymentStatus
) : RuntimeException("Cannot transition from $from to $to")
