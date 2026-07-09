from __future__ import annotations

from collections.abc import Iterable, Mapping

from chaos.domain.history import (
    AnchorSnapshot,
    FinalState,
    FinalTransaction,
    HistoryEntry,
    OpKind,
    Outcome,
    ReconciliationSnapshot,
)


def ok_create(index: int, key: str, txn_id: str) -> HistoryEntry:
    return HistoryEntry(
        index=index,
        kind=OpKind.CREATE,
        idempotency_key=key,
        outcome=Outcome.OK,
        http_status=201,
        returned_id=txn_id,
        amount=1000,
        currency="USD",
    )


def info_create(index: int, key: str) -> HistoryEntry:
    return HistoryEntry(
        index=index,
        kind=OpKind.CREATE,
        idempotency_key=key,
        outcome=Outcome.INFO,
        http_status=None,
        amount=1000,
        currency="USD",
    )


def clean_reconciliation() -> ReconciliationSnapshot:
    return ReconciliationSnapshot(
        healthy=True,
        stuck_transactions=(),
        transactions_without_ledger_entries=(),
        unbalanced_posting_groups=(),
        amount_mismatched_transactions=(),
        global_balanced=True,
        snapshot_drift=(),
    )


def clean_anchor() -> AnchorSnapshot:
    return AnchorSnapshot(
        verified_epochs=3, healthy=True, unanchored_entries=0, failures=()
    )


def final_state(
    txn_ids: Iterable[str],
    *,
    reconciliation: ReconciliationSnapshot | None = None,
    anchor: AnchorSnapshot | None = None,
) -> FinalState:
    transactions: Mapping[str, FinalTransaction] = {
        txn_id: FinalTransaction(
            id=txn_id,
            status="AUTHORIZED",
            amount=1000,
            captured_amount=0,
            refunded_amount=0,
            currency="USD",
        )
        for txn_id in txn_ids
    }
    return FinalState(
        transactions=transactions,
        reconciliation=reconciliation or clean_reconciliation(),
        anchor=anchor or clean_anchor(),
    )
