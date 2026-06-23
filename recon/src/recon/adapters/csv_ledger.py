from __future__ import annotations

import csv
from datetime import datetime
from pathlib import Path

from recon.domain.model import LedgerLine, MovementKind
from recon.domain.money import Money


def parse_ledger_csv(path: str | Path) -> list[LedgerLine]:
    """Parse our system's ledger export.

    Amounts are integer minor units, matching the JVM service's Long model, so
    no decimal rounding happens on this side - the values are already exact.
    """
    rows: list[LedgerLine] = []
    with open(path, newline="", encoding="utf-8") as handle:
        for record in csv.DictReader(handle):
            currency = record["currency"].strip()
            rows.append(
                LedgerLine(
                    reference=record["reference"].strip(),
                    kind=MovementKind(record["kind"].strip()),
                    gross=Money(int(record["gross_minor"]), currency),
                    fee=Money(int(record["fee_minor"]), currency),
                    occurred_at=_parse_timestamp(record["occurred_at"].strip()),
                )
            )
    return rows


def _parse_timestamp(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))
