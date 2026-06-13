package com.paymentservice.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.merchant.Merchant
import com.paymentservice.merchant.MerchantRepository
import com.paymentservice.payment.dto.CreatePaymentRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals

/**
 * End-to-end auth over real HTTP: the X-Api-Key filter authenticates the
 * caller and controllers enforce ownership. A merchant id in the request body
 * is never trusted over the authenticated key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var merchantRepository: MerchantRepository

    // seeded in V1
    private val merchantAId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")
    private val merchantAKey = "test-api-key-123"

    private fun newMerchant(): Merchant =
        merchantRepository.save(Merchant(name = "other", apiKey = "key-${UUID.randomUUID()}"))

    private fun headers(apiKey: String?, idempotencyKey: String? = null) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        apiKey?.let { set(ApiKeyAuthFilter.API_KEY_HEADER, it) }
        idempotencyKey?.let { set("Idempotency-Key", it) }
    }

    private fun createBody(merchantId: UUID) = objectMapper.writeValueAsString(
        CreatePaymentRequest(merchantId = merchantId, amount = 10_000L, currency = "EUR", description = "auth test")
    )

    private fun createPayment(apiKey: String?, bodyMerchantId: UUID): org.springframework.http.ResponseEntity<String> =
        restTemplate.postForEntity(
            "/api/v1/payments",
            HttpEntity(createBody(bodyMerchantId), headers(apiKey, "idem-${UUID.randomUUID()}")),
            String::class.java
        )

    @Test
    fun `missing api key is rejected`() {
        val response = createPayment(apiKey = null, bodyMerchantId = merchantAId)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `invalid api key is rejected`() {
        val response = createPayment(apiKey = "nope", bodyMerchantId = merchantAId)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `inactive merchant key is rejected`() {
        val suspended = merchantRepository.save(
            Merchant(name = "suspended", apiKey = "key-${UUID.randomUUID()}",
                status = com.paymentservice.merchant.MerchantStatus.SUSPENDED)
        )
        val response = createPayment(apiKey = suspended.apiKey, bodyMerchantId = suspended.id)
        assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
    }

    @Test
    fun `valid key creates payment for the authenticated merchant`() {
        val response = createPayment(apiKey = merchantAKey, bodyMerchantId = merchantAId)
        assertEquals(HttpStatus.CREATED, response.statusCode)
    }

    @Test
    fun `body merchant id different from the key is forbidden`() {
        val other = newMerchant()
        val response = createPayment(apiKey = merchantAKey, bodyMerchantId = other.id)
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `merchant cannot read another merchant's payment - reported as not found`() {
        val created = createPayment(apiKey = merchantAKey, bodyMerchantId = merchantAId)
        assertEquals(HttpStatus.CREATED, created.statusCode)
        val paymentId = objectMapper.readTree(created.body).get("id").asText()

        val other = newMerchant()
        val response = restTemplate.exchange(
            "/api/v1/payments/$paymentId",
            HttpMethod.GET,
            HttpEntity<Void>(headers(other.apiKey)),
            String::class.java
        )
        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `merchant reads its own payment`() {
        val created = createPayment(apiKey = merchantAKey, bodyMerchantId = merchantAId)
        val paymentId = objectMapper.readTree(created.body).get("id").asText()

        val response = restTemplate.exchange(
            "/api/v1/payments/$paymentId",
            HttpMethod.GET,
            HttpEntity<Void>(headers(merchantAKey)),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.statusCode)
    }

    @Test
    fun `merchant cannot read another merchant's balance`() {
        val other = newMerchant()
        val response = restTemplate.exchange(
            "/api/v1/merchants/${other.id}/balance",
            HttpMethod.GET,
            HttpEntity<Void>(headers(merchantAKey)),
            String::class.java
        )
        assertEquals(HttpStatus.FORBIDDEN, response.statusCode)
    }

    @Test
    fun `merchant reads its own balance`() {
        val response = restTemplate.exchange(
            "/api/v1/merchants/$merchantAId/balance",
            HttpMethod.GET,
            HttpEntity<Void>(headers(merchantAKey)),
            String::class.java
        )
        assertEquals(HttpStatus.OK, response.statusCode)
    }
}
