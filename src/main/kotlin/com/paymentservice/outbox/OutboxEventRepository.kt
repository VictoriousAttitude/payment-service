package com.paymentservice.outbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {

    /**
     * Unlocked candidate scan: PENDING events that are due (next_attempt_at past),
     * oldest first. Only yields ids to process; the row lock that actually gates
     * concurrent dispatchers is taken per-event in lockPendingById.
     */
    @Query(
        value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING' AND next_attempt_at <= now()
            ORDER BY created_at ASC
            LIMIT :limit
        """,
        nativeQuery = true
    )
    fun findDispatchable(@Param("limit") limit: Int): List<OutboxEvent>

    /**
     * Claims a single PENDING event for this transaction with FOR UPDATE SKIP
     * LOCKED: a row already locked by another instance's dispatch transaction is
     * skipped (returns null) rather than blocking, so N dispatchers drain the
     * outbox in parallel without grabbing the same row. The lock is held until
     * the surrounding transaction commits.
     */
    @Query(
        value = """
            SELECT * FROM outbox_events
            WHERE id = :id AND status = 'PENDING'
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun lockPendingById(@Param("id") id: UUID): OutboxEvent?

    fun findByAggregateId(aggregateId: UUID): List<OutboxEvent>
}
