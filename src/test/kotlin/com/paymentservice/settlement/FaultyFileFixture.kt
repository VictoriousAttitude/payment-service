package com.paymentservice.settlement

import com.paymentservice.shared.Money

/**
 * Kotlin mirror of the Python procsim fault injectors: renders a live-ledger
 * [MovementLine] extract as the acquirer CSV wire format, optionally with one
 * seeded fault, so ingestion can be proven to classify every discrepancy
 * class. Amounts cross the minor->major boundary through [Money.formatMajor],
 * the exact inverse of the parser's [Money.ofMajor], and stay signed (refund
 * and chargeback gross negative, refund fee negative).
 */
object FaultyFileFixture {

    private const val HEADER = "reference,reporting_category,currency,gross,fee"

    private val KIND_TO_CATEGORY = mapOf(
        MovementKind.CAPTURE to "charge",
        MovementKind.REFUND to "refund",
        MovementKind.CHARGEBACK to "dispute"
    )

    /** Same-exponent swap (procsim rule): only the currency check may fire. */
    private val CURRENCY_SWAP = mapOf("EUR" to "USD", "USD" to "EUR")

    fun clean(lines: List<MovementLine>): String = render(lines.map(::row))

    fun withDroppedLine(lines: List<MovementLine>, reference: String): String =
        render(lines.filterNot { it.reference == reference }.map(::row))

    fun withPhantomLine(lines: List<MovementLine>, phantomReference: String): String =
        render(lines.map(::row) + "$phantomReference,charge,EUR,1.00,0.00")

    fun withDuplicatedLine(lines: List<MovementLine>, reference: String): String =
        render(lines.map(::row) + row(lines.first { it.reference == reference }))

    fun withWrongKind(lines: List<MovementLine>, reference: String): String =
        mutate(lines, reference) { it.copy(kind = nextKind(it.kind)) }

    fun withWrongCurrency(lines: List<MovementLine>, reference: String): String =
        mutate(lines, reference) { it.copy(currency = CURRENCY_SWAP.getValue(it.currency)) }

    /** Perturbs by exactly one minor unit, the smallest representable leak. */
    fun withWrongGross(lines: List<MovementLine>, reference: String): String =
        mutate(lines, reference) { it.copy(grossMinor = it.grossMinor + 1) }

    fun withWrongFee(lines: List<MovementLine>, reference: String): String =
        mutate(lines, reference) { it.copy(feeMinor = it.feeMinor + 1) }

    private fun mutate(
        lines: List<MovementLine>,
        reference: String,
        change: (MovementLine) -> MovementLine
    ): String = render(lines.map { if (it.reference == reference) change(it) else it }.map(::row))

    private fun nextKind(kind: MovementKind): MovementKind = when (kind) {
        MovementKind.CAPTURE -> MovementKind.REFUND
        MovementKind.REFUND -> MovementKind.CHARGEBACK
        MovementKind.CHARGEBACK -> MovementKind.CAPTURE
    }

    private fun row(line: MovementLine): String = listOf(
        line.reference,
        KIND_TO_CATEGORY.getValue(line.kind),
        line.currency,
        Money.ofMinor(line.grossMinor, line.currency).formatMajor(),
        Money.ofMinor(line.feeMinor, line.currency).formatMajor()
    ).joinToString(",")

    private fun render(rows: List<String>): String =
        "$HEADER\n" + rows.joinToString("\n") + "\n"
}
