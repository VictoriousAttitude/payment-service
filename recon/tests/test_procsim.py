import random
from datetime import UTC, datetime
from decimal import Decimal

import pytest

from recon.domain.model import LedgerLine, MovementKind
from recon.domain.money import Money, exponent_of
from recon.procsim.cli import main
from recon.procsim.faults import (
    EXPECTED_DISCREPANCY,
    FaultType,
    inject,
)
from recon.procsim.generate import render_csv, to_settlement_rows

OCCURRED = datetime(2026, 6, 10, 9, 0, tzinfo=UTC)


def _line(ref, kind, gross, fee, currency):
    return LedgerLine(ref, kind, Money(gross, currency), Money(fee, currency), OCCURRED)


def test_kind_maps_to_stripe_reporting_category():
    rows = to_settlement_rows(
        [
            _line("t1", MovementKind.CAPTURE, 10000, 200, "EUR"),
            _line("t1:refund", MovementKind.REFUND, -3000, -60, "EUR"),
            _line("t1:chargeback", MovementKind.CHARGEBACK, -10000, 1500, "EUR"),
        ]
    )
    assert [r.reporting_category for r in rows] == ["charge", "refund", "dispute"]


def test_signed_major_decimals_pass_through():
    rows = to_settlement_rows(
        [_line("t1:refund", MovementKind.REFUND, -3000, -60, "EUR")]
    )
    assert rows[0].gross == Decimal("-30.00")
    assert rows[0].fee == Decimal("-0.60")


def test_jpy_renders_zero_exponent_major_units():
    rows = to_settlement_rows([_line("j1", MovementKind.CAPTURE, 1234, 24, "JPY")])
    assert rows[0].gross == Decimal("1234")
    assert "j1,charge,JPY,1234,24" in render_csv(rows)


def test_bhd_renders_three_exponent_major_units():
    rows = to_settlement_rows([_line("b1", MovementKind.CAPTURE, 12345, 246, "BHD")])
    assert rows[0].gross == Decimal("12.345")
    assert "b1,charge,BHD,12.345,0.246" in render_csv(rows)


def test_same_seed_same_output():
    rows = to_settlement_rows(
        [
            _line(f"t{i}", MovementKind.CAPTURE, 1000 * (i + 1), 20, "EUR")
            for i in range(5)
        ]
    )
    faults = [FaultType.DROP_LINE, FaultType.WRONG_FEE, FaultType.PHANTOM_LINE]
    first = inject(rows, faults, random.Random(42))
    second = inject(rows, faults, random.Random(42))
    assert first == second


@pytest.mark.parametrize("currency", ["EUR", "JPY", "BHD"])
def test_wrong_currency_swaps_within_same_exponent(currency):
    rows = to_settlement_rows(
        [_line("t1", MovementKind.CAPTURE, 5000, 100, currency)]
    )
    mutated, _ = inject(rows, [FaultType.WRONG_CURRENCY], random.Random(1))
    assert mutated[0].currency != currency
    assert exponent_of(mutated[0].currency) == exponent_of(currency)
    # amounts untouched, so only the currency check can fire downstream
    assert mutated[0].gross == rows[0].gross
    assert mutated[0].fee == rows[0].fee


def test_wrong_gross_perturbs_exactly_one_minor_unit():
    rows = to_settlement_rows([_line("t1", MovementKind.CAPTURE, 10000, 200, "EUR")])
    mutated, _ = inject(rows, [FaultType.WRONG_GROSS], random.Random(3))
    assert mutated[0].gross == Decimal("100.01")


def test_manifest_lists_expected_discrepancy_per_fault():
    rows = to_settlement_rows(
        [
            _line(f"t{i}", MovementKind.CAPTURE, 1000 * (i + 1), 20, "EUR")
            for i in range(7)
        ]
    )
    faults = list(FaultType)
    _, manifest = inject(rows, faults, random.Random(7))
    assert [entry.fault for entry in manifest] == faults
    for entry in manifest:
        assert entry.expected == EXPECTED_DISCREPANCY[entry.fault]
    phantom = next(e for e in manifest if e.fault is FaultType.PHANTOM_LINE)
    assert phantom.reference == "phantom-1"


def test_more_targeted_faults_than_rows_rejected():
    rows = to_settlement_rows([_line("t1", MovementKind.CAPTURE, 1000, 20, "EUR")])
    with pytest.raises(ValueError):
        inject(rows, [FaultType.DROP_LINE, FaultType.WRONG_FEE], random.Random(1))


def test_cli_writes_settlement_and_manifest(tmp_path, capsys):
    ledger = tmp_path / "ledger.csv"
    ledger.write_text(
        "reference,kind,gross_minor,fee_minor,currency,occurred_at\n"
        "ch1,CAPTURE,10000,200,EUR,2026-06-10T09:00:00Z\n"
    )
    out = tmp_path / "settlement.csv"
    manifest = tmp_path / "manifest.json"
    code = main(
        [
            "--ledger",
            str(ledger),
            "--out",
            str(out),
            "--fault",
            "wrong_fee",
            "--seed",
            "42",
            "--manifest",
            str(manifest),
        ]
    )
    assert code == 0
    assert "ch1,charge,EUR,100.00,2.01" in out.read_text()
    body = manifest.read_text()
    assert '"fault": "wrong_fee"' in body
    assert '"expected": "FEE_MISMATCH"' in body
    assert "1 fault(s) injected" in capsys.readouterr().out
