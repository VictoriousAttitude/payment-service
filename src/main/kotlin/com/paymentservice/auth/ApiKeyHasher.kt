package com.paymentservice.auth

import java.security.MessageDigest

/**
 * Hashes API keys for storage and lookup. API keys are high-entropy random
 * tokens, so a single SHA-256 is sufficient — unlike low-entropy passwords,
 * they don't need a slow KDF (bcrypt/argon2). A fast deterministic hash also
 * lets us look the merchant up by an indexed hash column in O(1); a salted KDF
 * would force a full-table scan on every authenticated request.
 *
 * Lowercase hex, matching Postgres encode(digest(key,'sha256'),'hex') used to
 * backfill existing rows.
 */
object ApiKeyHasher {

    fun hash(rawKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(rawKey.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
