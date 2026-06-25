package com.paymentservice.shared

import java.math.BigDecimal
import java.util.Currency

/**
 * ISO 4217 currency registry, backed by [java.util.Currency] (the JDK's built-in
 * ISO 4217 table). The minor-unit count is read from the JDK rather than
 * hardcoded, so it tracks ISO updates shipped with the runtime.
 *
 * Why this matters: amounts are stored in the minor unit (BIGINT), but the
 * number of minor units in a major unit is currency specific. USD and EUR have
 * 2 (cents), JPY has 0 (no sub-unit), KWD and BHD have 3 (fils). Assuming a
 * fixed "times 100" everywhere is a 100x error on JPY and a 10x error on KWD.
 */
object MonetaryCurrency {

    fun isSupported(code: String): Boolean = lookup(code) != null

    /** Minor units per major unit, the ISO 4217 exponent. USD=2, JPY=0, KWD=3. */
    fun minorUnits(code: String): Int =
        (lookup(code) ?: throw IllegalArgumentException("Unsupported currency: $code"))
            .defaultFractionDigits

    /**
     * Resolves a fundable ISO 4217 currency or null. Codes the JDK does not know,
     * the "no currency" code XXX, and non-fund codes such as precious metals
     * (XAU) all report a negative fraction-digit count and are rejected: they are
     * not spendable money in this ledger.
     */
    private fun lookup(code: String): Currency? =
        runCatching { Currency.getInstance(code.uppercase()) }
            .getOrNull()
            ?.takeIf { it.defaultFractionDigits >= 0 }
}

/**
 * A monetary amount as an integer count of the currency's minor unit, paired
 * with its currency. Two invariants the type enforces that raw `Long + String`
 * cannot:
 *  - arithmetic and comparison across currencies are rejected (adding USD to EUR
 *    is a bug, not a number), so a mixed-currency total can never be formed
 *    silently;
 *  - construction goes through the ISO 4217 registry, so an unsupported currency
 *    cannot reach the ledger.
 *
 * The wire format stays integer minor units (the Stripe convention) to avoid
 * floating point; [ofMajor] and [formatMajor] convert at integration boundaries
 * using the currency exponent.
 */
data class Money private constructor(
    val minorUnits: Long,
    val currency: String
) : Comparable<Money> {

    operator fun plus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.addExact(minorUnits, other.minorUnits), currency)
    }

    operator fun minus(other: Money): Money {
        requireSameCurrency(other)
        return Money(Math.subtractExact(minorUnits, other.minorUnits), currency)
    }

    override fun compareTo(other: Money): Int {
        requireSameCurrency(other)
        return minorUnits.compareTo(other.minorUnits)
    }

    val isPositive: Boolean get() = minorUnits > 0
    val isZero: Boolean get() = minorUnits == 0L

    /**
     * Major-unit decimal scaled to the currency exponent:
     * 1000 USD -> "10.00", 100 JPY -> "100", 1234 BHD -> "1.234".
     */
    fun formatMajor(): String {
        val scale = MonetaryCurrency.minorUnits(currency)
        return BigDecimal(minorUnits).movePointLeft(scale).setScale(scale).toPlainString()
    }

    private fun requireSameCurrency(other: Money) {
        require(currency == other.currency) {
            "Cannot operate across currencies: $currency vs ${other.currency}"
        }
    }

    companion object {

        fun ofMinor(minorUnits: Long, currency: String): Money =
            Money(minorUnits, normalize(currency))

        /**
         * Parses a major-unit decimal ("10.00") into minor units using the
         * currency exponent. Rejects more fraction digits than the currency
         * allows: "1.234" is valid for BHD (3) but invalid for USD (2). Failing
         * loudly is the point, silently truncating would drop a sub-unit the
         * caller actually wrote.
         */
        fun ofMajor(major: String, currency: String): Money {
            val code = normalize(currency)
            val scale = MonetaryCurrency.minorUnits(code)
            val parsed = BigDecimal(major)
            require(parsed.scale() <= scale) {
                "Amount $major has more fraction digits than $code allows ($scale)"
            }
            return Money(parsed.movePointRight(scale).longValueExact(), code)
        }

        private fun normalize(currency: String): String {
            val code = currency.uppercase()
            require(MonetaryCurrency.isSupported(code)) { "Unsupported currency: $currency" }
            return code
        }
    }
}
