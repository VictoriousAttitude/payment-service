package com.paymentservice.outbox

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OutboxEventRepository : JpaRepository<OutboxEvent, UUID> {

    /**
     * Oldest-first batch of undispatched events. A multi-instance deployment
     * would add FOR UPDATE SKIP LOCKED to avoid two dispatchers grabbing the
     * same row; the consumer (provider webhook) is idempotent regardless.
     */
    fun findByStatusOrderByCreatedAtAsc(status: OutboxStatus, pageable: Pageable): List<OutboxEvent>

    fun findByAggregateId(aggregateId: UUID): List<OutboxEvent>
}
