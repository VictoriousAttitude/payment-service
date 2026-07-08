package com.paymentservice.settlement

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Cross-language wire-contract test. The acquirer settlement category
 * vocabulary and the exponent-aware amount conversion live in three
 * hand-maintained places: this [AcquirerCsvParser], the Python `csv_settlement`
 * parser and the Python `procsim` producer. Only golden bytes keep them in
 * sync. This suite pins the JVM side against fixtures under the repo-root
 * `contract/` directory; `recon/tests/test_contract.py` reads the exact same
 * files. A category or exponent that diverges on either side breaks one suite.
 *
 * `procsim_settlement.csv` is emitted by the Python producer (asserted
 * byte-for-byte there), so parsing it here feeds procsim's real output through
 * the JVM parser rather than a lookalike.
 */
class SettlementContractTest {

    private val contractDir: Path =
        Path.of(System.getProperty("user.dir")).resolve("contract")

    private fun read(name: String): String =
        Files.readString(contractDir.resolve(name))

    @Test
    fun `categories fixture parses to expected minor units across the full vocabulary`() {
        val lines = AcquirerCsvParser.parse(read("settlement_categories.csv"))
        assertEquals(
            listOf(
                AcquirerSettlementLine("cap-charge", MovementKind.CAPTURE, 10_000, 200, "EUR"),
                AcquirerSettlementLine("cap-payment", MovementKind.CAPTURE, 5_000, 100, "USD"),
                AcquirerSettlementLine("ref-refund", MovementKind.REFUND, -3_000, -60, "EUR"),
                AcquirerSettlementLine("ref-payment_refund", MovementKind.REFUND, -500, -10, "USD"),
                AcquirerSettlementLine("cb-dispute", MovementKind.CHARGEBACK, -10_000, 1_500, "EUR"),
                AcquirerSettlementLine("cb-chargeback", MovementKind.CHARGEBACK, -5_000, 1_500, "USD"),
                AcquirerSettlementLine("jpy-charge", MovementKind.CAPTURE, 1_234, 24, "JPY"),
                AcquirerSettlementLine("bhd-charge", MovementKind.CAPTURE, 12_345, 246, "BHD")
            ),
            lines
        )
    }

    @Test
    fun `parses procsim generated output byte contract`() {
        val lines = AcquirerCsvParser.parse(read("procsim_settlement.csv"))
        assertEquals(
            listOf(
                AcquirerSettlementLine("txn-1", MovementKind.CAPTURE, 10_000, 200, "EUR"),
                AcquirerSettlementLine("txn-1:refund", MovementKind.REFUND, -3_000, -60, "EUR"),
                AcquirerSettlementLine("txn-2", MovementKind.CAPTURE, 1_234, 24, "JPY"),
                AcquirerSettlementLine("txn-3:chargeback", MovementKind.CHARGEBACK, -10_000, 1_500, "EUR")
            ),
            lines
        )
    }
}
