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
    var dispatchedAt: Instant? = null,

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = Instant.now()
) {
    fun markDispatched() {
        status = OutboxStatus.DISPATCHED
        dispatchedAt = Instant.now()
        updatedAt = Instant.now()
    }

    /**
     * Records a dispatch failure and pushes the event into the future with
     * exponential backoff, so a failing event stops hot-looping the provider on
     * every tick (next_attempt_at gates re-selection). At maxAttempts it is
     * dead-lettered to FAILED and never re-selected.
     */
    fun recordFailure(error: String, maxAttempts: Int) {
        attempts++
        lastError = error.take(1000)
        updatedAt = Instant.now()
        if (attempts >= maxAttempts) {
            status = OutboxStatus.FAILED
        } else {
            nextAttemptAt = Instant.now().plusSeconds(backoffSeconds())
        }
    }

    private fun backoffSeconds(): Long =
        minOf(BASE_BACKOFF_SECONDS shl (attempts - 1), MAX_BACKOFF_SECONDS)

    companion object {
        const val PROVIDER_AUTHORIZATION = "PROVIDER_AUTHORIZATION"
        private const val BASE_BACKOFF_SECONDS = 30L
        private const val MAX_BACKOFF_SECONDS = 3600L
    }
}

enum class OutboxStatus {
    PENDING,
    DISPATCHED,
    FAILED
}
