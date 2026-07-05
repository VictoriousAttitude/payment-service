"""Offline anchor chain verifier CLI.

The independent leg of the tamper-evidence design: the JVM /verify endpoint
recomputes against its own database, this tool recomputes against the
EXPORTED anchors and leaves with a separate implementation, so a compromise
of the service (or its database) cannot silently vouch for itself.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from recon.adapters.anchor_export import parse_anchors_json, parse_leaves_csv
from recon.domain.anchor import AnchorFailure, verify


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)

    anchors = parse_anchors_json(args.anchors)
    leaves_by_epoch = {
        anchor.epoch: parse_leaves_csv(args.leaves_dir / f"{anchor.epoch}.csv")
        for anchor in anchors
    }

    failures = verify(anchors, leaves_by_epoch)
    print(_render(len(anchors), failures))

    # non-zero exit so a scheduler/CI step fails loudly on tamper evidence
    return 0 if not failures else 1


def _parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="anchorverify",
        description="verify the exported ledger anchor chain by recomputation",
    )
    parser.add_argument(
        "--anchors",
        required=True,
        type=Path,
        help="anchors JSON export (GET /api/v1/ledger-anchors)",
    )
    parser.add_argument(
        "--leaves-dir",
        required=True,
        type=Path,
        help="directory holding one <epoch>.csv leaf export per anchor",
    )
    return parser.parse_args(argv)


def _render(verified: int, failures: tuple[AnchorFailure, ...]) -> str:
    lines = [
        f"epochs verified: {verified}",
        f"failures:        {len(failures)}",
    ]
    for failure in failures:
        lines.append(
            f"  - epoch {failure.epoch} {failure.reason.value}: "
            f"expected={failure.expected} actual={failure.actual}"
        )
    lines.append("CLEAN" if not failures else "TAMPER EVIDENCE")
    return "\n".join(lines)


if __name__ == "__main__":
    raise SystemExit(main())
