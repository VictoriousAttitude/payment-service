"""RFC 6962 Merkle tree hashing, independent of the JVM implementation.

leaf = SHA-256(0x00 || data), node = SHA-256(0x01 || left || right), and an
unbalanced tree splits at the largest power of two smaller than n (MTH from
RFC 6962 section 2.1). The 0x00/0x01 domain separation is what blocks the
concatenation second-preimage: without it, the single leaf "l0l1" and the
two-leaf tree ["l0", "l1"] would hash identically.
"""

from __future__ import annotations

import hashlib
from collections.abc import Sequence

_LEAF_PREFIX = b"\x00"
_NODE_PREFIX = b"\x01"


def leaf_hash(data: bytes) -> bytes:
    return hashlib.sha256(_LEAF_PREFIX + data).digest()


def node_hash(left: bytes, right: bytes) -> bytes:
    return hashlib.sha256(_NODE_PREFIX + left + right).digest()


def root(leaves: Sequence[bytes]) -> bytes:
    if not leaves:
        return hashlib.sha256(b"").digest()
    if len(leaves) == 1:
        return leaf_hash(leaves[0])
    split = _largest_power_of_two_below(len(leaves))
    return node_hash(root(leaves[:split]), root(leaves[split:]))


def root_hex(leaves: Sequence[bytes]) -> str:
    return root(leaves).hex()


def _largest_power_of_two_below(n: int) -> int:
    # largest power of two strictly smaller than n, for n >= 2; the mirror of
    # the JVM's Integer.highestOneBit(n - 1)
    return 1 << ((n - 1).bit_length() - 1)
