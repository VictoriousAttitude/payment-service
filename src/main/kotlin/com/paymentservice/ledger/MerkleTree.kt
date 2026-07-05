package com.paymentservice.ledger

import java.security.MessageDigest

/**
 * RFC 6962 (Certificate Transparency) Merkle tree hashing over SHA-256.
 *
 * Domain separation is mandatory: leaves are hashed as SHA-256(0x00 || leaf)
 * and interior nodes as SHA-256(0x01 || left || right). Without the distinct
 * prefixes an attacker could present a concatenation of two leaves as a single
 * leaf (or a node as a leaf) and forge a second preimage for the root.
 *
 * Unbalanced trees follow the RFC 6962 MTH definition: a tree of n > 1 leaves
 * splits at k, the largest power of two smaller than n. The hash of the empty
 * tree is SHA-256 of the empty string. This exact construction is what the
 * independent Python verifier reimplements; the shared golden vectors in
 * [MerkleTreeTest] pin both sides to the RFC.
 */
object MerkleTree {

    private const val LEAF_PREFIX: Byte = 0x00
    private const val NODE_PREFIX: Byte = 0x01

    fun leafHash(leaf: ByteArray): ByteArray = sha256(byteArrayOf(LEAF_PREFIX), leaf)

    fun nodeHash(left: ByteArray, right: ByteArray): ByteArray =
        sha256(byteArrayOf(NODE_PREFIX), left, right)

    fun root(leaves: List<ByteArray>): ByteArray = when (leaves.size) {
        0 -> sha256()
        1 -> leafHash(leaves[0])
        else -> {
            // largest power of two strictly smaller than n (RFC 6962 section 2.1)
            val split = Integer.highestOneBit(leaves.size - 1)
            nodeHash(root(leaves.subList(0, split)), root(leaves.subList(split, leaves.size)))
        }
    }

    fun rootHex(leaves: List<ByteArray>): String = toHex(root(leaves))

    fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest()
    }
}
