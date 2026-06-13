package com.paymentservice.payment

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HMAC-SHA256 signing/verification for provider webhooks.
 * The shared secret authenticates the caller: without it, anyone reaching the
 * webhook URL could authorize arbitrary payments. The signature is computed
 * over the raw request body so re-serialization differences can't break it.
 */
@Component
class WebhookSigner(
    @Value("\${payment.webhook.secret}") private val secret: String
) {

    fun sign(body: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), ALGORITHM))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Constant-time comparison. MessageDigest.isEqual is non-short-circuiting
     * (JDK 6u17+), so verification time doesn't leak how many leading bytes
     * matched — closes the timing side channel on signature forgery.
     */
    fun isValid(body: String, signature: String): Boolean {
        val expected = sign(body)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            signature.toByteArray(Charsets.UTF_8)
        )
    }

    companion object {
        private const val ALGORITHM = "HmacSHA256"
    }
}
