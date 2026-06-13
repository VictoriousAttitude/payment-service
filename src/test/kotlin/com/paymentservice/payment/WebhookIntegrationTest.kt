package com.paymentservice.payment

import com.fasterxml.jackson.databind.ObjectMapper
import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.payment.dto.CreatePaymentRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Exercises the signed webhook path end-to-end over real HTTP:
 * HMAC verification in the controller + idempotent handling in the service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
class WebhookIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var webhookSigner: WebhookSigner
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var paymentService: PaymentService

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
    private val url = "/api/v1/webhooks/provider-callback"

    private fun newPendingPayment(): Transaction {
        val request = CreatePaymentRequest(
            merchantId = merchantId,
            amount = 10_000L,
            currency = "EUR",
            description = "webhook test"
        )
        return paymentService.createPayment(request, "webhook-${UUID.randomUUID()}").transaction
    }

    private fun post(body: String, signature: String?) =
        restTemplate.postForEntity(
            url,
            HttpEntity(body, HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                signature?.let { set("X-Webhook-Signature", it) }
            }),
            Void::class.java
        )

    private fun callbackBody(txnId: UUID, authorized: Boolean, ref: String?) =
        objectMapper.writeValueAsString(ProviderCallbackRequest(txnId, authorized, ref))

    @Test
    fun `valid signature authorizes the payment`() {
        val txn = newPendingPayment()
        val body = callbackBody(txn.id, authorized = true, ref = "prov_ok")

        val response = post(body, webhookSigner.sign(body))

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(PaymentStatus.AUTHORIZED, paymentService.getPayment(txn.id).status)
    }

    @Test
    fun `invalid signature is rejected and payment untouched`() {
        val txn = newPendingPayment()
        val body = callbackBody(txn.id, authorized = true, ref = "prov_x")

        val response = post(body, "deadbeefdeadbeef")

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertEquals(PaymentStatus.PENDING, paymentService.getPayment(txn.id).status)
    }

    @Test
    fun `missing signature is rejected`() {
        val txn = newPendingPayment()
        val body = callbackBody(txn.id, authorized = true, ref = "prov_x")

        val response = post(body, signature = null)

        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
        assertEquals(PaymentStatus.PENDING, paymentService.getPayment(txn.id).status)
    }

    @Test
    fun `duplicate callback is idempotent - second is acked, no error`() {
        val txn = newPendingPayment()
        val body = callbackBody(txn.id, authorized = true, ref = "prov_dup")
        val signature = webhookSigner.sign(body)

        assertEquals(HttpStatus.OK, post(body, signature).statusCode)
        // provider retried the exact same callback
        assertEquals(HttpStatus.OK, post(body, signature).statusCode)

        assertEquals(PaymentStatus.AUTHORIZED, paymentService.getPayment(txn.id).status)
    }

    @Test
    fun `late callback after capture is acked and ignored - no retry storm`() {
        val txn = newPendingPayment()
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "prov_cap")
        paymentService.capturePayment(txn.id)

        // provider re-delivers the authorization long after we captured
        val body = callbackBody(txn.id, authorized = true, ref = "prov_cap")
        val response = post(body, webhookSigner.sign(body))

        // 200 (acked) so the provider stops retrying; state unchanged
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(PaymentStatus.CAPTURED, paymentService.getPayment(txn.id).status)
    }
}
