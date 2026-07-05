"""Anchor chain verification: the offline mirror of the JVM verifier.

Rebuilds the canonical leaf byte string from the exported columns, recomputes
every epoch root with :mod:`recon.domain.merkle`, and recomputes every chain
link from genesis. The canonical encoding and the chain payload are byte
contracts shared with the JVM (CanonicalLeafCodec / AnchorChain); the golden
tests on both sides pin identical constants.

Chain verification feeds each anchor's STORED chain hash into the next link,
matching the JVM rule: a corrupted anchor is flagged at itself (and its
immediate successor) instead of cascading a false failure over every later
epoch.
"""

from __future__ import annotations

import hashlib
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from enum import Enum

from recon.domain import merkle

GENESIS = "0" * 64

_NULL_DESCRIPTION_MARKER = "0"
_PRESENT_DESCRIPTION_MARKER = "1"


@dataclass(frozen=True, slots=True)
class AnchorRecord:
    """One sealed epoch as the service exported it."""

    epoch: int
    root: str
    chain_hash: str
    leaf_count: int


@dataclass(frozen=True, slots=True)
class LeafRecord:
    """One ledger entry at one leaf position, from the per-epoch CSV export."""

    leaf_index: int
    entry_id: str
    transaction_id: str | None
    posting_group_id: str
    account_type: str
    account_id: str
    entry_type: str
    amount: int
    currency: str
    created_at_micros: int
    description: str | None


def canonical_string(leaf: LeafRecord) -> str:
    """Mirror of the JVM CanonicalLeafCodec: pipe-joined restricted fields
    with the free-text description last, prefixed by a null/present marker so
    the encoding stays injective without any escaping."""
    if leaf.description is None:
        description = _NULL_DESCRIPTION_MARKER
    else:
        description = _PRESENT_DESCRIPTION_MARKER + leaf.description
    return "|".join(
        [
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


def canonical_leaf(leaf: LeafRecord) -> bytes:
    # encode() defaults to UTF-8, the contract encoding
    return canonical_string(leaf).encode()


def chain_next(previous_chain_hash: str, root_hex: str, epoch: int) -> str:
    """Mirror of the JVM AnchorChain: SHA-256 over "epoch|root|previous"."""
    payload = f"{epoch}|{root_hex}|{previous_chain_hash}"
    return hashlib.sha256(payload.encode()).hexdigest()


class FailureReason(Enum):
    EPOCH_GAP = "EPOCH_GAP"
    CHAIN_MISMATCH = "CHAIN_MISMATCH"
    LEAF_COUNT_MISMATCH = "LEAF_COUNT_MISMATCH"
    LEAF_INDEX_GAP = "LEAF_INDEX_GAP"
    ROOT_MISMATCH = "ROOT_MISMATCH"


@dataclass(frozen=True, slots=True)
class AnchorFailure:
    epoch: int
    reason: FailureReason
    expected: str
    actual: str


def verify(
    anchors: Sequence[AnchorRecord],
    leaves_by_epoch: Mapping[int, Sequence[LeafRecord]],
) -> tuple[AnchorFailure, ...]:
    failures: list[AnchorFailure] = []
    previous_epoch = 0
    previous_chain = GENESIS
    for anchor in sorted(anchors, key=lambda record: record.epoch):
        leaves = leaves_by_epoch.get(anchor.epoch, ())
        failures.extend(_verify_anchor(anchor, previous_epoch, previous_chain, leaves))
        previous_epoch = anchor.epoch
        previous_chain = anchor.chain_hash
    return tuple(failures)


def _verify_anchor(
    anchor: AnchorRecord,
    previous_epoch: int,
    previous_chain: str,
    leaves: Sequence[LeafRecord],
) -> list[AnchorFailure]:
    failures: list[AnchorFailure] = []
    if anchor.epoch != previous_epoch + 1:
        failures.append(
            AnchorFailure(
                anchor.epoch,
                FailureReason.EPOCH_GAP,
                str(previous_epoch + 1),
                str(anchor.epoch),
            )
        )
    expected_chain = chain_next(previous_chain, anchor.root, anchor.epoch)
    if expected_chain != anchor.chain_hash:
        failures.append(
            AnchorFailure(
                anchor.epoch,
                FailureReason.CHAIN_MISMATCH,
                expected_chain,
                anchor.chain_hash,
            )
        )
    ordered = sorted(leaves, key=lambda leaf: leaf.leaf_index)
    if len(ordered) != anchor.leaf_count:
        failures.append(
            AnchorFailure(
                anchor.epoch,
                FailureReason.LEAF_COUNT_MISMATCH,
                str(anchor.leaf_count),
                str(len(ordered)),
            )
        )
    indexes = [leaf.leaf_index for leaf in ordered]
    if indexes != list(range(len(ordered))):
        failures.append(
            AnchorFailure(
                anchor.epoch,
                FailureReason.LEAF_INDEX_GAP,
                ",".join(str(index) for index in range(len(ordered))),
                ",".join(str(index) for index in indexes),
            )
        )
    recomputed = merkle.root_hex([canonical_leaf(leaf) for leaf in ordered])
    if recomputed != anchor.root:
        failures.append(
            AnchorFailure(
                anchor.epoch,
                FailureReason.ROOT_MISMATCH,
                anchor.root,
                recomputed,
            )
        )
    return failures
