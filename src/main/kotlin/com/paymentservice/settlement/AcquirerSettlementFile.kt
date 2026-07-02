package com.paymentservice.settlement

import com.paymentservice.shared.Money

/**
 * A settlement file line failed to parse. Carries the 1-based line number so
 * the operator can locate the offending row without diffing the file.
 */
class SettlementFileParseException(
    val lineNumber: Int,
    reason: String,
    cause: Throwable? = null
) : RuntimeException("line $lineNumber: $reason", cause)

/**
 * One parsed acquirer settlement line. Amounts are integer minor units, signed
 * by direction (mirrors [MovementLine]): capture gross positive, refund and
 * chargeback gross negative, refund fee negative. The major-to-minor conversion
 * happened at the parse boundary, so all downstream comparison is exact Long
 * equality.
 */
data class AcquirerSettlementLine(
    val reference: String,
    val kind: MovementKind,
    val grossMinor: Long,
    val feeMinor: Long,
    val currency: String
)

/**
 * Strict parser for the acquirer settlement CSV, the same wire contract the
 * Python oracle's `csv_settlement` adapter reads: header
 * `reference,reporting_category,currency,gross,fee`, Stripe-style reporting
 * categories, signed major-unit decimal amounts.
 *
 * Hand-rolled on purpose: the format is fully controlled (references are
 * UUID-derived with `:refund`/`:chargeback` suffixes, categories are an enum,
 * amounts plain decimals), so there are no quotes or embedded commas to handle
 * and a CSV library would add a dependency for nothing. A real free-text
 * acquirer feed (RFC 4180 quoting, embedded delimiters) would need
 * apache-commons-csv instead.
 *
 * Strict-reject over best-effort: any quote character, wrong field count,
 * unknown category, unsupported currency or excess fraction precision fails
 * the whole file with its line number. A settlement file is a financial
 * statement; guessing at a malformed row hides exactly the corruption this
 * pipeline exists to catch. Note the boundary: an *unsupported* currency code
 * is a parse failure, while a supported-but-different one parses fine and
 * surfaces later as a CURRENCY_MISMATCH discrepancy.
 *
 * Tolerated encoding noise: a UTF-8 BOM and CRLF line endings (both common in
 * files exported from Windows tooling), plus blank lines.
 */
object AcquirerCsvParser {

    private const val EXPECTED_HEADER = "reference,reporting_category,currency,gross,fee"
    private const val FIELD_COUNT = 5
    private const val BOM = "\uFEFF"

    /** Same category vocabulary as the Python `csv_settlement` adapter. */
    private val CATEGORY_TO_KIND = mapOf(
        "charge" to MovementKind.CAPTURE,
        "payment" to MovementKind.CAPTURE,
        "refund" to MovementKind.REFUND,
        "payment_refund" to MovementKind.REFUND,
        "dispute" to MovementKind.CHARGEBACK,
        "chargeback" to MovementKind.CHARGEBACK
    )

    fun parse(content: String): List<AcquirerSettlementLine> {
        val lines = content.removePrefix(BOM).split("\n").map { it.removeSuffix("\r") }
        if (lines.first() != EXPECTED_HEADER) {
            fail(1, "expected header '$EXPECTED_HEADER'")
        }
        return lines.withIndex()
            .drop(1)
            .filter { it.value.isNotEmpty() }
            .map { (index, raw) -> parseLine(index + 1, raw) }
    }

    private fun parseLine(lineNumber: Int, raw: String): AcquirerSettlementLine {
        if (raw.contains('"')) fail(lineNumber, "quoted fields are not supported")
        val fields = raw.split(',')
        if (fields.size != FIELD_COUNT) {
            fail(lineNumber, "expected $FIELD_COUNT fields, got ${fields.size}")
        }
        val cells = fields.map { it.trim() }
        val reference = cells.component1()
        val category = cells.component2()
        val currency = cells.component3()
        if (reference.isEmpty()) fail(lineNumber, "empty reference")
        val kind = CATEGORY_TO_KIND[category.lowercase()]
            ?: fail(lineNumber, "unknown reporting_category '$category'")
        return AcquirerSettlementLine(
            reference = reference,
            kind = kind,
            grossMinor = parseAmount(lineNumber, "gross", cells.component4(), currency),
            feeMinor = parseAmount(lineNumber, "fee", cells.component5(), currency),
            currency = currency.uppercase()
        )
    }

    /**
     * The sole major-to-minor boundary on the JVM side: [Money.ofMajor] is
     * currency-exponent aware (JPY has 0 fraction digits, BHD 3), rejects
     * excess precision and unsupported codes, and `longValueExact` inside it
     * rejects overflow - the exact mirror of Python's `Money.from_decimal`.
     */
    private fun parseAmount(lineNumber: Int, field: String, raw: String, currency: String): Long =
        try {
            Money.ofMajor(raw, currency).minorUnits
        } catch (e: IllegalArgumentException) {
            throw SettlementFileParseException(lineNumber, "$field: ${e.message}", e)
        } catch (e: ArithmeticException) {
            throw SettlementFileParseException(lineNumber, "$field: ${e.message}", e)
        }

    private fun fail(lineNumber: Int, reason: String): Nothing =
        throw SettlementFileParseException(lineNumber, reason)
}
