package com.paymentservice.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.paymentservice.config.ErrorResponse
import com.paymentservice.merchant.MerchantRepository
import com.paymentservice.merchant.MerchantStatus
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates merchant API requests by the X-Api-Key header and pins the
 * caller's merchant id onto the request. Downstream controllers read that id
 * to enforce ownership — the merchant id in a request body is never trusted.
 *
 * Only guards merchant-facing endpoints. The provider webhook authenticates
 * with an HMAC signature (different trust domain), and docs/health are public,
 * so those are skipped.
 *
 * Runs before the dispatcher, so it writes its own 401 body rather than
 * delegating to @RestControllerAdvice.
 */
@Component
class ApiKeyAuthFilter(
    private val merchantRepository: MerchantRepository,
    private val objectMapper: ObjectMapper
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return PROTECTED_PREFIXES.none { path.startsWith(it) }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val apiKey = request.getHeader(API_KEY_HEADER)
        if (apiKey.isNullOrBlank()) {
            reject(response, "API_KEY_MISSING", "Missing $API_KEY_HEADER header")
            return
        }

        val merchant = merchantRepository.findByApiKey(apiKey)
        if (merchant == null || merchant.status != MerchantStatus.ACTIVE) {
            // Same message for unknown and inactive keys: don't reveal which.
            reject(response, "API_KEY_INVALID", "Invalid or inactive API key")
            return
        }

        request.setAttribute(MERCHANT_ID_ATTRIBUTE, merchant.id)
        filterChain.doFilter(request, response)
    }

    private fun reject(response: HttpServletResponse, error: String, message: String) {
        response.status = HttpStatus.UNAUTHORIZED.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write(objectMapper.writeValueAsString(ErrorResponse(error, message)))
    }

    companion object {
        const val API_KEY_HEADER = "X-Api-Key"
        const val MERCHANT_ID_ATTRIBUTE = "authenticatedMerchantId"
        private val PROTECTED_PREFIXES = listOf("/api/v1/payments", "/api/v1/merchants")
    }
}
