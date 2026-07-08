package com.paymentservice.expiry

import com.paymentservice.payment.TransactionRepository
import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Releases authorizations not captured within the validity window, so a stale
 * AUTHORIZED hold does not linger forever. Card auth holds are time-bounded
 * (commonly ~7 days); this selects authorizations older than the configured
 * window and expires each in its own transaction.
 */
@Component
class AuthorizationExpiryBatch(
    private val expiryProcessor: AuthorizationExpiryProcessor,
    private val transactionRepository: TransactionRepository,
    private val meterRegistry: MeterRegistry,
    @Value("\${payment.authorization.validity-minutes:10080}") private val validityMinutes: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${payment.authorization.interval-ms:300000}",
        initialDelayString = "\${payment.authorization.initial-delay-ms:30000}"
    )
    @SchedulerLock(name = "authorization-expiry")
    fun expireStaleAuthorizations() {
        val cutoff = Instant.now().minus(Duration.ofMinutes(validityMinutes))
        val batch = transactionRepository.findExpirableAuthorizations(cutoff, PageRequest.of(0, BATCH_SIZE))
        for (transaction in batch) {
            try {
                val currency = expiryProcessor.expire(transaction.id) ?: continue
                meterRegistry.counter("payments.expired", "currency", currency).increment()
            } catch (e: Exception) {
                log.error("Authorization expiry failed for txn={}", transaction.id, e)
            }
        }
    }

    companion object {
        const val BATCH_SIZE = 200
    }
}
