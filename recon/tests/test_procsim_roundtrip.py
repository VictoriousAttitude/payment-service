"""Round-trip property proof for procsim.

procsim is a test-data generator, so its correctness gate is not mutation
testing but this loop: ledger -> settlement CSV -> parse -> reconcile. A clean
generation must reconcile clean, and every injected fault must produce exactly
the discrepancy its manifest entry promises.
"""

import random
import tempfile
from datetime import UTC, datetime, timedelta
from pathlib import Path

from hypothesis import given, settings
from hypothesis import strategies as st

from recon.adapters.csv_settlement import parse_settlement_csv
from recon.domain.model import LedgerLine, MovementKind
from recon.domain.money import Money
from recon.domain.reconcile import reconcile
from recon.procsim.faults import FaultType, inject
from recon.procsim.generate import render_csv, to_settlement_rows

WINDOW = timedelta(days=2)
BASE = datetime(2026, 6, 1, tzinfo=UTC)

currencies = st.sampled_from(["EUR", "USD", "JPY", "BHD"])
kinds = st.sampled_from(list(MovementKind))


@st.composite
def ledger_lines(draw, min_size=1):
    size = draw(st.integers(min_value=min_size, max_value=12))
    lines = []
    for i in range(size):
        currency = draw(currencies)
        lines.append(
            LedgerLine(
                reference=f"txn-{i}",
                kind=draw(kinds),
                gross=Money(draw(st.integers(-(10**7), 10**7)), currency),
                fee=Money(draw(st.integers(-(10**5), 10**5)), currency),
                occurred_at=BASE + timedelta(minutes=draw(st.integers(0, 10_000))),
            )
        )
    return lines


def _reconcile_rendered(ledger, rows):
    """Full wire round trip: render CSV, reparse, reconcile past the window."""
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "settlement.csv"
        path.write_text(render_csv(rows), encoding="utf-8")
        settlement = parse_settlement_csv(path)
    as_of = max(line.occurred_at for line in ledger) + WINDOW
    return reconcile(ledger, settlement, as_of=as_of, settlement_window=WINDOW)


@settings(max_examples=50, deadline=None)
@given(ledger_lines())
def test_unfaulted_generation_reconciles_clean(ledger):
    report = _reconcile_rendered(ledger, to_settlement_rows(ledger))
    assert report.is_clean
    assert len(report.matched) == len(ledger)
    assert len(report.pending) == 0


@settings(max_examples=100, deadline=None)
@given(ledger_lines(), st.sampled_from(list(FaultType)), st.integers(0, 2**16))
def test_single_fault_yields_exactly_its_discrepancy(ledger, fault, seed):
    rows = to_settlement_rows(ledger)
    mutated, manifest = inject(rows, [fault], random.Random(seed))
    (entry,) = manifest

    report = _reconcile_rendered(ledger, mutated)

    hits = [
        d
        for d in report.discrepancies
        if d.reference == entry.reference and d.type == entry.expected
    ]
    assert len(hits) == 1
    # nothing but the targeted reference is ever implicated
    assert {d.reference for d in report.discrepancies} == {entry.reference}


@settings(max_examples=50, deadline=None)
@given(ledger_lines(min_size=6), st.integers(0, 2**16))
def test_combined_faults_all_detected(ledger, seed):
    faults = list(FaultType)
    rows = to_settlement_rows(ledger)
    mutated, manifest = inject(rows, faults, random.Random(seed))

    report = _reconcile_rendered(ledger, mutated)

    assert len(manifest) == len(faults)
    for entry in manifest:
        hits = [
            d
            for d in report.discrepancies
            if d.reference == entry.reference and d.type == entry.expected
        ]
        assert len(hits) == 1
