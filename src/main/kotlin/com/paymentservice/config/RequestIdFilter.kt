package com.paymentservice.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Puts a correlation id into the MDC for the duration of each request so every
 * log line it produces is traceable. Honors an inbound X-Request-Id (set by an
 * upstream gateway) and otherwise mints one, echoing it back on the response.
 *
 * Highest precedence so the id is present before any other filter logs —
 * including auth rejections.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val requestId = request.getHeader(REQUEST_ID_HEADER)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()

        MDC.put(REQUEST_ID_KEY, requestId)
        response.setHeader(REQUEST_ID_HEADER, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(REQUEST_ID_KEY)
        }
    }

    companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val REQUEST_ID_KEY = "requestId"
    }
}
