package com.paymentservice.settlement

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Platform-level ops surface for acquirer settlement files. Like
 * /api/v1/reconciliation it sits outside the API-key filter (which establishes
 * merchant identity for /api/v1/payments and /api/v1/merchants only): a
 * settlement file spans all merchants, so no single merchant owns it.
 * Restrict at the network layer in prod. Real acquirer delivery is SFTP/AS2,
 * not REST; documented as a production gap.
 */
@RestController
@RequestMapping("/api/v1/settlement-files")
class SettlementFileController(
    private val ingestionService: SettlementFileIngestionService,
    private val fileRepository: SettlementFileRepository,
    private val discrepancyRepository: SettlementFileDiscrepancyRepository
) {

    /**
     * Raw CSV body, filename as a query param (single fixed-format file, so
     * multipart adds surface for nothing). 201 with the fresh verdict, 200
     * with the original verdict for a byte-identical re-upload, 413 over the
     * size cap.
     */
    @PostMapping(consumes = ["text/csv", MediaType.TEXT_PLAIN_VALUE])
    fun upload(
        @RequestParam filename: String,
        @RequestBody body: String
    ): ResponseEntity<SettlementFileSummaryResponse> {
        val outcome = ingestionService.ingest(filename, body)
        val status = if (outcome.created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(SettlementFileSummaryResponse.from(outcome.file))
    }

    @GetMapping
    fun list(): List<SettlementFileSummaryResponse> =
        fileRepository.findAllByOrderByCreatedAtDesc().map(SettlementFileSummaryResponse::from)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): SettlementFileDetailResponse {
        val file = fileRepository.findById(id).orElseThrow { SettlementFileNotFoundException(id) }
        return SettlementFileDetailResponse(
            file = SettlementFileSummaryResponse.from(file),
            discrepancies = discrepancyRepository.findByFileIdOrderByReference(id)
                .map(FileDiscrepancyResponse::from)
        )
    }
}
