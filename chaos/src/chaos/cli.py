"""Chaos harness entry point.

Two subcommands, split along the functional-core/imperative-shell line:

  run    the imperative shell. Drives a live step-3 cluster: fires the workload,
         injects faults, records the history, collects the final state, then
         hands both to the pure checker. Needs the cluster and kubectl.
  check  the pure leg. Re-runs the checker over already-recorded artifacts (a
         history JSONL and a final-state JSON), with zero I/O beyond reading the
         two files. A failing run is reproducible from its artifacts on any
         machine, and CI can gate on `check` without a cluster.
"""

from __future__ import annotations

import argparse
import json
import threading
import time
from pathlib import Path

from chaos.adapters.recorder import HistoryWriter, read_history
from chaos.domain.checker import check, render
from chaos.domain.history import (
    AnchorSnapshot,
    FinalState,
    FinalTransaction,
    ReconciliationSnapshot,
    coerce_int,
)
from chaos.driver.client import Client
from chaos.driver.collector import collect
from chaos.driver.nemesis import Nemesis, NemesisConfig
from chaos.driver.workload import Workload


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    if args.command == "run":
        return _cmd_run(args)
    return _cmd_check(args)


def _cmd_run(args: argparse.Namespace) -> int:
    client = Client(args.base_url, args.api_key, timeout=args.timeout)
    nemesis = Nemesis(NemesisConfig(namespace=args.namespace))

    with HistoryWriter(args.history) as writer:
        workload = Workload(client, args.merchant_id, writer, amount=args.amount)
        worker = threading.Thread(
            target=workload.run, args=(args.ops, args.threads)
        )
        worker.start()
        _inject(nemesis, args.nemesis, args.period, worker)
        worker.join()

    print(f"workload complete, settling {args.settle}s before collecting")
    time.sleep(args.settle)

    history = read_history(args.history)
    final = collect(client, history)
    _write_final(args.final, final)

    report = check(history, final)
    print(render(report))
    return 0 if report.ok else 1


def _cmd_check(args: argparse.Namespace) -> int:
    history = read_history(args.history)
    final = _read_final(args.final)
    report = check(history, final)
    print(render(report))
    return 0 if report.ok else 1


def _inject(
    nemesis: Nemesis, mode: str, period: float, worker: threading.Thread
) -> None:
    """Kill something every `period` seconds while the workload runs."""
    turn = 0
    while worker.is_alive():
        time.sleep(period)
        if not worker.is_alive():
            break
        if mode == "app" or (mode == "both" and turn % 2 == 0):
            victim = nemesis.kill_app_pod()
            print(f"nemesis: killed app pod {victim}")
        elif mode in {"db", "both"}:
            victim = nemesis.kill_db_primary()
            print(f"nemesis: killed db primary {victim}")
        turn += 1


def _write_final(path: Path, final: FinalState) -> None:
    payload = {
        "transactions": {
            txn_id: {
                "status": txn.status,
                "amount": txn.amount,
                "capturedAmount": txn.captured_amount,
                "refundedAmount": txn.refunded_amount,
                "currency": txn.currency,
            }
            for txn_id, txn in final.transactions.items()
        },
        "reconciliation": {
            "healthy": final.reconciliation.healthy,
            "stuckTransactions": list(final.reconciliation.stuck_transactions),
            "transactionsWithoutLedgerEntries": list(
                final.reconciliation.transactions_without_ledger_entries
            ),
            "unbalancedPostingGroups": list(
                final.reconciliation.unbalanced_posting_groups
            ),
            "amountMismatchedTransactions": list(
                final.reconciliation.amount_mismatched_transactions
            ),
            "globalBalance": {"balanced": final.reconciliation.global_balanced},
            "snapshotDrift": list(final.reconciliation.snapshot_drift),
        },
        "anchor": {
            "verifiedEpochs": final.anchor.verified_epochs,
            "healthy": final.anchor.healthy,
            "unanchoredEntries": final.anchor.unanchored_entries,
            "failures": list(final.anchor.failures),
        },
    }
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")


def _read_final(path: Path) -> FinalState:
    raw = json.loads(path.read_text(encoding="utf-8"))
    transactions = {
        txn_id: FinalTransaction(
            id=txn_id,
            status=str(body.get("status")),
            amount=coerce_int(body.get("amount")),
            captured_amount=coerce_int(body.get("capturedAmount")),
            refunded_amount=coerce_int(body.get("refundedAmount")),
            currency=str(body.get("currency")),
        )
        for txn_id, body in raw.get("transactions", {}).items()
    }
    return FinalState(
        transactions=transactions,
        reconciliation=ReconciliationSnapshot.from_json(raw["reconciliation"]),
        anchor=AnchorSnapshot.from_json(raw["anchor"]),
    )


def _parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="chaos",
        description="jepsen-lite chaos harness for the payment ledger",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    run = sub.add_parser("run", help="drive a live cluster and check the result")
    run.add_argument("--base-url", default="http://localhost:8080")
    run.add_argument("--api-key", required=True)
    run.add_argument("--merchant-id", required=True)
    run.add_argument("--namespace", default="payments")
    run.add_argument("--ops", type=int, default=200)
    run.add_argument("--threads", type=int, default=8)
    run.add_argument("--amount", type=int, default=1000)
    run.add_argument(
        "--nemesis", choices=["app", "db", "both"], default="both"
    )
    run.add_argument("--period", type=float, default=5.0, help="seconds between kills")
    run.add_argument(
        "--settle", type=float, default=30.0, help="quiescence wait before collecting"
    )
    run.add_argument("--timeout", type=float, default=5.0)
    run.add_argument("--history", type=Path, default=Path("history.jsonl"))
    run.add_argument("--final", type=Path, default=Path("final.json"))

    chk = sub.add_parser("check", help="re-check recorded artifacts offline")
    chk.add_argument("--history", type=Path, required=True)
    chk.add_argument("--final", type=Path, required=True)

    return parser.parse_args(argv)


if __name__ == "__main__":
    raise SystemExit(main())
