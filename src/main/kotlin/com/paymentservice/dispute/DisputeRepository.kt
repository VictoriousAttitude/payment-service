package com.paymentservice.dispute

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface DisputeRepository : JpaRepository<Dispute, UUID> {

    fun findByTransactionIdOrderByCreatedAtAsc(transactionId: UUID): List<Dispute>

    /**
     * A transaction may carry at most one live (non-terminal) dispute at a time;
     * this guards opening a second one while one is still in flight.
     */
    fun existsByTransactionIdAndStatusIn(transactionId: UUID, statuses: Collection<DisputeStatus>): Boolean
}
