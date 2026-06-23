from __future__ import annotations

import argparse
from datetime import UTC, datetime, timedelta
from pathlib import Path

from recon.adapters.csv_ledger import parse_ledger_csv
from recon.adapters.csv_settlement import parse_settlement_csv
from recon.adapters.metrics import write_metrics
from recon.domain.model import ReconciliationReport
from recon.domain.reconcile import reconcile


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)

    ledger = parse_ledger_csv(args.ledger)
    settlement = parse_settlement_csv(args.settlement)

    report = reconcile(
        ledger,
        settlement,
        as_of=args.as_of,
        settlement_window=timedelta(days=args.window_days),
    )

    print(_render_summary(report))

    if args.metrics_file is not None:
        write_metrics(args.metrics_file, report)

    # non-zero exit so a scheduler/CI step fails loudly on any discrepancy
    return 0 if report.is_clean else 1


def _parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="recon",
        description="reconcile the payment ledger against a processor settlement file",
    )
    parser.add_argument("--ledger", required=True, type=Path)
    parser.add_argument("--settlement", required=True, type=Path)
    parser.add_argument(
        "--as-of",
        type=_parse_date,
        default=datetime.now(UTC),
        help="reconciliation cutoff (ISO date); defaults to now (UTC)",
    )
    parser.add_argument(
        "--window-days",
        type=int,
        default=2,
        help=(
            "settlement lag in days; ledger movements newer than this are "
            "pending, not missing (default: 2 for T+2)"
        ),
    )
    parser.add_argument("--metrics-file", type=Path, default=None)
    return parser.parse_args(argv)


def _parse_date(value: str) -> datetime:
    parsed = datetime.fromisoformat(value)
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed


def _render_summary(report: ReconciliationReport) -> str:
    lines = [
        f"matched:       {len(report.matched)}",
        f"pending:       {len(report.pending)} (within settlement window)",
        f"discrepancies: {len(report.discrepancies)}",
    ]
    for kind, count in sorted(
        report.counts_by_type().items(), key=lambda item: item[0].value
    ):
        lines.append(f"  {kind.value}: {count}")
    for discrepancy in report.discrepancies:
        detail = discrepancy.detail
        has_values = (
            discrepancy.ledger_value is not None
            or discrepancy.settlement_value is not None
        )
        if has_values:
            detail += (
                f" [ledger={discrepancy.ledger_value} "
                f"settlement={discrepancy.settlement_value}]"
            )
        lines.append(f"  - {discrepancy.reference} {discrepancy.type.value}: {detail}")
    lines.append("CLEAN" if report.is_clean else "DISCREPANCIES FOUND")
    return "\n".join(lines)


if __name__ == "__main__":
    raise SystemExit(main())
