package com.paymentservice.ledger

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Golden vectors computed by an independent Python hashlib implementation of
 * the RFC 6962 MTH definition (see recon anchor verifier tests, which pin the
 * identical constants). Vector leaves are the UTF-8 strings "l0".."l6"; the
 * odd sizes (3, 5, 7) exercise the unbalanced split at the largest power of
 * two smaller than n. Mutation tested.
 */
class MerkleTreeTest {

    private fun leaves(n: Int): List<ByteArray> =
        (0 until n).map { "l$it".toByteArray(Charsets.UTF_8) }

    @Test
    fun `empty tree root is sha256 of empty string`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            MerkleTree.rootHex(emptyList())
        )
    }

    @Test
    fun `single leaf root equals its leaf hash`() {
        val expected = "b41c19e571c01e62257677386dd8a91808b0450ca5b41d1e61faec81bd2fd3dc"
        assertEquals(expected, MerkleTree.rootHex(leaves(1)))
        assertEquals(expected, MerkleTree.toHex(MerkleTree.leafHash("l0".toByteArray())))
    }

    @Test
    fun `golden vectors for balanced and unbalanced trees`() {
        assertEquals("f0403eee8055c3dde05912e05d92c561655db532a2ac817cbff1309190af8419", MerkleTree.rootHex(leaves(2)))
        assertEquals("695e465949dc418a0d75a0223eadf9ec122104cc546184fe8cc1af9b73799301", MerkleTree.rootHex(leaves(3)))
        assertEquals("af34225b92c38a609d8b9fab540175cf48bb6acacbabf74b6a7402e17f3140f3", MerkleTree.rootHex(leaves(5)))
        assertEquals("e6b07a82b3335f5dbe11853144f3dff8e1b3701ef4312e9f50f7690faf407154", MerkleTree.rootHex(leaves(7)))
    }

    @Test
    fun `domain separation blocks the concatenation second preimage`() {
        // without 0x00/0x01 prefixes, the single leaf "l0l1" would collide
        // with the two-leaf tree ["l0", "l1"]
        assertEquals(
            "9648bde7090e3aa2d7be029f8943ada33d02b1cdd24ed26a3e8337805e21aafd",
            MerkleTree.rootHex(listOf("l0l1".toByteArray()))
        )
        assertNotEquals(
            MerkleTree.rootHex(listOf("l0l1".toByteArray())),
            MerkleTree.rootHex(leaves(2))
        )
        val a = "l0".toByteArray()
        val b = "l1".toByteArray()
        assertNotEquals(
            MerkleTree.toHex(MerkleTree.nodeHash(MerkleTree.leafHash(a), MerkleTree.leafHash(b))),
            MerkleTree.toHex(MerkleTree.leafHash(a + b))
        )
    }

    @Test
    fun `root commits to leaf order and content`() {
        val swapped = listOf("l1".toByteArray(), "l0".toByteArray())
        assertNotEquals(MerkleTree.rootHex(leaves(2)), MerkleTree.rootHex(swapped))
        val mutated = listOf("l0".toByteArray(), "lX".toByteArray())
        assertNotEquals(MerkleTree.rootHex(leaves(2)), MerkleTree.rootHex(mutated))
    }

    @Test
    fun `two leaf root is node hash of the two leaf hashes`() {
        val expected = MerkleTree.nodeHash(
            MerkleTree.leafHash("l0".toByteArray()),
            MerkleTree.leafHash("l1".toByteArray())
        )
        assertEquals(MerkleTree.toHex(expected), MerkleTree.rootHex(leaves(2)))
    }
}
