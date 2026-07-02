package com.paymentservice.payout

/**
 * Lifecycle of a payout batch. PENDING on creation (funds moved to
 * PAYOUT_CLEARING, submitted to the bank); PAID once the disbursement is
 * confirmed (a milestone, no money moves); FAILED when the bank rejects it
 * (compensating reversal restores the payable balance). Both terminal.
 */
enum class PayoutStatus {
    PENDING,
    PAID,
    FAILED;

    fun canTransitionTo(target: PayoutStatus): Boolean = when (this) {
        PENDING -> target in setOf(PAID, FAILED)
        PAID    -> false
        FAILED  -> false
    }

    fun transitionTo(target: PayoutStatus): PayoutStatus {
        if (!canTransitionTo(target)) {
            throw InvalidPayoutTransitionException(this, target)
        }
        return target
    }

    val isTerminal: Boolean
        get() = this in setOf(PAID, FAILED)
}

class InvalidPayoutTransitionException(
    val from: PayoutStatus,
    val to: PayoutStatus
) : RuntimeException("Cannot transition payout from $from to $to")
