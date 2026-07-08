"""Cross-language wire-contract tests.

The settlement CSV vocabulary lives in three hand-maintained maps: the JVM
`AcquirerCsvParser`, this package's `csv_settlement` parser, and the procsim
`generate` producer. Nothing but golden bytes keeps them in sync. These tests
pin the Python side against fixtures under the repo-root `contract/` directory;
`SettlementContractTest` on the JVM side reads the exact same files. A category
or exponent that diverges on either side fails one of the two suites.
"""

from __future__ import annotations

from datetime import UTC, datetime
from pathlib import Path

from recon.adapters.csv_settlement import parse_settlement_csv
from recon.domain.model import LedgerLine, MovementKind
from recon.domain.money import Money
from recon.procsim.generate import render_csv, to_settlement_rows

CONTRACT_DIR = Path(__file__).resolve().parents[2] / "contract"
CATEGORIES_CSV = CONTRACT_DIR / "settlement_categories.csv"
PROCSIM_CSV = CONTRACT_DIR / "procsim_settlement.csv"

# The full acquirer category vocabulary the parser must accept, with the exact
# minor-unit result of the exponent-aware conversion. Both language parsers are
# pinned to this table against identical bytes.
EXPECTED_CATEGORIES: dict[str, tuple[MovementKind, Money, Money]] = {
    "cap-charge": (MovementKind.CAPTURE, Money(10000, "EUR"), Money(200, "EUR")),
    "cap-payment": (MovementKind.CAPTURE, Money(5000, "USD"), Money(100, "USD")),
    "ref-refund": (MovementKind.REFUND, Money(-3000, "EUR"), Money(-60, "EUR")),
    "ref-payment_refund": (MovementKind.REFUND, Money(-500, "USD"), Money(-10, "USD")),
    "cb-dispute": (MovementKind.CHARGEBACK, Money(-10000, "EUR"), Money(1500, "EUR")),
    "cb-chargeback": (MovementKind.CHARGEBACK, Money(-5000, "USD"), Money(1500, "USD")),
    "jpy-charge": (MovementKind.CAPTURE, Money(1234, "JPY"), Money(24, "JPY")),
    "bhd-charge": (MovementKind.CAPTURE, Money(12345, "BHD"), Money(246, "BHD")),
}


def test_categories_fixture_parses_to_expected_minor_units() -> None:
    rows = {r.reference: r for r in parse_settlement_csv(CATEGORIES_CSV)}
    assert rows.keys() == EXPECTED_CATEGORIES.keys()
    for reference, (kind, gross, fee) in EXPECTED_CATEGORIES.items():
        line = rows[reference]
        assert line.kind == kind, reference
        assert line.gross == gross, reference
        assert line.fee == fee, reference


def test_procsim_output_matches_committed_golden_bytes() -> None:
    """procsim is the acquirer; its rendered CSV must equal the committed
    fixture byte-for-byte, so the JVM parser test consumes procsim's real
    output rather than a hand-authored lookalike."""
    at = datetime(2026, 7, 1, 9, 0, tzinfo=UTC)

    def line(ref: str, k: MovementKind, g: int, f: int, c: str) -> LedgerLine:
        return LedgerLine(ref, k, Money(g, c), Money(f, c), at)

    ledger = [
        line("txn-1", MovementKind.CAPTURE, 10000, 200, "EUR"),
        line("txn-1:refund", MovementKind.REFUND, -3000, -60, "EUR"),
        line("txn-2", MovementKind.CAPTURE, 1234, 24, "JPY"),
        line("txn-3:chargeback", MovementKind.CHARGEBACK, -10000, 1500, "EUR"),
    ]
    rendered = render_csv(to_settlement_rows(ledger))
    assert rendered == PROCSIM_CSV.read_text(encoding="utf-8")


def test_procsim_golden_reparses_consistently() -> None:
    rows = {r.reference: r for r in parse_settlement_csv(PROCSIM_CSV)}
    assert rows["txn-1"].kind == MovementKind.CAPTURE
    assert rows["txn-1"].gross == Money(10000, "EUR")
    assert rows["txn-1:refund"].kind == MovementKind.REFUND
    assert rows["txn-2"].gross == Money(1234, "JPY")
    assert rows["txn-3:chargeback"].kind == MovementKind.CHARGEBACK
    assert rows["txn-3:chargeback"].gross == Money(-10000, "EUR")
