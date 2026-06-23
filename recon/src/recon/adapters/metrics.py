from __future__ import annotations

from pathlib import Path

from recon.domain.model import DiscrepancyType, ReconciliationReport


def render_metrics(report: ReconciliationReport) -> str:
    """Render the report in Prometheus text exposition format.

    Batch jobs publish through the node_exporter textfile collector rather than
    an HTTP endpoint, so the engine writes a file an exporter can scrape.
    """
    counts = report.counts_by_type()
    lines = [
        "# HELP reconciliation_matched_total references present on both sides",
        "# TYPE reconciliation_matched_total gauge",
        f"reconciliation_matched_total {len(report.matched)}",
        "# HELP reconciliation_pending_total ledger movements still inside the window",
        "# TYPE reconciliation_pending_total gauge",
        f"reconciliation_pending_total {len(report.pending)}",
        "# HELP reconciliation_discrepancies_total discrepancies by type",
        "# TYPE reconciliation_discrepancies_total gauge",
    ]
    for kind in DiscrepancyType:
        lines.append(
            f'reconciliation_discrepancies_total{{type="{kind.value.lower()}"}} '
            f"{counts.get(kind, 0)}"
        )
    lines.append("# HELP reconciliation_clean 1 if no discrepancies else 0")
    lines.append("# TYPE reconciliation_clean gauge")
    lines.append(f"reconciliation_clean {1 if report.is_clean else 0}")
    return "\n".join(lines) + "\n"


def write_metrics(path: str | Path, report: ReconciliationReport) -> None:
    Path(path).write_text(render_metrics(report), encoding="utf-8")
