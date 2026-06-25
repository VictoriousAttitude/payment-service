package com.paymentservice.shared

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the money model: the ISO 4217 exponent table, exponent-aware parsing and
 * formatting, and the cross-currency guards. Pure logic, mutation tested.
 */
class MoneyTest {

    @Test
    fun `registry exposes ISO 4217 minor units per currency`() {
        assertEquals(2, MonetaryCurrency.minorUnits("USD"))
        assertEquals(2, MonetaryCurrency.minorUnits("EUR"))
        assertEquals(0, MonetaryCurrency.minorUnits("JPY")) // no sub-unit
        assertEquals(3, MonetaryCurrency.minorUnits("KWD")) // fils
        assertEquals(3, MonetaryCurrency.minorUnits("BHD"))
    }

    @Test
    fun `registry rejects unknown, non-fund and metal codes`() {
        assertTrue(MonetaryCurrency.isSupported("USD"))
        assertTrue(MonetaryCurrency.isSupported("usd")) // case-insensitive
        assertFalse(MonetaryCurrency.isSupported("ZZZ")) // not a code
        assertFalse(MonetaryCurrency.isSupported("XXX")) // ISO "no currency"
        assertFalse(MonetaryCurrency.isSupported("XAU")) // gold, not spendable
        assertFalse(MonetaryCurrency.isSupported("US"))  // too short
    }

    @Test
    fun `ofMinor normalizes the currency code and rejects unsupported`() {
        assertEquals("USD", Money.ofMinor(1000, "usd").currency)
        assertFailsWith<IllegalArgumentException> { Money.ofMinor(1000, "ZZZ") }
    }

    @Test
    fun `ofMajor parses using the currency exponent`() {
        assertEquals(1000, Money.ofMajor("10.00", "USD").minorUnits)
        assertEquals(1000, Money.ofMajor("10", "USD").minorUnits) // implicit scale
        assertEquals(100, Money.ofMajor("100", "JPY").minorUnits)  // zero-decimal
        assertEquals(1234, Money.ofMajor("1.234", "BHD").minorUnits) // three-decimal
    }

    @Test
    fun `ofMajor rejects more fraction digits than the currency allows`() {
        // exactly at the exponent is fine, one past it is rejected (boundary)
        assertEquals(1099, Money.ofMajor("10.99", "USD").minorUnits)
        assertFailsWith<IllegalArgumentException> { Money.ofMajor("10.999", "USD") }
        assertFailsWith<IllegalArgumentException> { Money.ofMajor("100.5", "JPY") }
    }

    @Test
    fun `formatMajor renders to the currency exponent`() {
        assertEquals("10.00", Money.ofMinor(1000, "USD").formatMajor())
        assertEquals("100", Money.ofMinor(100, "JPY").formatMajor())
        assertEquals("1.234", Money.ofMinor(1234, "BHD").formatMajor())
        assertEquals("-5.00", Money.ofMinor(-500, "USD").formatMajor())
    }

    @Test
    fun `plus and minus stay within a currency`() {
        val ten = Money.ofMinor(1000, "USD")
        val three = Money.ofMinor(300, "USD")
        assertEquals(Money.ofMinor(1300, "USD"), ten + three)
        assertEquals(Money.ofMinor(700, "USD"), ten - three)
    }

    @Test
    fun `arithmetic across currencies is rejected`() {
        val usd = Money.ofMinor(1000, "USD")
        val eur = Money.ofMinor(1000, "EUR")
        assertFailsWith<IllegalArgumentException> { usd + eur }
        assertFailsWith<IllegalArgumentException> { usd - eur }
        assertFailsWith<IllegalArgumentException> { usd.compareTo(eur) }
    }

    @Test
    fun `comparison orders amounts within a currency`() {
        assertTrue(Money.ofMinor(1000, "USD") > Money.ofMinor(999, "USD"))
        assertTrue(Money.ofMinor(0, "USD").isZero)
        assertFalse(Money.ofMinor(1, "USD").isZero)
        assertTrue(Money.ofMinor(1, "USD").isPositive)
        assertFalse(Money.ofMinor(0, "USD").isPositive)
    }

    @Test
    fun `addition overflow fails loudly rather than wrapping`() {
        val max = Money.ofMinor(Long.MAX_VALUE, "USD")
        assertFailsWith<ArithmeticException> { max + Money.ofMinor(1, "USD") }
    }
}
