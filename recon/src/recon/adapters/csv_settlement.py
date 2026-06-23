from __future__ import annotations

import csv
from decimal import Decimal
from pathlib import Path

from recon.domain.model import MovementKind, SettlementLine
from recon.domain.money import Money

# Stripe reporting categories mapped into our movement vocabulary. A different
# processor only needs a different adapter; the core never changes.
_CATEGORY_TO_KIND: dict[str, MovementKind] = {
    "charge": MovementKind.CAPTURE,
    "payment": MovementKind.CAPTURE,
    "refund": MovementKind.REFUND,
    "payment_refund": MovementKind.REFUND,
    "dispute": MovementKind.CHARGEBACK,
    "chargeback": MovementKind.CHARGEBACK,
}


def parse_settlement_csv(path: str | Path) -> list[SettlementLine]:
    """Parse a Stripe-style settlement export.

    Amounts arrive as major-unit decimals (e.g. 100.00). Decimal carries them
    across the boundary and `Money.from_decimal` converts to exact minor units
    using the currency's ISO 4217 exponent - the one place rounding is allowed.
    """
    rows: list[SettlementLine] = []
    with open(path, newline="", encoding="utf-8") as handle:
        for record in csv.DictReader(handle):
            currency = record["currency"].strip().upper()
            category = record["reporting_category"].strip().lower()
            kind = _CATEGORY_TO_KIND.get(category)
            if kind is None:
                raise ValueError(f"unknown reporting_category: {category!r}")
            rows.append(
                SettlementLine(
                    reference=record["reference"].strip(),
                    kind=kind,
                    gross=Money.from_decimal(Decimal(record["gross"]), currency),
                    fee=Money.from_decimal(Decimal(record["fee"]), currency),
                )
            )
    return rows
