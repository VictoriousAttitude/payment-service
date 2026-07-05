"""Parsers for the JVM anchor export surface.

The anchors list is the JSON body of GET /api/v1/ledger-anchors; the leaves
file is the per-epoch CSV of GET /api/v1/ledger-anchors/{epoch}/leaves (see
AnchorLeafCsv on the JVM side for the row contract: description travels
base64-encoded, "-" marks a null description as distinct from the empty
string, and an empty transaction_id field means null).
"""

from __future__ import annotations

import base64
import csv
import json
from pathlib import Path

from recon.domain.anchor import AnchorRecord, LeafRecord

_NULL_DESCRIPTION = "-"


def parse_anchors_json(path: str | Path) -> list[AnchorRecord]:
    with open(path, encoding="utf-8") as handle:
        records = json.load(handle)
    return [
        AnchorRecord(
            epoch=int(record["epoch"]),
            root=record["root"],
            chain_hash=record["chainHash"],
            leaf_count=int(record["leafCount"]),
        )
        for record in records
    ]


def parse_leaves_csv(path: str | Path) -> list[LeafRecord]:
    rows: list[LeafRecord] = []
    with open(path, newline="", encoding="utf-8") as handle:
        for record in csv.DictReader(handle):
            rows.append(
                LeafRecord(
                    leaf_index=int(record["leaf_index"]),
                    entry_id=record["entry_id"],
                    transaction_id=record["transaction_id"] or None,
                    posting_group_id=record["posting_group_id"],
                    account_type=record["account_type"],
                    account_id=record["account_id"],
                    entry_type=record["entry_type"],
                    amount=int(record["amount"]),
                    currency=record["currency"],
                    created_at_micros=int(record["created_at_micros"]),
                    description=_decode_description(record["description_b64"]),
                )
            )
    return rows


def _decode_description(value: str) -> str | None:
    if value == _NULL_DESCRIPTION:
        return None
    return base64.b64decode(value).decode("utf-8")
