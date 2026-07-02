package com.paymentservice.payout

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface ReserveHoldRepository : JpaRepository<ReserveHold, UUID> {

    /** Holds due for release: HELD and past their release time. */
    fun findByStatusAndReleaseAtLessThanEqual(
        status: ReserveHoldStatus,
        cutoff: Instant,
        pageable: Pageable
    ): List<ReserveHold>

    /** At most one hold per transaction (uq_reserve_holds_txn). */
    fun findByTransactionId(transactionId: UUID): ReserveHold?
}
