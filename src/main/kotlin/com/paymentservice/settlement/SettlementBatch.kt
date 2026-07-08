package com.paymentservice.settlement

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
 * Drives the CAPTURED -> SETTLED transition that nothing else triggers, so
 * SETTLED stops being a defined-but-unreachable dead state. Real settlement is
 * a batch run on a delay (T+1/T+2 with the acquirer); this selects captures
 * past the configured delay and settles each in its own transaction.
 */
@Component
class SettlementBatch(
    private val settlementProcessor: SettlementProcessor,
    private val transactionRepository: TransactionRepository,
    private val meterRegistry: MeterRegistry,
    @Value("\${payment.settlement.delay-minutes:1440}") private val delayMinutes: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${payment.settlement.interval-ms:300000}",
        initialDelayString = "\${payment.settlement.initial-delay-ms:30000}"
    )
    @SchedulerLock(name = "settlement")
    fun settleEligible() {
        val cutoff = Instant.now().minus(Duration.ofMinutes(delayMinutes))
        val batch = transactionRepository.findSettlable(cutoff, PageRequest.of(0, BATCH_SIZE))
        for (transaction in batch) {
            try {
                val currency = settlementProcessor.settle(transaction.id) ?: continue
                meterRegistry.counter("payments.settled", "currency", currency).increment()
            } catch (e: Exception) {
                log.error("Settlement failed for txn={}", transaction.id, e)
            }
        }
    }

    companion object {
        const val BATCH_SIZE = 200
    }
}
