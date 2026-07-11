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

import time
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

# The final state is ground truth for the checker, so every read must be
# DEFINITIVE: a 2xx with a body (present) or a clean 4xx (absent). An INFO
# (timeout, 5xx, dropped socket while the cluster is still recovering) is
# retried; if it never resolves the run is INCONCLUSIVE and collection aborts.
# Found live: treating an INFO GET as absence made three committed writes read
# as P1 "lost committed write" false positives during a residual failover.
_MAX_ATTEMPTS = 20
_RETRY_DELAY_S = 3.0


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
        transaction = _fetch_transaction(client, txn_id)
        if transaction is not None:
            found[txn_id] = transaction
    return found


def _fetch_transaction(client: Client, txn_id: str) -> FinalTransaction | None:
    for _ in range(_MAX_ATTEMPTS):
        response = client.request("GET", f"/api/v1/payments/{txn_id}")
        if response.outcome is Outcome.OK and response.body is not None:
            return _to_transaction(txn_id, response.body)
        if response.outcome is Outcome.FAIL:
            # a clean 4xx is the one definitive absence signal (404: no row).
            return None
        time.sleep(_RETRY_DELAY_S)
    raise RuntimeError(
        f"could not definitively read transaction {txn_id}; "
        "final state is inconclusive, refusing to emit a verdict"
    )


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
    response = _get_report(client, "/api/v1/reconciliation")
    return ReconciliationSnapshot.from_json(response)


def _fetch_anchor(client: Client) -> AnchorSnapshot:
    response = _get_report(client, "/api/v1/ledger-anchors/verify")
    return AnchorSnapshot.from_json(response)


def _get_report(client: Client, path: str) -> dict[str, object]:
    error: str | None = None
    for _ in range(_MAX_ATTEMPTS):
        response = client.request("GET", path, authed=False)
        if response.outcome is Outcome.OK and response.body is not None:
            return response.body
        error = response.error
        time.sleep(_RETRY_DELAY_S)
    raise RuntimeError(f"report endpoint {path} unavailable: {error}")
