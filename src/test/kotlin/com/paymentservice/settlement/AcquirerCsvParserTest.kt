package com.paymentservice.settlement

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * Pins the strict acquirer CSV contract: exact header, field count, category
 * and currency vocabulary, exponent-aware major-to-minor conversion, signs,
 * and the encoding noise the parser tolerates (BOM, CRLF, blank lines).
 * Pure, mutation tested.
 */
class AcquirerCsvParserTest {

    private val header = "reference,reporting_category,currency,gross,fee"

    @Test
    fun `parses a well-formed file across all reporting categories`() {
        val lines = AcquirerCsvParser.parse(
            header + "\n" +
                "ch1,charge,EUR,100.00,2.00\n" +
                "ch2,payment,EUR,50.00,1.00\n" +
                "ch1:refund,refund,EUR,-30.00,-0.60\n" +
                "ch2:refund,payment_refund,EUR,-5.00,-0.10\n" +
                "ch1:chargeback,dispute,EUR,-100.00,15.00\n" +
                "ch2:chargeback,chargeback,EUR,-50.00,15.00\n"
        )
        assertEquals(6, lines.size)
        assertEquals(
            listOf(
                MovementKind.CAPTURE, MovementKind.CAPTURE,
                MovementKind.REFUND, MovementKind.REFUND,
                MovementKind.CHARGEBACK, MovementKind.CHARGEBACK
            ),
            lines.map { it.kind }
        )
        assertEquals(
            AcquirerSettlementLine("ch1", MovementKind.CAPTURE, 10_000, 200, "EUR"),
            lines.first()
        )
    }

    @Test
    fun `signed refund and chargeback amounts parse to negative minors`() {
        val lines = AcquirerCsvParser.parse(
            "$header\nch1:refund,refund,EUR,-30.00,-0.60\n"
        )
        assertEquals(-3_000, lines.single().grossMinor)
        assertEquals(-60, lines.single().feeMinor)
    }

    @Test
    fun `jpy has exponent zero so major units are the minor units`() {
        val line = AcquirerCsvParser.parse("$header\nj1,charge,JPY,1234,24\n").single()
        assertEquals(1_234, line.grossMinor)
        assertEquals(24, line.feeMinor)
    }

    @Test
    fun `bhd has exponent three so three fraction digits are exact`() {
        val line = AcquirerCsvParser.parse("$header\nb1,charge,BHD,12.345,0.246\n").single()
        assertEquals(12_345, line.grossMinor)
        assertEquals(246, line.feeMinor)
    }

    @Test
    fun `rejects an unknown reporting category with its line number`() {
        val e = assertThrows<SettlementFileParseException> {
            AcquirerCsvParser.parse(
                "$header\nch1,charge,EUR,100.00,2.00\nx1,topup,EUR,1.00,0.00\n"
            )
        }
        assertEquals(3, e.lineNumber)
    }

    @Test
    fun `rejects an unsupported currency as a parse failure not a mismatch`() {
        val e = assertThrows<SettlementFileParseException> {
            AcquirerCsvParser.parse("$header\nch1,charge,XXX,100.00,2.00\n")
        }
        assertEquals(2, e.lineNumber)
    }

    @Test
    fun `rejects more fraction digits than the currency allows`() {
        val e = assertThrows<SettlementFileParseException> {
            AcquirerCsvParser.parse("$header\nch1,charge,EUR,1.234,0.00\n")
        }
        assertEquals(2, e.lineNumber)
    }

    @Test
    fun `rejects a non-numeric amount`() {
        val e = assertThrows<SettlementFileParseException> {
            AcquirerCsvParser.parse("$header\nch1,charge,EUR,abc,0.00\n")
        }
        assertEquals(2, e.lineNumber)
    }

    @Test
    fun `rejects quoted fields`() {
        val e = assertThrows<SettlementFileParseException> {
            AcquirerCsvParser.parse("$header\n\"ch1\",charge,EUR,100.00,2.00\n")
        }
        assertEquals(2, e.lineNumber)
    }

    @Test
    fun `rejects a wrong field count with its line number`() {
        val e = assertThrows<SettlementFileParseException> {
            AcquirerCsvParser.parse("$header\nch1,charge,EUR,100.00\n")
        }
        assertEquals(2, e.lineNumber)
    }

    @Test
    fun `rejects an empty reference`() {
        val e = assertThrows<SettlementFileParseException> {
            AcquirerCsvParser.parse("$header\n,charge,EUR,100.00,2.00\n")
        }
        assertEquals(2, e.lineNumber)
    }

    @Test
    fun `rejects a wrong header on line one`() {
        val e = assertThrows<SettlementFileParseException> {
            AcquirerCsvParser.parse("ref,category,ccy,gross,fee\nch1,charge,EUR,1.00,0.00\n")
        }
        assertEquals(1, e.lineNumber)
    }

    @Test
    fun `strips a utf-8 bom and tolerates crlf line endings`() {
        val lines = AcquirerCsvParser.parse(
            "\uFEFF$header\r\nch1,charge,EUR,100.00,2.00\r\n"
        )
        assertEquals(10_000, lines.single().grossMinor)
    }

    @Test
    fun `skips blank lines and trims field whitespace`() {
        val lines = AcquirerCsvParser.parse(
            "$header\n\nch1, charge , eur , 100.00 , 2.00\n\n"
        )
        val line = lines.single()
        assertEquals("ch1", line.reference)
        assertEquals(MovementKind.CAPTURE, line.kind)
        assertEquals("EUR", line.currency)
        assertEquals(10_000, line.grossMinor)
    }
}
