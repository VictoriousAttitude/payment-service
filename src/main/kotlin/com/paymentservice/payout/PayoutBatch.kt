package com.paymentservice.payout

import com.paymentservice.ledger.LedgerService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Auto-payout sweep: disburses every merchant whose payable balance reaches
 * the configured minimum. Each payout goes through the same guarded
 * createPayout path as the API (merchant row lock, amount guard), so a race
 * with a concurrent chargeback or manual payout degrades to a logged 422-class
 * rejection, never a double-spend.
 */
@Component
class PayoutBatch(
    private val payoutService: PayoutService,
    private val ledgerService: LedgerService,
    @Value("\${payment.payout.minimum-amount:1000}") private val minimumAmount: Long
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${payment.payout.interval-ms:300000}",
        initialDelayString = "\${payment.payout.initial-delay-ms:60000}"
    )
    fun payoutEligible() {
        val eligible = ledgerService.payableBalancesAtLeast(minimumAmount)
        for (balance in eligible) {
            try {
                payoutService.createPayout(balance.accountId, balance.currency)
            } catch (e: Exception) {
                log.error(
                    "Auto-payout failed for merchant={} currency={}",
                    balance.accountId, balance.currency, e
                )
            }
        }
    }
}
