from datetime import UTC, datetime, timedelta

from recon.domain.model import (
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


def test_clean_match() -> None:
    report = run(
        [ledger("ch1", MovementKind.CAPTURE, 10000, 200)],
        [settle("ch1", MovementKind.CAPTURE, 10000, 200)],
    )
    assert report.is_clean
    assert len(report.matched) == 1


def test_fee_mismatch_is_margin_leak() -> None:
    report = run(
        [ledger("ch1", MovementKind.CAPTURE, 10000, 200)],
        [settle("ch1", MovementKind.CAPTURE, 10000, 250)],
    )
    assert DiscrepancyType.FEE_MISMATCH in types(report)
    assert len(report.matched) == 1


def test_gross_mismatch() -> None:
    report = run(
        [ledger("ch1", MovementKind.CAPTURE, 8000, 160)],
        [settle("ch1", MovementKind.CAPTURE, 7500, 160)],
    )
    assert DiscrepancyType.GROSS_MISMATCH in types(report)


def test_kind_mismatch() -> None:
    report = run(
        [ledger("ch1", MovementKind.CAPTURE, 10000, 200)],
        [settle("ch1", MovementKind.REFUND, 10000, 200)],
    )
    assert DiscrepancyType.KIND_MISMATCH in types(report)


def test_currency_mismatch_skips_amount_checks() -> None:
    report = run(
        [ledger("ch1", MovementKind.CAPTURE, 10000, 200, currency="EUR")],
        [settle("ch1", MovementKind.CAPTURE, 9999, 999, currency="USD")],
    )
    assert DiscrepancyType.CURRENCY_MISMATCH in types(report)
    assert DiscrepancyType.GROSS_MISMATCH not in types(report)
    assert DiscrepancyType.FEE_MISMATCH not in types(report)


def test_old_ledger_only_is_missing_in_settlement() -> None:
    report = run([ledger("ch1", MovementKind.CAPTURE, 4000, 80, at=OLD)], [])
    assert DiscrepancyType.MISSING_IN_SETTLEMENT in types(report)


def test_recent_ledger_only_is_pending_not_missing() -> None:
    report = run([ledger("ch1", MovementKind.CAPTURE, 6000, 120, at=RECENT)], [])
    assert report.is_clean
    assert len(report.pending) == 1


def test_settlement_only_is_missing_in_ledger() -> None:
    report = run([], [settle("ch1", MovementKind.CAPTURE, 50000, 1000)])
    assert DiscrepancyType.MISSING_IN_LEDGER in types(report)


def test_duplicate_reference_is_flagged() -> None:
    report = run(
        [
            ledger("ch1", MovementKind.CAPTURE, 10000, 200),
            ledger("ch1", MovementKind.CAPTURE, 10000, 200),
        ],
        [settle("ch1", MovementKind.CAPTURE, 10000, 200)],
    )
    assert DiscrepancyType.DUPLICATE_REFERENCE in types(report)
    # the ambiguous reference is excluded from matching
    assert len(report.matched) == 0
