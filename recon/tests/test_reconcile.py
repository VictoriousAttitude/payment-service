from datetime import UTC, datetime, timedelta

from recon.domain.model import (
    Discrepancy,
    DiscrepancyType,
    LedgerLine,
    MovementKind,
    SettlementLine,
)
from recon.domain.money import Money
from recon.domain.reconcile import reconcile

AS_OF = datetime(2026, 6, 23, tzinfo=UTC)
WINDOW = timedelta(days=2)
OLD = datetime(2026, 6, 1, tzinfo=UTC)
RECENT = datetime(2026, 6, 23, tzinfo=UTC)


def ledger(ref, kind, gross, fee, currency="EUR", at=OLD):
    return LedgerLine(ref, kind, Money(gross, currency), Money(fee, currency), at)


def settle(ref, kind, gross, fee, currency="EUR"):
    return SettlementLine(ref, kind, Money(gross, currency), Money(fee, currency))


def run(ledger_rows, settle_rows):
    return reconcile(ledger_rows, settle_rows, as_of=AS_OF, settlement_window=WINDOW)


def types(report):
    return {d.type for d in report.discrepancies}


def only(report, dtype) -> Discrepancy:
    """The single discrepancy of the given type (asserts exactly one)."""
    found = [d for d in report.discrepancies if d.type == dtype]
    assert len(found) == 1, f"expected exactly one {dtype}, got {found}"
    return found[0]


def test_clean_match() -> None:
    report = run(
        [ledger("ch1", MovementKind.CAPTURE, 10000, 200)],
        [settle("ch1", MovementKind.CAPTURE, 10000, 200)],
    )
    assert report.is_clean
    assert len(report.matched) == 1
    pair = report.matched[0]
    assert pair.reference == "ch1"
    assert pair.ledger.reference == "ch1"
    assert pair.settlement.reference == "ch1"
    assert pair.ledger.kind == MovementKind.CAPTURE
    assert pair.settlement.gross == Money(10000, "EUR")


def test_fee_mismatch_is_margin_leak() -> None:
    report = run(
        [ledger("ch1", MovementKind.CAPTURE, 10000, 200)],
        [settle("ch1", MovementKind.CAPTURE, 10000, 250)],
    )
    assert len(report.matched) == 1
    d = only(report, DiscrepancyType.FEE_MISMATCH)
    assert d.reference == "ch1"
    assert d.detail == "fee differs (possible margin leak)"
    # the carried values name which side held what - the leak is 2.00 vs 2.50
    assert d.ledger_value == "2.00 EUR"
    assert d.settlement_value == "2.50 EUR"


def test_gross_mismatch() -> None:
    report = run(
        [ledger("ch1", MovementKind.CAPTURE, 8000, 160)],
        [settle("ch1", MovementKind.CAPTURE, 7500, 160)],
    )
    d = only(report, DiscrepancyType.GROSS_MISMATCH)
    assert d.reference == "ch1"
    assert d.detail == "gross amount differs"
    assert d.ledger_value == "80.00 EUR"
    assert d.settlement_value == "75.00 EUR"


def test_kind_mismatch() -> None:
    report = run(
        [ledger("ch1", MovementKind.CAPTURE, 10000, 200)],
        [settle("ch1", MovementKind.REFUND, 10000, 200)],
    )
    d = only(report, DiscrepancyType.KIND_MISMATCH)
    assert d.reference == "ch1"
    assert d.detail == "movement kind differs"
    assert d.ledger_value == "CAPTURE"
    assert d.settlement_value == "REFUND"


def test_currency_mismatch_skips_amount_checks() -> None:
    report = run(
        [ledger("ch1", MovementKind.CAPTURE, 10000, 200, currency="EUR")],
        [settle("ch1", MovementKind.CAPTURE, 9999, 999, currency="USD")],
    )
    d = only(report, DiscrepancyType.CURRENCY_MISMATCH)
    assert d.reference == "ch1"
    assert d.detail == "currency differs"
    assert d.ledger_value == "EUR"
    assert d.settlement_value == "USD"
    # cross-currency amounts are not comparable, so no amount discrepancies fire
    assert DiscrepancyType.GROSS_MISMATCH not in types(report)
    assert DiscrepancyType.FEE_MISMATCH not in types(report)


def test_old_ledger_only_is_missing_in_settlement() -> None:
    report = run([ledger("ch1", MovementKind.CAPTURE, 4000, 80, at=OLD)], [])
    d = only(report, DiscrepancyType.MISSING_IN_SETTLEMENT)
    assert d.reference == "ch1"
    assert d.detail == "booked in the ledger but absent from the settlement file"
    assert d.ledger_value == "40.00 EUR"
    assert d.settlement_value is None


def test_recent_ledger_only_is_pending_not_missing() -> None:
    report = run([ledger("ch1", MovementKind.CAPTURE, 6000, 120, at=RECENT)], [])
    assert report.is_clean
    assert [p.reference for p in report.pending] == ["ch1"]


def test_settlement_window_boundary_is_inclusive() -> None:
    # a ledger line exactly on the cutoff (as_of - window) is NOT pending:
    # `occurred_at > cutoff` is strict, so the boundary counts as overdue
    cutoff = AS_OF - WINDOW
    report = run([ledger("ch1", MovementKind.CAPTURE, 4000, 80, at=cutoff)], [])
    assert only(report, DiscrepancyType.MISSING_IN_SETTLEMENT).reference == "ch1"
    assert len(report.pending) == 0


def test_settlement_only_is_missing_in_ledger() -> None:
    report = run([], [settle("ch1", MovementKind.CAPTURE, 50000, 1000)])
    d = only(report, DiscrepancyType.MISSING_IN_LEDGER)
    assert d.reference == "ch1"
    assert d.detail == "settled by the processor but never booked in the ledger"
    assert d.settlement_value == "500.00 EUR"
    assert d.ledger_value is None


def test_duplicate_ledger_reference_is_flagged() -> None:
    report = run(
        [
            ledger("ch1", MovementKind.CAPTURE, 10000, 200),
            ledger("ch1", MovementKind.CAPTURE, 10000, 200),
        ],
        [settle("ch1", MovementKind.CAPTURE, 10000, 200)],
    )
    d = only(report, DiscrepancyType.DUPLICATE_REFERENCE)
    assert d.reference == "ch1"
    assert d.detail == "reference appears more than once in the ledger export"
    # the ambiguous reference is excluded from matching, never silently deduped
    assert len(report.matched) == 0


def test_triple_duplicate_ledger_reference_is_flagged_once() -> None:
    # three occurrences exercise the "already a known duplicate" branch
    # (k in duplicates), which must stay excluded from the index
    report = run(
        [ledger("ch1", MovementKind.CAPTURE, 10000, 200)] * 3,
        [settle("ch1", MovementKind.CAPTURE, 10000, 200)],
    )
    d = only(report, DiscrepancyType.DUPLICATE_REFERENCE)
    assert d.reference == "ch1"
    assert len(report.matched) == 0


def test_duplicate_settlement_reference_is_flagged() -> None:
    report = run(
        [ledger("ch1", MovementKind.CAPTURE, 10000, 200)],
        [
            settle("ch1", MovementKind.CAPTURE, 10000, 200),
            settle("ch1", MovementKind.CAPTURE, 10000, 200),
        ],
    )
    d = only(report, DiscrepancyType.DUPLICATE_REFERENCE)
    assert d.reference == "ch1"
    assert d.detail == "reference appears more than once in the settlement file"
    assert len(report.matched) == 0
