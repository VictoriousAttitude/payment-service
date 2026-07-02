from __future__ import annotations

from collections.abc import Iterable, Sequence

from recon.domain.model import LedgerLine, MovementKind
from recon.procsim.faults import SettlementRow

# Inverse of the csv_settlement adapter's category map (one canonical
# category per kind); procsim plays the acquirer, so it speaks that contract.
_KIND_TO_CATEGORY: dict[MovementKind, str] = {
    MovementKind.CAPTURE: "charge",
    MovementKind.REFUND: "refund",
    MovementKind.CHARGEBACK: "dispute",
}

HEADER = "reference,reporting_category,currency,gross,fee"


def to_settlement_rows(ledger: Iterable[LedgerLine]) -> list[SettlementRow]:
    """Project ledger movements to the acquirer wire format.

    `Money.to_decimal` renders exact major units at the currency's ISO 4217
    exponent (JPY -> no fraction, BHD -> three digits); signs pass through, so
    refunds and chargebacks stay negative.
    """
    return [
        SettlementRow(
            reference=line.reference,
            reporting_category=_KIND_TO_CATEGORY[line.kind],
            currency=line.gross.currency,
            gross=line.gross.to_decimal(),
            fee=line.fee.to_decimal(),
        )
        for line in ledger
    ]


def render_csv(rows: Sequence[SettlementRow]) -> str:
    """Render rows to the settlement CSV; references never contain commas or
    quotes (UUID-derived plus `:refund`/`:chargeback` suffixes), so no quoting
    layer is needed."""
    lines = [HEADER]
    lines.extend(
        f"{r.reference},{r.reporting_category},{r.currency},{r.gross},{r.fee}"
        for r in rows
    )
    return "\n".join(lines) + "\n"
