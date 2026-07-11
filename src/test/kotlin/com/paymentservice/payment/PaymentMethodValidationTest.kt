package com.paymentservice.payment

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.auth.ApiKeyAuthFilter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Boundary validation of paymentMethod, which is persisted into a jsonb
 * column. Found by the model-based conformance suite (mbt/): a bare non-JSON
 * token like "card" passed bean validation and failed only at INSERT — an
 * unhandled DataIntegrityViolationException surfacing as a 500 for what is a
 * malformed request. Must be over real HTTP: @Valid fires in the controller.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class PaymentMethodValidationTest {

    @Autowired lateinit var restTemplate: TestRestTemplate

    // seeded in V1
    private val merchantId = "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11"
    private val apiKey = "test-api-key-123"

    private fun create(paymentMethodJson: String?): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set(ApiKeyAuthFilter.API_KEY_HEADER, apiKey)
            set("Idempotency-Key", "pm-${UUID.randomUUID()}")
        }
        val paymentMethodField =
            if (paymentMethodJson == null) "" else ""","paymentMethod": $paymentMethodJson"""
        val body = """
            {"merchantId": "$merchantId", "amount": 1000, "currency": "EUR"$paymentMethodField}
        """.trimIndent()
        return restTemplate.postForEntity(
            "/api/v1/payments", HttpEntity(body, headers), String::class.java
        )
    }

    @Test
    fun `bare non-json token is a 400, not a 500 at insert`() {
        // field value "card" -> the string `card`, which is not a JSON document
        val response = create("\"card\"")
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertTrue(response.body!!.contains("VALIDATION_ERROR"))
    }

    @Test
    fun `blank payment method is a 400`() {
        assertEquals(HttpStatus.BAD_REQUEST, create("\"  \"").statusCode)
    }

    @Test
    fun `json object payment method is accepted`() {
        val response = create("\"{\\\"type\\\": \\\"card\\\"}\"")
        assertEquals(HttpStatus.CREATED, response.statusCode)
    }

    @Test
    fun `trailing garbage after a json document is a 400`() {
        val response = create("\"{} garbage\"")
        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
    }

    @Test
    fun `omitted payment method is still accepted`() {
        val response = create(null)
        assertEquals(HttpStatus.CREATED, response.statusCode)
    }
}
