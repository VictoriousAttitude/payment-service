"""Golden vectors shared verbatim with the JVM CanonicalLeafCodecTest and
AnchorChainTest, plus verification semantics for every failure class. Only
domain imports: this suite runs under the mutation gate."""

from dataclasses import replace

from recon.domain import merkle
from recon.domain.anchor import (
    GENESIS,
    AnchorRecord,
    FailureReason,
    LeafRecord,
    canonical_leaf,
    canonical_string,
    chain_next,
    verify,
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

ROOT_1 = "3dc4a3e169aba383f31aa41cff239a715cb04c7f5fa114f740e9581fefbad646"
ROOT_2 = "f0403eee8055c3dde05912e05d92c561655db532a2ac817cbff1309190af8419"


def test_golden_canonical_string_for_a_payment_entry() -> None:
    assert canonical_string(PAYMENT) == (
        "00000000-0000-0000-0000-000000000001|"
        "00000000-0000-0000-0000-000000000002|"
        "00000000-0000-0000-0000-000000000002|MERCHANT|"
        "00000000-0000-0000-0000-000000000003|"
        "CREDIT|9800|EUR|1767323045123456|1capture"
    )


def test_golden_canonical_string_for_a_treasury_entry() -> None:
    assert canonical_string(TREASURY) == (
        "00000000-0000-0000-0000-000000000005||"
        "00000000-0000-0000-0000-000000000004|MERCHANT_PAYABLE|"
        "00000000-0000-0000-0000-000000000003|DEBIT|5000|USD|"
        "1767323045123456|0"
    )


def test_golden_leaf_hashes_and_epoch_root_shared_with_the_jvm() -> None:
    payment = canonical_leaf(PAYMENT)
    treasury = canonical_leaf(TREASURY)
    assert (
        merkle.leaf_hash(payment).hex()
        == "f8ef1653776f813dce4f2aa8ea0d4e684abebbc8122b643c03b21960078b894e"
    )
    assert (
        merkle.leaf_hash(treasury).hex()
        == "9e722ceb7e380146739b5b23e66daf0475656fe82bae3a374aa5be1f4b1adf6a"
    )
    assert merkle.root_hex([payment, treasury]) == ROOT_1


def test_null_and_empty_description_encode_differently() -> None:
    empty = replace(TREASURY, description="")
    assert canonical_string(empty).rsplit("|", 1)[1] == "1"
    assert canonical_string(TREASURY).rsplit("|", 1)[1] == "0"
    assert canonical_string(empty) != canonical_string(TREASURY)


def test_description_with_pipes_stays_injective() -> None:
    tricky = replace(PAYMENT, description="a|b|c")
    assert canonical_string(tricky).endswith("|1767323045123456|1a|b|c")
    assert canonical_string(tricky) != canonical_string(PAYMENT)


def test_golden_chain_vectors_from_genesis() -> None:
    chain_1 = chain_next(GENESIS, ROOT_1, 1)
    assert chain_1 == (
        "79c6ae64ef06f450119bcfdb6f17e45a8672c0bacbfdea005fdcf746da9d5bc1"
    )
    assert chain_next(chain_1, ROOT_2, 2) == (
        "83d6987ceeaa7d213f146ad02ce783d3774a49dd5e1d3f9c15e045d2b8d0b178"
    )


def test_chain_hash_commits_to_epoch_root_and_predecessor() -> None:
    base = chain_next(GENESIS, ROOT_1, 1)
    assert base != chain_next(GENESIS, ROOT_1, 2)
    assert base != chain_next(GENESIS, ROOT_2, 1)
    assert base != chain_next(base, ROOT_1, 1)


def test_genesis_is_sixty_four_zero_characters() -> None:
    assert GENESIS == "0" * 64


def _leaf(index: int, entry_id: str, amount: int = 100) -> LeafRecord:
    return replace(PAYMENT, leaf_index=index, entry_id=entry_id, amount=amount)


def _seal(epoch: int, leaves: list[LeafRecord], previous: str) -> AnchorRecord:
    root = merkle.root_hex([canonical_leaf(leaf) for leaf in leaves])
    return AnchorRecord(epoch, root, chain_next(previous, root, epoch), len(leaves))


def _chain(
    leaf_sets: list[list[LeafRecord]],
) -> tuple[list[AnchorRecord], dict[int, list[LeafRecord]]]:
    anchors: list[AnchorRecord] = []
    previous = GENESIS
    for epoch, leaves in enumerate(leaf_sets, start=1):
        anchor = _seal(epoch, leaves, previous)
        anchors.append(anchor)
        previous = anchor.chain_hash
    leaves_by_epoch = {
        anchor.epoch: leaves
        for anchor, leaves in zip(anchors, leaf_sets, strict=True)
    }
    return anchors, leaves_by_epoch


def test_untampered_chain_verifies_clean_regardless_of_input_order() -> None:
    anchors, leaves_by_epoch = _chain(
        [[_leaf(0, "e1"), _leaf(1, "e2")], [_leaf(0, "e3")], [_leaf(0, "e4")]]
    )
    assert verify(anchors, leaves_by_epoch) == ()
    assert verify(list(reversed(anchors)), leaves_by_epoch) == ()


def test_tampered_leaf_amount_is_a_root_mismatch_at_its_epoch_only() -> None:
    anchors, leaves_by_epoch = _chain([[_leaf(0, "e1")], [_leaf(0, "e2")]])
    tampered_leaf = replace(leaves_by_epoch[2][0], amount=101)
    leaves_by_epoch[2] = [tampered_leaf]

    failures = verify(anchors, leaves_by_epoch)

    assert [f.reason for f in failures] == [FailureReason.ROOT_MISMATCH]
    assert failures[0].epoch == 2
    assert failures[0].expected == anchors[1].root
    assert failures[0].actual == merkle.root_hex([canonical_leaf(tampered_leaf)])


def test_tampered_chain_hash_flags_itself_and_successor_without_cascade() -> None:
    anchors, leaves_by_epoch = _chain(
        [[_leaf(0, "e1")], [_leaf(0, "e2")], [_leaf(0, "e3")]]
    )
    tampered = [replace(anchors[0], chain_hash=GENESIS), anchors[1], anchors[2]]

    failures = verify(tampered, leaves_by_epoch)

    assert {f.epoch for f in failures} == {1, 2}
    assert all(f.reason is FailureReason.CHAIN_MISMATCH for f in failures)
    at_one = next(f for f in failures if f.epoch == 1)
    assert at_one.expected == anchors[0].chain_hash
    assert at_one.actual == GENESIS


def test_missing_epoch_is_an_epoch_gap() -> None:
    anchors, leaves_by_epoch = _chain(
        [[_leaf(0, "e1")], [_leaf(0, "e2")], [_leaf(0, "e3")]]
    )
    del leaves_by_epoch[2]

    failures = verify([anchors[0], anchors[2]], leaves_by_epoch)

    gaps = [f for f in failures if f.reason is FailureReason.EPOCH_GAP]
    assert [(f.epoch, f.expected, f.actual) for f in gaps] == [(3, "2", "3")]


def test_absent_leaves_are_a_leaf_count_mismatch() -> None:
    anchors, leaves_by_epoch = _chain([[_leaf(0, "e1"), _leaf(1, "e2")]])
    del leaves_by_epoch[1]

    failures = verify(anchors, leaves_by_epoch)

    counts = [f for f in failures if f.reason is FailureReason.LEAF_COUNT_MISMATCH]
    assert [(f.epoch, f.expected, f.actual) for f in counts] == [(1, "2", "0")]


def test_non_contiguous_leaf_indexes_are_a_leaf_index_gap() -> None:
    leaves = [_leaf(0, "e1"), _leaf(1, "e2")]
    anchors, _ = _chain([leaves])
    shifted = [leaves[0], replace(leaves[1], leaf_index=2)]

    failures = verify(anchors, {1: shifted})

    gaps = [f for f in failures if f.reason is FailureReason.LEAF_INDEX_GAP]
    assert [(f.epoch, f.expected, f.actual) for f in gaps] == [(1, "0,1", "0,2")]
