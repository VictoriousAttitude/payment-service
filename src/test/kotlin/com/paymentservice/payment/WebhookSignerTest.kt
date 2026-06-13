package com.paymentservice.payment

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebhookSignerTest {

    private val signer = WebhookSigner("test-secret")

    @Test
    fun `sign is deterministic for the same body`() {
        val body = """{"transactionId":"x","authorized":true}"""
        assertEquals(signer.sign(body), signer.sign(body))
    }

    @Test
    fun `isValid accepts a matching signature`() {
        val body = """{"amount":1000}"""
        assertTrue(signer.isValid(body, signer.sign(body)))
    }

    @Test
    fun `isValid rejects a tampered body`() {
        val signature = signer.sign("""{"amount":1000}""")
        assertFalse(signer.isValid("""{"amount":9999}""", signature))
    }

    @Test
    fun `isValid rejects a garbage signature`() {
        assertFalse(signer.isValid("""{"amount":1000}""", "deadbeef"))
    }

    @Test
    fun `different secrets produce different signatures`() {
        val other = WebhookSigner("other-secret")
        val body = """{"amount":1000}"""
        assertFalse(signer.isValid(body, other.sign(body)))
    }
}
