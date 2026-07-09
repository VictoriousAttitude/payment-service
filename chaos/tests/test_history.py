from __future__ import annotations

import pytest

from chaos.domain.history import (
    AnchorSnapshot,
    HistoryEntry,
    OpKind,
    Outcome,
    ReconciliationSnapshot,
    coerce_int,
)


def test_history_entry_json_round_trip() -> None:
    entry = HistoryEntry(
        index=7,
        kind=OpKind.CREATE,
        idempotency_key="k",
        outcome=Outcome.OK,
        http_status=201,
        returned_id="t1",
        amount=1000,
        currency="USD",
    )
    assert HistoryEntry.from_json(entry.to_json()) == entry


def test_info_entry_round_trip_keeps_none_status() -> None:
    entry = HistoryEntry(
        index=1,
        kind=OpKind.CREATE,
        idempotency_key="k",
        outcome=Outcome.INFO,
        http_status=None,
    )
    restored = HistoryEntry.from_json(entry.to_json())
    assert restored.outcome is Outcome.INFO
    assert restored.http_status is None
    assert restored.returned_id is None


def test_ok_requires_status() -> None:
    with pytest.raises(ValueError, match="acknowledging status"):
        HistoryEntry(
            index=0,
            kind=OpKind.CREATE,
            idempotency_key="k",
            outcome=Outcome.OK,
            http_status=None,
        )


def test_reconciliation_from_json_reads_nested_balance() -> None:
    snapshot = ReconciliationSnapshot.from_json(
        {
            "healthy": True,
            "stuckTransactions": ["a"],
            "transactionsWithoutLedgerEntries": [],
            "unbalancedPostingGroups": ["g"],
            "amountMismatchedTransactions": [],
            "globalBalance": {"balanced": True},
            "snapshotDrift": ["x"],
        }
    )
    assert snapshot.global_balanced is True
    assert snapshot.stuck_transactions == ("a",)
    assert snapshot.unbalanced_posting_groups == ("g",)
    assert snapshot.snapshot_drift == ("x",)


def test_reconciliation_missing_balance_is_not_balanced() -> None:
    snapshot = ReconciliationSnapshot.from_json({"healthy": False})
    assert snapshot.global_balanced is False


def test_from_json_rejects_non_int_index() -> None:
    with pytest.raises(ValueError, match="must be an int"):
        HistoryEntry.from_json(
            {
                "index": "x",
                "kind": "CREATE",
                "idempotency_key": "k",
                "outcome": "OK",
                "http_status": 201,
            }
        )


def test_from_json_rejects_non_str_kind() -> None:
    with pytest.raises(ValueError, match="must be a str"):
        HistoryEntry.from_json(
            {
                "index": 0,
                "kind": 5,
                "idempotency_key": "k",
                "outcome": "OK",
                "http_status": 201,
            }
        )


def test_coerce_int_reads_ints() -> None:
    assert coerce_int(5) == 5


def test_coerce_int_defaults_non_numeric_to_zero() -> None:
    assert coerce_int(None) == 0
    assert coerce_int("7") == 0


def test_coerce_int_treats_bool_as_zero() -> None:
    # bool is an int subclass; a JSON true must not read as 1.
    assert coerce_int(True) == 0


def test_anchor_from_json_renders_failures() -> None:
    snapshot = AnchorSnapshot.from_json(
        {
            "verifiedEpochs": 2,
            "healthy": False,
            "unanchoredEntries": 4,
            "failures": [
                {
                    "epoch": 5,
                    "reason": "ROOT_MISMATCH",
                    "expected": "aa",
                    "actual": "bb",
                }
            ],
        }
    )
    assert snapshot.verified_epochs == 2
    assert snapshot.unanchored_entries == 4
    assert snapshot.failures == ("epoch 5 ROOT_MISMATCH: expected=aa actual=bb",)
