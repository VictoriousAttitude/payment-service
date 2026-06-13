package com.paymentservice.merchant

import com.paymentservice.auth.ApiKeyAuthFilter
import com.paymentservice.ledger.LedgerService
import com.paymentservice.payment.PaymentAccessDeniedException
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
        @RequestAttribute(ApiKeyAuthFilter.MERCHANT_ID_ATTRIBUTE) merchantId: UUID,
        @PathVariable id: UUID
    ): BalanceResponse {
        // A merchant may only read its own balance.
        if (id != merchantId) {
            throw PaymentAccessDeniedException(merchantId)
        }

        val merchant = merchantRepository.findById(id)
            .orElseThrow { com.paymentservice.payment.MerchantNotFoundException(id) }

        val balances = ledgerService.getMerchantBalances(merchant.id)
            .map { CurrencyAmount(currency = it.currency, amount = it.net) }

        return BalanceResponse(
            merchantId = merchant.id,
            merchantName = merchant.name,
            balances = balances
        )
    }
}

data class BalanceResponse(
    val merchantId: UUID,
    val merchantName: String,
    val balances: List<CurrencyAmount>
)

data class CurrencyAmount(
    val currency: String,
    val amount: Long
)
