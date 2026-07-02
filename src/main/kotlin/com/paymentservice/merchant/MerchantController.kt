package com.paymentservice.merchant

import com.paymentservice.ledger.AccountType
import com.paymentservice.ledger.LedgerService
import com.paymentservice.shared.MERCHANT_ID_ATTRIBUTE
import com.paymentservice.shared.PaymentAccessDeniedException
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/merchants")
class MerchantController(
    private val merchantRepository: MerchantRepository,
    private val ledgerService: LedgerService
) {

    @GetMapping("/{id}/balance")
    fun getBalance(
        @RequestAttribute(MERCHANT_ID_ATTRIBUTE) merchantId: UUID,
        @PathVariable id: UUID
    ): BalanceResponse {
        // A merchant may only read its own balance.
        if (id != merchantId) {
            throw PaymentAccessDeniedException(merchantId)
        }

        val merchant = merchantRepository.findById(id)
            .orElseThrow { MerchantNotFoundException(id) }

        // Three pots per currency: MERCHANT = captured but unsettled (pending),
        // MERCHANT_PAYABLE = settled and disbursable (available, may be negative
        // after a settled chargeback), MERCHANT_RESERVE = rolling reserve held.
        val pending = byCurrency(AccountType.MERCHANT, merchant.id)
        val available = byCurrency(AccountType.MERCHANT_PAYABLE, merchant.id)
        val reserve = byCurrency(AccountType.MERCHANT_RESERVE, merchant.id)

        val balances = (pending.keys + available.keys + reserve.keys)
            .sorted()
            .map { currency ->
                BalanceEntry(
                    currency = currency,
                    pending = pending[currency] ?: 0L,
                    available = available[currency] ?: 0L,
                    reserve = reserve[currency] ?: 0L
                )
            }

        return BalanceResponse(
            merchantId = merchant.id,
            merchantName = merchant.name,
            balances = balances
        )
    }

    private fun byCurrency(accountType: AccountType, accountId: UUID): Map<String, Long> =
        ledgerService.getBalancesByCurrency(accountType, accountId)
            .associate { it.currency to it.net }
}

data class BalanceResponse(
    val merchantId: UUID,
    val merchantName: String,
    val balances: List<BalanceEntry>
)

data class BalanceEntry(
    val currency: String,
    val pending: Long,
    val available: Long,
    val reserve: Long
)
