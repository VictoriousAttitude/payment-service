package com.paymentservice.payment

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * HMAC-SHA256 signing/verification for provider webhooks, with replay
 * protection. The signature is computed over `<timestamp>.<raw body>` and the
 * timestamp travels in the header, so verification rejects a captured-and-
 * replayed callback once it falls outside the tolerance window. Signing the
 * body alone (no timestamp) leaves a valid signature replayable forever.
 *
 * Header format (Stripe-style): `t=<unix_seconds>,v1=<hex_hmac>`.
 */
@Component
class WebhookSigner(
    @Value("\${payment.webhook.secret}") private val secret: String,
    @Value("\${payment.webhook.tolerance-seconds:300}") private val toleranceSeconds: Long = 300
) {

    /**
     * Builds the signed header for [body] at [timestamp]. The provider sends
     * this as X-Webhook-Signature.
     */
    fun signedHeader(body: String, timestamp: Instant = Instant.now()): String {
        val t = timestamp.epochSecond
        return "t=$t,v1=${hmac("$t.$body")}"
    }

    /**
     * Verifies the header against [body]: parses the timestamp + signature,
     * rejects a stale timestamp (replay), then constant-time compares the HMAC.
     * MessageDigest.isEqual is non-short-circuiting, so it doesn't leak how many
     * leading bytes matched.
     */
    fun isValid(body: String, header: String): Boolean {
        val fields = parse(header)
        val t = fields["t"]?.toLongOrNull() ?: return false
        val v1 = fields["v1"] ?: return false

        if (abs(Instant.now().epochSecond - t) > toleranceSeconds) {
            return false
        }

        val expected = hmac("$t.$body")
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            v1.toByteArray(Charsets.UTF_8)
        )
    }

    private fun parse(header: String): Map<String, String> =
        header.split(",")
            .mapNotNull { part ->
                val kv = part.split("=", limit = 2)
                if (kv.size == 2) kv[0].trim() to kv[1].trim() else null
            }
            .toMap()

    private fun hmac(data: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val ALGORITHM = "HmacSHA256"
    }
}
