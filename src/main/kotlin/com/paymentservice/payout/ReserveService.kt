package com.paymentservice.payout

import com.paymentservice.ledger.LedgerService
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Rolling reserve policy: a slice of every settled net is withheld against
 * chargeback exposure and released after a hold period. Industry standard is
 * 5-10% for 90-180 days; defaults here are 1000 bps / 90 days, config-driven.
 */
@Service
class ReserveService(
    private val reserveHoldRepository: ReserveHoldRepository,
    private val ledgerService: LedgerService,
    private val meterRegistry: MeterRegistry,
    @Value("\${payment.reserve.rate-bps:1000}") private val rateBps: Long,
    @Value("\${payment.reserve.hold-days:90}") private val holdDays: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Reserve slice of a settled net, floored - same rounding policy as
     * platformFee: the fractional minor unit stays with the merchant (lands in
     * payable via net - reserve), so the split always balances exactly.
     */
    fun reserveAmount(net: Long): Long = Math.floorDiv(net * rateBps, 10_000)

    /**
     * Records the hold row for a reserve slice already posted by the settlement
     * split. MANDATORY: the hold must be atomic with the split entries and the
     * SETTLED transition.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun createHold(transactionId: UUID, merchantId: UUID, amount: Long, currency: String): ReserveHold {
        val hold = reserveHoldRepository.save(
            ReserveHold(
                transactionId = transactionId,
                merchantId = merchantId,
                amount = amount,
                currency = currency,
                releaseAt = Instant.now().plus(Duration.ofDays(holdDays))
            )
        )
        meterRegistry.counter("reserve.held", "currency", currency).increment()
        return hold
    }

    /**
     * Releases one matured hold: posts RESERVE -> PAYABLE and flips the row to
     * RELEASED, atomically. Idempotent: a hold already released (by a prior run
     * or a concurrent worker, rejected via @Version) returns null. Returns the
     * currency on success for metric tagging.
     */
    @Transactional
    fun release(holdId: UUID): String? {
        val hold = reserveHoldRepository.findById(holdId).orElse(null) ?: return null
        if (hold.status != ReserveHoldStatus.HELD) return null

        hold.markReleased()
        reserveHoldRepository.save(hold)
        ledgerService.createReserveReleaseEntries(
            holdId = hold.id,
            merchantId = hold.merchantId,
            amount = hold.amount,
            currency = hold.currency
        )
        log.info("Reserve released hold={} merchant={} amount={}", hold.id, hold.merchantId, hold.amount)
        meterRegistry.counter("reserve.released", "currency", hold.currency).increment()
        return hold.currency
    }
}
