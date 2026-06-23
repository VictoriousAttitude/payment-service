from datetime import UTC, datetime, timedelta

from hypothesis import given
from hypothesis import strategies as st

from recon.domain.model import (
    DiscrepancyType,
    LedgerLine,
    MovementKind,
    SettlementLine,
)
from recon.domain.money import Money
from recon.domain.reconcile import reconcile

FAR_FUTURE = datetime(9999, 1, 1, tzinfo=UTC)
ZERO_WINDOW = timedelta(0)

currencies = st.sampled_from(["EUR", "USD", "JPY", "BHD"])
kinds = st.sampled_from(list(MovementKind))
timestamps = st.datetimes(
    min_value=datetime(2020, 1, 1),
    max_value=datetime(2099, 1, 1),
    timezones=st.just(UTC),
)


@st.composite
def ledger_lines(draw):
    currency = draw(currencies)
    return LedgerLine(
        reference=draw(st.text(min_size=1, max_size=12)),
        kind=draw(kinds),
        gross=Money(draw(st.integers(-10**9, 10**9)), currency),
        fee=Money(draw(st.integers(0, 10**6)), currency),
        occurred_at=draw(timestamps),
    )


def settlement_from(line: LedgerLine) -> SettlementLine:
    return SettlementLine(line.reference, line.kind, line.gross, line.fee)


@given(st.lists(ledger_lines(), unique_by=lambda line: line.reference))
def test_identical_inputs_reconcile_clean(ledger):
    settlement = [settlement_from(line) for line in ledger]
    report = reconcile(
        ledger, settlement, as_of=FAR_FUTURE, settlement_window=ZERO_WINDOW
    )
    assert report.is_clean
    assert len(report.matched) == len(ledger)
    assert len(report.pending) == 0


@given(
    st.lists(ledger_lines(), unique_by=lambda line: line.reference),
    st.lists(ledger_lines(), unique_by=lambda line: line.reference),
)
def test_partition_is_exhaustive(ledger, other):
    settlement = [settlement_from(line) for line in other]
    report = reconcile(
        ledger, settlement, as_of=FAR_FUTURE, settlement_window=ZERO_WINDOW
    )
    counts = report.counts_by_type()
    union = {line.reference for line in ledger} | {
        line.reference for line in settlement
    }
    # every reference is in exactly one bucket: matched, missing-in-settlement,
    # or missing-in-ledger (no duplicates, nothing pending with zero window)
    total = (
        len(report.matched)
        + counts.get(DiscrepancyType.MISSING_IN_SETTLEMENT, 0)
        + counts.get(DiscrepancyType.MISSING_IN_LEDGER, 0)
    )
    assert total == len(union)
    assert len(report.pending) == 0


@given(st.lists(ledger_lines(), unique_by=lambda line: line.reference))
def test_order_does_not_affect_result(ledger):
    settlement = [settlement_from(line) for line in ledger]
    forward = reconcile(
        ledger, settlement, as_of=FAR_FUTURE, settlement_window=ZERO_WINDOW
    )
    reverse = reconcile(
        list(reversed(ledger)),
        list(reversed(settlement)),
        as_of=FAR_FUTURE,
        settlement_window=ZERO_WINDOW,
    )
    assert forward.is_clean == reverse.is_clean
    assert len(forward.matched) == len(reverse.matched)
