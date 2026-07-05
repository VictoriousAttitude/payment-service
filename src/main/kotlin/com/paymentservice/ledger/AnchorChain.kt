package com.paymentservice.ledger

import java.security.MessageDigest

/**
 * Hash chain over epoch anchors (Certificate Transparency's signed-tree-head
 * lineage, minus the signature). Chaining means an attacker who rewrites one
 * epoch's entries must recompute every subsequent chain hash, so publishing
 * any single recent chain hash out of band pins the entire history.
 *
 * Cross-language contract with the Python verifier:
 *   chainHash(n) = sha256Hex(utf8("{epoch}|{rootHex}|{prevChainHashHex}"))
 * with the genesis predecessor being 64 zero characters.
 */
object AnchorChain {

    const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

    fun next(previousChainHash: String, rootHex: String, epoch: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$epoch|$rootHex|$previousChainHash".toByteArray(Charsets.UTF_8))
        return MerkleTree.toHex(bytes)
    }
}
