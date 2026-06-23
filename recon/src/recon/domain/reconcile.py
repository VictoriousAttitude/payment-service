from __future__ import annotations

from collections.abc import Callable, Iterable, Sequence
from datetime import datetime, timedelta
from typing import TypeVar

from recon.domain.model import (
    Discrepancy,
    DiscrepancyType,
    LedgerLine,
    MatchedPair,
    ReconciliationReport,
    SettlementLine,
)

T = TypeVar("T")


def reconcile(
    ledger: Sequence[LedgerLine],
    settlement: Sequence[SettlementLine],
    *,
    as_of: datetime,
    settlement_window: timedelta,
) -> ReconciliationReport:
    """Reconcile our ledger against the processor settlement file.

    The sides are joined on `reference` (one reference per money movement).
    Every reference in either side lands in exactly one bucket - matched,
    ledger-only, or settlement-only. That partition is exhaustive by
    construction, which is the completeness guarantee: nothing is silently
    dropped, and "did we check everything?" has a yes/no answer.
    """
    discrepancies: list[Discrepancy] = []

    ledger_index, ledger_dupes = _index(ledger, lambda line: line.reference)
    settlement_index, settlement_dupes = _index(
        settlement, lambda line: line.reference
    )

    for ref in sorted(ledger_dupes):
        discrepancies.append(
            Discrepancy(
                DiscrepancyType.DUPLICATE_REFERENCE,
                ref,
                "reference appears more than once in the ledger export",
            )
        )
    for ref in sorted(settlement_dupes):
        discrepancies.append(
            Discrepancy(
                DiscrepancyType.DUPLICATE_REFERENCE,
                ref,
                "reference appears more than once in the settlement file",
            )
        )

    ledger_keys = set(ledger_index)
    settlement_keys = set(settlement_index)

    matched: list[MatchedPair] = []
    for ref in sorted(ledger_keys & settlement_keys):
        left = ledger_index[ref]
        right = settlement_index[ref]
        matched.append(MatchedPair(ref, left, right))
        discrepancies.extend(_compare(left, right))

    pending: list[LedgerLine] = []
    cutoff = as_of - settlement_window
    for ref in sorted(ledger_keys - settlement_keys):
        line = ledger_index[ref]
        if line.occurred_at > cutoff:
            # too recent to have settled yet - expected, not a discrepancy
            pending.append(line)
        else:
            discrepancies.append(
                Discrepancy(
                    DiscrepancyType.MISSING_IN_SETTLEMENT,
                    ref,
                    "booked in the ledger but absent from the settlement file",
                    ledger_value=str(line.gross),
                )
            )

    for ref in sorted(settlement_keys - ledger_keys):
        settlement_line = settlement_index[ref]
        discrepancies.append(
            Discrepancy(
                DiscrepancyType.MISSING_IN_LEDGER,
                ref,
                "settled by the processor but never booked in the ledger",
                settlement_value=str(settlement_line.gross),
            )
        )

    return ReconciliationReport(tuple(matched), tuple(discrepancies), tuple(pending))


def _index(
    rows: Iterable[T], key: Callable[[T], str]
) -> tuple[dict[str, T], set[str]]:
    """Index rows by key, returning the unique rows and the colliding keys.

    A duplicated reference is ambiguous to match, so it is excluded from the
    index and surfaced as its own discrepancy rather than silently deduped -
    silent dedup would let a double-booking pass reconciliation unnoticed.
    """
    index: dict[str, T] = {}
    duplicates: set[str] = set()
    for row in rows:
        k = key(row)
        if k in index or k in duplicates:
            duplicates.add(k)
            index.pop(k, None)
        else:
            index[k] = row
    return index, duplicates


def _compare(left: LedgerLine, right: SettlementLine) -> list[Discrepancy]:
    found: list[Discrepancy] = []

    if left.kind != right.kind:
        found.append(
            Discrepancy(
                DiscrepancyType.KIND_MISMATCH,
                left.reference,
                "movement kind differs",
                ledger_value=left.kind.value,
                settlement_value=right.kind.value,
            )
        )

    if left.gross.currency != right.gross.currency:
        # cross-currency: amounts are not comparable, stop after flagging it
        found.append(
            Discrepancy(
                DiscrepancyType.CURRENCY_MISMATCH,
                left.reference,
                "currency differs",
                ledger_value=left.gross.currency,
                settlement_value=right.gross.currency,
            )
        )
        return found

    if left.gross != right.gross:
        found.append(
            Discrepancy(
                DiscrepancyType.GROSS_MISMATCH,
                left.reference,
                "gross amount differs",
                ledger_value=str(left.gross),
                settlement_value=str(right.gross),
            )
        )

    if left.fee != right.fee:
        found.append(
            Discrepancy(
                DiscrepancyType.FEE_MISMATCH,
                left.reference,
                "fee differs (possible margin leak)",
                ledger_value=str(left.fee),
                settlement_value=str(right.fee),
            )
        )

    return found
