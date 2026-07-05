"""Adapter and CLI shell for the anchor verifier: builds a consistent export
on disk exactly as the JVM endpoints serve it (camelCase anchors JSON,
base64-description leaf CSV) and drives the entry point end to end."""

import base64
import json
from dataclasses import replace
from pathlib import Path

from recon.adapters.anchor_export import parse_anchors_json, parse_leaves_csv
from recon.anchorverify import main
from recon.domain import merkle
from recon.domain.anchor import GENESIS, LeafRecord, canonical_leaf, chain_next

HEADER = (
    "leaf_index,entry_id,transaction_id,posting_group_id,account_type,"
    "account_id,entry_type,amount,currency,created_at_micros,description_b64"
)

PAYMENT = LeafRecord(
    leaf_index=0,
    entry_id="00000000-0000-0000-0000-000000000001",
    transaction_id="00000000-0000-0000-0000-000000000002",
    posting_group_id="00000000-0000-0000-0000-000000000002",
    account_type="MERCHANT",
    account_id="00000000-0000-0000-0000-000000000003",
    entry_type="CREDIT",
    amount=9800,
    currency="EUR",
    created_at_micros=1767323045123456,
    description="capture",
)

TREASURY = LeafRecord(
    leaf_index=1,
    entry_id="00000000-0000-0000-0000-000000000005",
    transaction_id=None,
    posting_group_id="00000000-0000-0000-0000-000000000004",
    account_type="MERCHANT_PAYABLE",
    account_id="00000000-0000-0000-0000-000000000003",
    entry_type="DEBIT",
    amount=5000,
    currency="USD",
    created_at_micros=1767323045123456,
    description=None,
)


def _row(leaf: LeafRecord) -> str:
    if leaf.description is None:
        description = "-"
    else:
        description = base64.b64encode(leaf.description.encode()).decode()
    return ",".join(
        [
            str(leaf.leaf_index),
            leaf.entry_id,
            leaf.transaction_id or "",
            leaf.posting_group_id,
            leaf.account_type,
            leaf.account_id,
            leaf.entry_type,
            str(leaf.amount),
            leaf.currency,
            str(leaf.created_at_micros),
            description,
        ]
    )


def _write_export(
    tmp_path: Path, leaf_sets: list[list[LeafRecord]]
) -> tuple[Path, Path]:
    leaves_dir = tmp_path / "leaves"
    leaves_dir.mkdir()
    anchors = []
    previous = GENESIS
    for epoch, leaves in enumerate(leaf_sets, start=1):
        root = merkle.root_hex([canonical_leaf(leaf) for leaf in leaves])
        chain = chain_next(previous, root, epoch)
        anchors.append(
            {
                "epoch": epoch,
                "root": root,
                "chainHash": chain,
                "leafCount": len(leaves),
                "createdAt": "2026-01-02T03:04:05Z",
            }
        )
        rows = [HEADER] + [_row(leaf) for leaf in leaves]
        (leaves_dir / f"{epoch}.csv").write_text("\n".join(rows) + "\n")
        previous = chain
    anchors_path = tmp_path / "anchors.json"
    anchors_path.write_text(json.dumps(anchors))
    return anchors_path, leaves_dir


def test_parsers_round_trip_the_export(tmp_path):
    anchors_path, leaves_dir = _write_export(tmp_path, [[PAYMENT, TREASURY]])

    anchors = parse_anchors_json(anchors_path)
    leaves = parse_leaves_csv(leaves_dir / "1.csv")

    assert len(anchors) == 1
    assert anchors[0].epoch == 1
    assert anchors[0].leaf_count == 2
    assert leaves == [PAYMENT, TREASURY]


def test_empty_base64_description_is_the_empty_string_not_null(tmp_path):
    empty = LeafRecord(
        leaf_index=0,
        entry_id=PAYMENT.entry_id,
        transaction_id=None,
        posting_group_id=PAYMENT.posting_group_id,
        account_type=PAYMENT.account_type,
        account_id=PAYMENT.account_id,
        entry_type=PAYMENT.entry_type,
        amount=1,
        currency="EUR",
        created_at_micros=1,
        description="",
    )
    _, leaves_dir = _write_export(tmp_path, [[empty]])

    parsed = parse_leaves_csv(leaves_dir / "1.csv")

    assert parsed[0].description == ""
    assert parsed[0].transaction_id is None


def test_cli_verifies_a_clean_export(tmp_path, capsys):
    anchors_path, leaves_dir = _write_export(
        tmp_path, [[PAYMENT, TREASURY], [replace(TREASURY, leaf_index=0)]]
    )

    code = main(["--anchors", str(anchors_path), "--leaves-dir", str(leaves_dir)])

    output = capsys.readouterr().out
    assert code == 0
    assert "epochs verified: 2" in output
    assert "CLEAN" in output


def test_cli_flags_a_tampered_leaf_amount(tmp_path, capsys):
    anchors_path, leaves_dir = _write_export(tmp_path, [[PAYMENT, TREASURY]])
    csv_path = leaves_dir / "1.csv"
    csv_path.write_text(csv_path.read_text().replace(",9800,", ",9801,"))

    code = main(["--anchors", str(anchors_path), "--leaves-dir", str(leaves_dir)])

    output = capsys.readouterr().out
    assert code == 1
    assert "ROOT_MISMATCH" in output
    assert "TAMPER EVIDENCE" in output
