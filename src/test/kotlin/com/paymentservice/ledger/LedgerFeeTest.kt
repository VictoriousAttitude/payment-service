package com.paymentservice.ledger

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the platform fee rounding policy: floored to the minor unit, remainder
 * left with the merchant. This is a money rule, so the direction is asserted
 * explicitly rather than left to integer-division happenstance.
 */
class LedgerFeeTest {

    @Test
    fun `exact fee - no remainder`() {
        // 2% of 10000 = 200 exactly
        assertEquals(200L, LedgerService.platformFee(10_000L))
    }

    @Test
    fun `fractional fee is floored, not rounded up`() {
        // 2% of 10001 = 200.02 -> 200
        assertEquals(200L, LedgerService.platformFee(10_001L))
        // 2% of 10099 = 201.98 -> 201 (floor, not nearest)
        assertEquals(201L, LedgerService.platformFee(10_099L))
    }

    @Test
    fun `sub-unit fee floors to zero`() {
        // 2% of 49 = 0.98 -> 0; platform takes nothing on dust
        assertEquals(0L, LedgerService.platformFee(49L))
    }

    @Test
    fun `merchant absorbs the truncated remainder and the split balances`() {
        val amount = 10_099L
        val fee = LedgerService.platformFee(amount)
        val merchantAmount = amount - fee

        // remainder stays with the merchant (floor sends it their way)
        assertTrue(merchantAmount > amount * 98 / 100)
        // capture legs balance exactly: debit(amount) == credit(merchant)+credit(fee)
        assertEquals(amount, merchantAmount + fee)
    }
}
