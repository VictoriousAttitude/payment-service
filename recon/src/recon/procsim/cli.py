from __future__ import annotations

import argparse
import json
import random
from pathlib import Path

from recon.adapters.csv_ledger import parse_ledger_csv
from recon.procsim.faults import FaultType, InjectedFault, inject
from recon.procsim.generate import render_csv, to_settlement_rows


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)

    ledger = parse_ledger_csv(args.ledger)
    rows = to_settlement_rows(ledger)

    # the only impure inputs (file I/O, randomness) live here in the shell;
    # the seeded Random makes every run reproducible bit for bit
    rng = random.Random(args.seed)
    faults = [FaultType(name) for name in args.fault]
    mutated, manifest = inject(rows, faults, rng)

    args.out.write_text(render_csv(mutated), encoding="utf-8")
    if args.manifest is not None:
        args.manifest.write_text(_render_manifest(manifest), encoding="utf-8")

    print(
        f"wrote {len(mutated)} settlement line(s) to {args.out}, "
        f"{len(manifest)} fault(s) injected"
    )
    return 0


def _parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        prog="procsim",
        description=(
            "simulate an acquirer settlement file from a ledger extract, "
            "optionally injecting seeded faults"
        ),
    )
    parser.add_argument("--ledger", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument(
        "--fault",
        action="append",
        default=[],
        choices=[fault.value for fault in FaultType],
        help="fault to inject; repeat the flag to inject several",
    )
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=None,
        help="write a JSON manifest of injected faults and expected verdicts",
    )
    return parser.parse_args(argv)


def _render_manifest(manifest: list[InjectedFault]) -> str:
    entries = [
        {
            "reference": entry.reference,
            "fault": entry.fault.value,
            "expected": entry.expected.value,
        }
        for entry in manifest
    ]
    return json.dumps(entries, indent=2) + "\n"


if __name__ == "__main__":
    raise SystemExit(main())
