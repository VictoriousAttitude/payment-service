package com.paymentservice.payout

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Confirms disbursements the bank has had long enough to reject: a PENDING
 * payout older than the confirm delay is marked PAID (T+N milestone, no
 * ledger). A real integration would consume the bank's status file/webhook;
 * failures are driven through PayoutService.fail() by that channel.
 */
@Component
class PayoutConfirmBatch(
    private val payoutService: PayoutService,
    private val payoutRepository: PayoutRepository,
    @Value("\${payment.payout.confirm-delay-minutes:60}") private val confirmDelayMinutes: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${payment.payout.confirm-interval-ms:300000}",
        initialDelayString = "\${payment.payout.confirm-initial-delay-ms:60000}"
    )
    fun confirmSettled() {
        val cutoff = Instant.now().minus(Duration.ofMinutes(confirmDelayMinutes))
        val batch = payoutRepository.findConfirmable(cutoff, PageRequest.of(0, BATCH_SIZE))
        for (payout in batch) {
            try {
                payoutService.confirm(payout.id)
            } catch (e: Exception) {
                log.error("Payout confirm failed for payout={}", payout.id, e)
            }
        }
    }

    companion object {
        const val BATCH_SIZE = 200
    }
}
