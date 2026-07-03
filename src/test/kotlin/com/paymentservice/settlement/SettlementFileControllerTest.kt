package com.paymentservice.settlement

import com.fasterxml.jackson.databind.ObjectMapper
import com.paymentservice.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HTTP surface of settlement-file ingestion. Files reference phantom
 * "sfctl-" ids (absent from the ledger), so every upload is PROCESSED with a
 * MISSING_IN_LEDGER discrepancy at each of its own references; assertions on
 * discrepancies filter by that prefix because the shared suite DB pollutes
 * every verdict with unrelated ledger-side duplicates (see the ingestion
 * service test).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class SettlementFileControllerTest {

    @Autowired lateinit var restTemplate: TestRestTemplate
    @Autowired lateinit var objectMapper: ObjectMapper

    private val header = "reference,reporting_category,currency,gross,fee"

    private fun csvHeaders() = HttpHeaders().apply {
        contentType = MediaType.parseMediaType("text/csv")
    }

    private fun post(filename: String, body: String) = restTemplate.postForEntity(
        "/api/v1/settlement-files?filename=$filename",
        HttpEntity(body, csvHeaders()),
        String::class.java
    )

    @Test
    fun `upload returns 201 with the persisted verdict`() {
        val ref = "sfctl-${UUID.randomUUID()}"

        val response = post("upload.csv", "$header\n$ref,charge,EUR,100.00,2.00\n")

        assertEquals(HttpStatus.CREATED, response.statusCode)
        val json = objectMapper.readTree(response.body)
        assertEquals("PROCESSED", json.get("status").asText())
        assertEquals("upload.csv", json.get("filename").asText())
        assertEquals(1, json.get("lineCount").asInt())

        val list = restTemplate.getForEntity("/api/v1/settlement-files", String::class.java)
        assertEquals(HttpStatus.OK, list.statusCode)
        val ids = objectMapper.readTree(list.body).map { it.get("id").asText() }
        assertTrue(json.get("id").asText() in ids)
    }

    @Test
    fun `re-uploading identical bytes returns 200 with the original file`() {
        val body = "$header\nsfctl-${UUID.randomUUID()},charge,EUR,100.00,2.00\n"

        val first = post("original.csv", body)
        val second = post("renamed.csv", body)

        assertEquals(HttpStatus.CREATED, first.statusCode)
        assertEquals(HttpStatus.OK, second.statusCode)
        val firstJson = objectMapper.readTree(first.body)
        val secondJson = objectMapper.readTree(second.body)
        assertEquals(firstJson.get("id").asText(), secondJson.get("id").asText())
        assertEquals("original.csv", secondJson.get("filename").asText())
    }

    @Test
    fun `an upload over the size cap is rejected with 413`() {
        val oversized = "$header\n" + "x".repeat(1_100_000)

        val response = post("huge.csv", oversized)

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.statusCode)
        assertEquals(
            "SETTLEMENT_FILE_TOO_LARGE",
            objectMapper.readTree(response.body).get("error").asText()
        )
    }

    @Test
    fun `file detail returns discrepancies ordered by reference`() {
        val prefix = "sfctl-${UUID.randomUUID()}"
        val body = "$header\n$prefix-b,charge,EUR,100.00,2.00\n$prefix-a,charge,EUR,50.00,1.00\n"
        val id = objectMapper.readTree(post("detail.csv", body).body).get("id").asText()

        val response = restTemplate.getForEntity("/api/v1/settlement-files/$id", String::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        val json = objectMapper.readTree(response.body)
        assertEquals(id, json.get("file").get("id").asText())
        val mine = json.get("discrepancies")
            .filter { it.get("reference").asText().startsWith(prefix) }
        assertEquals(2, mine.size)
        assertEquals("$prefix-a", mine[0].get("reference").asText())
        assertEquals("$prefix-b", mine[1].get("reference").asText())
        assertTrue(mine.all { it.get("type").asText() == "MISSING_IN_LEDGER" })
    }

    @Test
    fun `settlement extract endpoint serves the recon movement csv`() {
        val response = restTemplate.getForEntity("/api/v1/settlement-extract", String::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(
            response.body!!.startsWith("reference,kind,gross_minor,fee_minor,currency,occurred_at\n")
        )
    }

    @Test
    fun `unknown file id is a 404`() {
        val response = restTemplate.getForEntity(
            "/api/v1/settlement-files/${UUID.randomUUID()}",
            String::class.java
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals(
            "SETTLEMENT_FILE_NOT_FOUND",
            objectMapper.readTree(response.body).get("error").asText()
        )
    }
}
