package com.paymentservice.payment

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookSignerTest {

    private val signer = WebhookSigner("test-secret")

    @Test
    fun `isValid accepts a fresh matching signature`() {
        val body = """{"amount":1000}"""
        assertTrue(signer.isValid(body, signer.signedHeader(body)))
    }

    @Test
    fun `isValid rejects a tampered body`() {
        val header = signer.signedHeader("""{"amount":1000}""")
        assertFalse(signer.isValid("""{"amount":9999}""", header))
    }

    @Test
    fun `isValid rejects a garbage signature`() {
        assertFalse(signer.isValid("""{"amount":1000}""", "deadbeef"))
    }

    @Test
    fun `isValid rejects a header missing the timestamp`() {
        assertFalse(signer.isValid("""{"amount":1000}""", "v1=abc123"))
    }

    @Test
    fun `different secrets produce non-matching signatures`() {
        val other = WebhookSigner("other-secret")
        val body = """{"amount":1000}"""
        assertFalse(signer.isValid(body, other.signedHeader(body)))
    }

    @Test
    fun `isValid rejects a stale timestamp - replay protection`() {
        val body = """{"amount":1000}"""
        // correctly signed, but the timestamp is well outside the 300s window
        val stale = signer.signedHeader(body, Instant.now().minusSeconds(3600))
        assertFalse(signer.isValid(body, stale))
    }

    @Test
    fun `isValid rejects a far-future timestamp`() {
        val body = """{"amount":1000}"""
        val future = signer.signedHeader(body, Instant.now().plusSeconds(3600))
        assertFalse(signer.isValid(body, future))
    }
}
