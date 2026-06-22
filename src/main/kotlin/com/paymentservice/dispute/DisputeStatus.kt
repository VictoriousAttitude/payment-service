package com.paymentservice.dispute

/**
 * Lifecycle of a chargeback, independent of the payment status machine: a
 * captured payment carries its own dispute. The acquirer OPENs it; the merchant
 * may contest (UNDER_REVIEW); the network rules WON (merchant keeps the funds)
 * or LOST (funds are clawed back). WON/LOST are terminal — a settled dispute is
 * final.
 */
enum class DisputeStatus {
    OPEN,
    UNDER_REVIEW,
    WON,
    LOST;

    fun canTransitionTo(target: DisputeStatus): Boolean = when (this) {
        OPEN         -> target in setOf(UNDER_REVIEW, WON, LOST)
        UNDER_REVIEW -> target in setOf(WON, LOST)
        WON          -> false
        LOST         -> false
    }

    fun transitionTo(target: DisputeStatus): DisputeStatus {
        if (!canTransitionTo(target)) {
            throw InvalidDisputeTransitionException(this, target)
        }
        return target
    }

    val isTerminal: Boolean
        get() = this in setOf(WON, LOST)
}

class InvalidDisputeTransitionException(
    val from: DisputeStatus,
    val to: DisputeStatus
) : RuntimeException("Cannot transition dispute from $from to $to")
