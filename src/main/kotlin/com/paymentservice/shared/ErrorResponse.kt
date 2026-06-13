package com.paymentservice.shared

/**
 * Shared kernel: the JSON error body every module's HTTP boundary renders.
 * Lives here so the auth filter and the global exception handler agree on the
 * wire shape without either depending on the other's module.
 */
data class ErrorResponse(
    val error: String,
    val message: String,
    val details: Map<String, String>? = null
)
