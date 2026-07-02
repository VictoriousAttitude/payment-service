package com.paymentservice.payout

import com.paymentservice.ledger.LedgerService
import com.paymentservice.merchant.MerchantNotFoundException
import com.paymentservice.merchant.MerchantRepository
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class PayoutService(
    private val payoutRepository: PayoutRepository,
    private val merchantRepository: MerchantRepository,
    private val ledgerService: LedgerService,
    private val meterRegistry: MeterRegistry
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Disburses the merchant's payable balance. [amount] null pays out the full
     * available balance. Concurrency: the FOR UPDATE lock on the merchant row
     * serializes payouts per merchant, so the balance read and the entry
     * posting are atomic against a concurrent payout - without it, two payouts
     * could both read the same balance and double-spend it. The available
     * balance is floored at 0 for the guard: a settled chargeback can push
     * payable negative, and a negative balance has nothing to disburse.
     */
    @Transactional
    fun createPayout(merchantId: UUID, currency: String, amount: Long? = null): Payout {
        merchantRepository.lockById(merchantId) ?: throw MerchantNotFoundException(merchantId)

        val available = ledgerService.getPayableBalance(merchantId, currency)
        val requested = amount ?: available

        if (requested <= 0 || requested > maxOf(available, 0)) {
            throw InvalidPayoutAmountException(
                "Payout amount $requested invalid; available payable is $available"
            )
        }

        val payout = payoutRepository.save(
            Payout(merchantId = merchantId, amount = requested, currency = currency.uppercase())
        )
        ledgerService.createPayoutEntries(
            payoutId = payout.id,
            merchantId = merchantId,
            amount = requested,
            currency = payout.currency
        )
        log.info("Payout created id={} merchant={} amount={}", payout.id, merchantId, requested)
        meterRegistry.counter("payouts.created", "currency", payout.currency).increment()
        return payout
    }

    /**
     * Marks a disbursement as confirmed by the bank: PENDING -> PAID. A
     * lifecycle milestone only - the money already left payable at creation, so
     * nothing posts. Idempotent for the batch: a payout already terminal
     * returns null; the API path uses fail()/confirmOrThrow semantics via the
     * status machine. Returns the currency on success for metric tagging.
     */
    @Transactional
    fun confirm(payoutId: UUID): String? {
        val payout = findPayout(payoutId)
        if (payout.status != PayoutStatus.PENDING) return null

        payout.transitionTo(PayoutStatus.PAID)
        payoutRepository.save(payout)
        meterRegistry.counter("payouts.paid", "currency", payout.currency).increment()
        return payout.currency
    }

    /**
     * Records a bank rejection: PENDING -> FAILED plus the compensating
     * reversal restoring the payable balance, atomically. A payout already
     * terminal throws (transitionTo) rather than double-posting the reversal.
     */
    @Transactional
    fun fail(payoutId: UUID): Payout {
        val payout = findPayout(payoutId)
        payout.transitionTo(PayoutStatus.FAILED)

        ledgerService.createPayoutReversalEntries(
            payoutId = payout.id,
            merchantId = payout.merchantId,
            amount = payout.amount,
            currency = payout.currency
        )
        val saved = payoutRepository.save(payout)
        log.info("Payout failed id={} merchant={} amount={}", payout.id, payout.merchantId, payout.amount)
        meterRegistry.counter("payouts.failed", "currency", payout.currency).increment()
        return saved
    }

    fun getPayout(payoutId: UUID): Payout = findPayout(payoutId)

    fun getPayoutsForMerchant(merchantId: UUID): List<Payout> =
        payoutRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId)

    private fun findPayout(payoutId: UUID): Payout =
        payoutRepository.findById(payoutId)
            .orElseThrow { PayoutNotFoundException(payoutId) }
}

class PayoutNotFoundException(val payoutId: UUID) :
    RuntimeException("Payout not found: $payoutId")

class InvalidPayoutAmountException(message: String) : RuntimeException(message)
