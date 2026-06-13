package com.paymentservice.outbox

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * A unit of work to be dispatched outside the originating transaction.
 * Persisted in the same DB transaction as the aggregate change it describes,
 * so the change and its side-effect intent commit atomically (transactional
 * outbox pattern).
 */
@Entity
@Table(name = "outbox_events")
class OutboxEvent(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "aggregate_id", nullable = false)
    val aggregateId: UUID,

    @Column(nullable = false)
    val type: String,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: OutboxStatus = OutboxStatus.PENDING,

    @Column(nullable = false)
    var attempts: Int = 0,

    @Column(name = "last_error")
    var lastError: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "dispatched_at")
    var dispatchedAt: Instant? = null
) {
    fun markDispatched() {
        status = OutboxStatus.DISPATCHED
        dispatchedAt = Instant.now()
        updatedAt = Instant.now()
    }

    fun recordFailure(error: String, maxAttempts: Int) {
        attempts++
        lastError = error.take(1000)
        updatedAt = Instant.now()
        if (attempts >= maxAttempts) {
            status = OutboxStatus.FAILED
        }
    }

    companion object {
        const val PROVIDER_AUTHORIZATION = "PROVIDER_AUTHORIZATION"
    }
}

enum class OutboxStatus {
    PENDING,
    DISPATCHED,
    FAILED
}
