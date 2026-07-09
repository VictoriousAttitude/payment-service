"""Final-state collector.

After the workload finishes and the cluster has quiesced, read the ground truth
the checker needs: every transaction the history mentions (GET by id), plus the
service's own reconciliation and anchor-verification self-reports. The pure
checker then decides the verdict; this module only fetches.

Quiescence matters for P7: the outbox dispatcher, reconciliation, anchor and
snapshot batches are all ShedLock-gated and run on a schedule, so the caller
must wait long enough after the last fault for the survivor to drain them
before collecting, or a transiently-stuck row reads as a violation.

Imperative shell. Requires the live cluster; not unit tested.
"""

from __future__ import annotations

from collections.abc import Iterable

from chaos.domain.history import (
    AnchorSnapshot,
    FinalState,
    FinalTransaction,
    HistoryEntry,
    Outcome,
    ReconciliationSnapshot,
    coerce_int,
)
from chaos.driver.client import Client


def collect(client: Client, history: Iterable[HistoryEntry]) -> FinalState:
    transactions = _fetch_transactions(client, history)
    return FinalState(
        transactions=transactions,
        reconciliation=_fetch_reconciliation(client),
        anchor=_fetch_anchor(client),
    )


def _fetch_transactions(
    client: Client, history: Iterable[HistoryEntry]
) -> dict[str, FinalTransaction]:
    ids = {
        entry.returned_id
        for entry in history
        if entry.outcome is Outcome.OK and entry.returned_id is not None
    }
    found: dict[str, FinalTransaction] = {}
    for txn_id in sorted(ids):
        response = client.request("GET", f"/api/v1/payments/{txn_id}")
        if response.outcome is Outcome.OK and response.body is not None:
            found[txn_id] = _to_transaction(txn_id, response.body)
    return found


def _to_transaction(txn_id: str, body: dict[str, object]) -> FinalTransaction:
    return FinalTransaction(
        id=txn_id,
        status=str(body.get("status")),
        amount=coerce_int(body.get("amount")),
        captured_amount=coerce_int(body.get("capturedAmount")),
        refunded_amount=coerce_int(body.get("refundedAmount")),
        currency=str(body.get("currency")),
    )


def _fetch_reconciliation(client: Client) -> ReconciliationSnapshot:
    response = client.request("GET", "/api/v1/reconciliation", authed=False)
    if response.body is None:
        raise RuntimeError(f"reconciliation endpoint unavailable: {response.error}")
    return ReconciliationSnapshot.from_json(response.body)


def _fetch_anchor(client: Client) -> AnchorSnapshot:
    response = client.request("GET", "/api/v1/ledger-anchors/verify", authed=False)
    if response.body is None:
        raise RuntimeError(f"anchor verify endpoint unavailable: {response.error}")
    return AnchorSnapshot.from_json(response.body)
