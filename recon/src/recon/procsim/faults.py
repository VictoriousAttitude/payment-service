from __future__ import annotations

import random
from collections.abc import Sequence
from dataclasses import dataclass, replace
from decimal import Decimal
from enum import Enum

from recon.domain.model import DiscrepancyType
from recon.domain.money import Money


class FaultType(Enum):
    """One acquirer failure mode; values double as the CLI spelling."""

    DROP_LINE = "drop"
    PHANTOM_LINE = "phantom"
    DUPLICATE_LINE = "duplicate"
    WRONG_KIND = "wrong_kind"
    WRONG_CURRENCY = "wrong_currency"
    WRONG_GROSS = "wrong_gross"
    WRONG_FEE = "wrong_fee"


# The fault taxonomy maps 1:1 onto the reconciler's discrepancy vocabulary:
# every injected fault names the exact verdict a correct matcher must return.
EXPECTED_DISCREPANCY: dict[FaultType, DiscrepancyType] = {
    FaultType.DROP_LINE: DiscrepancyType.MISSING_IN_SETTLEMENT,
    FaultType.PHANTOM_LINE: DiscrepancyType.MISSING_IN_LEDGER,
    FaultType.DUPLICATE_LINE: DiscrepancyType.DUPLICATE_REFERENCE,
    FaultType.WRONG_KIND: DiscrepancyType.KIND_MISMATCH,
    FaultType.WRONG_CURRENCY: DiscrepancyType.CURRENCY_MISMATCH,
    FaultType.WRONG_GROSS: DiscrepancyType.GROSS_MISMATCH,
    FaultType.WRONG_FEE: DiscrepancyType.FEE_MISMATCH,
}

# Swap partner shares the ISO 4217 exponent, so the rendered decimals reparse
# to the same minor integers and only the currency check can fire - a
# cross-exponent swap would trip amount checks or precision validation too.
_CURRENCY_SWAP: dict[str, str] = {
    "EUR": "USD",
    "USD": "EUR",
    "GBP": "CHF",
    "CHF": "GBP",
    "JPY": "KRW",
    "KRW": "JPY",
    "BHD": "KWD",
    "KWD": "BHD",
    "TND": "KWD",
}

# Remap within the categories procsim emits; each target maps to a different
# movement kind, so only the kind check fires.
_CATEGORY_REMAP: dict[str, str] = {
    "charge": "refund",
    "refund": "dispute",
    "dispute": "charge",
}


@dataclass(frozen=True, slots=True)
class SettlementRow:
    """One settlement file line; amounts are signed major-unit decimals."""

    reference: str
    reporting_category: str
    currency: str
    gross: Decimal
    fee: Decimal


@dataclass(frozen=True, slots=True)
class InjectedFault:
    """Manifest entry: the fault applied and the verdict it must produce."""

    reference: str
    fault: FaultType
    expected: DiscrepancyType


def inject(
    rows: Sequence[SettlementRow],
    fault_types: Sequence[FaultType],
    rng: random.Random,
) -> tuple[list[SettlementRow], list[InjectedFault]]:
    """Apply each requested fault to a distinct target row.

    Targets are drawn without replacement so faults never overlap and each
    manifest entry stays independently verifiable. PHANTOM_LINE needs no
    target; it appends a fresh `phantom-<n>` reference instead.
    """
    targeted = [f for f in fault_types if f is not FaultType.PHANTOM_LINE]
    if len(targeted) > len(rows):
        raise ValueError(
            f"{len(targeted)} targeted faults but only {len(rows)} rows"
        )

    free = list(range(len(rows)))
    manifest: list[InjectedFault] = []
    drops: set[int] = set()
    duplicates: set[int] = set()
    mutations: dict[int, FaultType] = {}
    phantoms: list[SettlementRow] = []

    for fault in fault_types:
        if fault is FaultType.PHANTOM_LINE:
            phantom = _phantom_row(len(phantoms) + 1)
            phantoms.append(phantom)
            manifest.append(
                InjectedFault(phantom.reference, fault, EXPECTED_DISCREPANCY[fault])
            )
            continue
        idx = free.pop(rng.randrange(len(free)))
        manifest.append(
            InjectedFault(rows[idx].reference, fault, EXPECTED_DISCREPANCY[fault])
        )
        if fault is FaultType.DROP_LINE:
            drops.add(idx)
        elif fault is FaultType.DUPLICATE_LINE:
            duplicates.add(idx)
        else:
            mutations[idx] = fault

    result: list[SettlementRow] = []
    for i, row in enumerate(rows):
        if i in drops:
            continue
        mutated = _mutate(row, mutations[i]) if i in mutations else row
        result.append(mutated)
        if i in duplicates:
            result.append(mutated)
    result.extend(phantoms)
    return result, manifest


def _phantom_row(n: int) -> SettlementRow:
    return SettlementRow(
        reference=f"phantom-{n}",
        reporting_category="charge",
        currency="EUR",
        gross=Decimal("1.00"),
        fee=Decimal("0.00"),
    )


def _mutate(row: SettlementRow, fault: FaultType) -> SettlementRow:
    if fault is FaultType.WRONG_KIND:
        category = _CATEGORY_REMAP[row.reporting_category]
        return replace(row, reporting_category=category)
    if fault is FaultType.WRONG_CURRENCY:
        return replace(row, currency=_swap_currency(row.currency))
    if fault is FaultType.WRONG_GROSS:
        return replace(row, gross=_off_by_one_minor(row.gross, row.currency))
    if fault is FaultType.WRONG_FEE:
        return replace(row, fee=_off_by_one_minor(row.fee, row.currency))
    raise AssertionError(f"not a mutation fault: {fault}")


def _swap_currency(currency: str) -> str:
    # unlisted codes carry the default exponent 2, same as USD/EUR
    return _CURRENCY_SWAP.get(currency, "USD" if currency != "USD" else "EUR")


def _off_by_one_minor(value: Decimal, currency: str) -> Decimal:
    """Perturb by exactly one minor unit, exponent-aware, never float."""
    minor = Money.from_decimal(value, currency).minor
    return Money(minor + 1, currency).to_decimal()
