import pytest

from recon.adapters.csv_ledger import parse_ledger_csv
from recon.adapters.csv_settlement import parse_settlement_csv
from recon.domain.model import MovementKind
from recon.domain.money import Money


def test_parse_ledger_minor_units(tmp_path):
    path = tmp_path / "ledger.csv"
    path.write_text(
        "reference,kind,gross_minor,fee_minor,currency,occurred_at\n"
        "ch1,CAPTURE,10000,200,EUR,2026-06-10T09:00:00Z\n"
    )
    rows = parse_ledger_csv(path)
    assert rows[0].gross == Money(10000, "EUR")
    assert rows[0].fee == Money(200, "EUR")
    assert rows[0].kind == MovementKind.CAPTURE


def test_parse_settlement_decimal_to_minor(tmp_path):
    path = tmp_path / "settlement.csv"
    path.write_text(
        "reference,reporting_category,currency,gross,fee\n"
        "ch1,charge,EUR,100.00,2.00\n"
        "rf1,refund,EUR,-30.00,0.00\n"
    )
    rows = parse_settlement_csv(path)
    assert rows[0].gross == Money(10000, "EUR")
    assert rows[0].kind == MovementKind.CAPTURE
    assert rows[1].kind == MovementKind.REFUND
    assert rows[1].gross == Money(-3000, "EUR")


def test_parse_settlement_rejects_unknown_category(tmp_path):
    path = tmp_path / "settlement.csv"
    path.write_text(
        "reference,reporting_category,currency,gross,fee\n"
        "x1,topup,EUR,1.00,0.00\n"
    )
    with pytest.raises(ValueError):
        parse_settlement_csv(path)
