from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import Enum

from recon.domain.money import Money


class MovementKind(Enum):
    CAPTURE = "CAPTURE"
    REFUND = "REFUND"
    CHARGEBACK = "CHARGEBACK"


@dataclass(frozen=True, slots=True)
class LedgerLine:
    """A money movement our system recorded, from the ledger export."""

    reference: str
    kind: MovementKind
    gross: Money
    fee: Money
    occurred_at: datetime

    def __post_init__(self) -> None:
        if self.gross.currency != self.fee.currency:
            raise ValueError(
                f"ledger line {self.reference}: gross/fee currency mismatch"
            )


@dataclass(frozen=True, slots=True)
class SettlementLine:
    """A money movement the processor reports in the settlement file."""

    reference: str
    kind: MovementKind
    gross: Money
    fee: Money

    def __post_init__(self) -> None:
        if self.gross.currency != self.fee.currency:
            raise ValueError(
                f"settlement line {self.reference}: gross/fee currency mismatch"
            )


class DiscrepancyType(Enum):
    # processor moved money we never booked - the scariest class
    MISSING_IN_LEDGER = "MISSING_IN_LEDGER"
    # we booked money the processor never settled (past the settlement window)
    MISSING_IN_SETTLEMENT = "MISSING_IN_SETTLEMENT"
    KIND_MISMATCH = "KIND_MISMATCH"
    CURRENCY_MISMATCH = "CURRENCY_MISMATCH"
    GROSS_MISMATCH = "GROSS_MISMATCH"
    # fee we expected vs fee the processor actually took - margin leak
    FEE_MISMATCH = "FEE_MISMATCH"
    DUPLICATE_REFERENCE = "DUPLICATE_REFERENCE"


@dataclass(frozen=True, slots=True)
class Discrepancy:
    type: DiscrepancyType
    reference: str
    detail: str
    ledger_value: str | None = None
    settlement_value: str | None = None


@dataclass(frozen=True, slots=True)
class MatchedPair:
    reference: str
    ledger: LedgerLine
    settlement: SettlementLine


@dataclass(frozen=True, slots=True)
class ReconciliationReport:
    matched: tuple[MatchedPair, ...]
    discrepancies: tuple[Discrepancy, ...]
    # ledger-only movements still inside the settlement window: not yet
    # expected in the file, so not a discrepancy.
    pending: tuple[LedgerLine, ...]

    @property
    def is_clean(self) -> bool:
        return len(self.discrepancies) == 0

    def counts_by_type(self) -> dict[DiscrepancyType, int]:
        counts: dict[DiscrepancyType, int] = {}
        for discrepancy in self.discrepancies:
            counts[discrepancy.type] = counts.get(discrepancy.type, 0) + 1
        return counts
