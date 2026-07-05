"""Golden vectors shared verbatim with the JVM MerkleTreeTest: if either
implementation drifts from RFC 6962, its own golden test fails before any
cross-language comparison does."""

from recon.domain import merkle


def _leaves(n: int) -> list[bytes]:
    return [f"l{i}".encode() for i in range(n)]


def test_empty_tree_root_is_sha256_of_empty_string() -> None:
    assert (
        merkle.root_hex([])
        == "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    )


def test_single_leaf_root_equals_its_leaf_hash() -> None:
    expected = "b41c19e571c01e62257677386dd8a91808b0450ca5b41d1e61faec81bd2fd3dc"
    assert merkle.root_hex(_leaves(1)) == expected
    assert merkle.leaf_hash(b"l0").hex() == expected


def test_golden_vectors_for_balanced_and_unbalanced_trees() -> None:
    assert (
        merkle.root_hex(_leaves(2))
        == "f0403eee8055c3dde05912e05d92c561655db532a2ac817cbff1309190af8419"
    )
    assert (
        merkle.root_hex(_leaves(3))
        == "695e465949dc418a0d75a0223eadf9ec122104cc546184fe8cc1af9b73799301"
    )
    assert (
        merkle.root_hex(_leaves(5))
        == "af34225b92c38a609d8b9fab540175cf48bb6acacbabf74b6a7402e17f3140f3"
    )
    assert (
        merkle.root_hex(_leaves(7))
        == "e6b07a82b3335f5dbe11853144f3dff8e1b3701ef4312e9f50f7690faf407154"
    )


def test_domain_separation_blocks_the_concatenation_second_preimage() -> None:
    assert (
        merkle.root_hex([b"l0l1"])
        == "9648bde7090e3aa2d7be029f8943ada33d02b1cdd24ed26a3e8337805e21aafd"
    )
    assert merkle.root_hex([b"l0l1"]) != merkle.root_hex(_leaves(2))
    concatenated_leaf = merkle.leaf_hash(b"l0" + b"l1")
    two_leaf_node = merkle.node_hash(merkle.leaf_hash(b"l0"), merkle.leaf_hash(b"l1"))
    assert concatenated_leaf != two_leaf_node


def test_root_commits_to_leaf_order_and_content() -> None:
    assert merkle.root_hex(_leaves(2)) != merkle.root_hex([b"l1", b"l0"])
    assert merkle.root_hex(_leaves(2)) != merkle.root_hex([b"l0", b"lX"])


def test_two_leaf_root_is_node_hash_of_the_two_leaf_hashes() -> None:
    expected = merkle.node_hash(merkle.leaf_hash(b"l0"), merkle.leaf_hash(b"l1"))
    assert merkle.root_hex(_leaves(2)) == expected.hex()
