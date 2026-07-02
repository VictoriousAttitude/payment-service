package com.paymentservice.payout

import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Drives the release of matured reserve holds. Separate bean from
 * ReserveService so each release runs in its own transaction (the
 * @Transactional proxy only applies across bean boundaries): one poisoned hold
 * cannot roll back the whole sweep.
 */
@Component
class ReserveReleaseBatch(
    private val reserveService: ReserveService,
    private val reserveHoldRepository: ReserveHoldRepository
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${payment.reserve.release-interval-ms:300000}",
        initialDelayString = "\${payment.reserve.release-initial-delay-ms:30000}"
    )
    fun releaseMatured() {
        val batch = reserveHoldRepository.findByStatusAndReleaseAtLessThanEqual(
            ReserveHoldStatus.HELD, Instant.now(), PageRequest.of(0, BATCH_SIZE)
        )
        for (hold in batch) {
            try {
                reserveService.release(hold.id)
            } catch (e: Exception) {
                log.error("Reserve release failed for hold={}", hold.id, e)
            }
        }
    }

    companion object {
        const val BATCH_SIZE = 200
    }
}
