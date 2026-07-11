package com.paymentservice.shared

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * Validates that a string is a parseable JSON document. Guards fields that are
 * persisted into jsonb columns: without this, any non-JSON value passes bean
 * validation and only fails at INSERT, surfacing as an unhandled
 * DataIntegrityViolationException — a 500 for what is a malformed request
 * (found by the model-based conformance suite on `paymentMethod`).
 *
 * A null value passes so optionality stays owned by the field's own nullability.
 * Blank is rejected here: Postgres rejects ''::jsonb, and there is no @NotBlank
 * on an optional field to own that case. FAIL_ON_TRAILING_TOKENS keeps the
 * check aligned with the Postgres json parser ("{}garbage" must not pass).
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [JsonDocumentValidator::class])
annotation class JsonDocument(
    val message: String = "Must be a valid JSON document",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

class JsonDocumentValidator : ConstraintValidator<JsonDocument, String> {

    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean {
        if (value == null) return true
        return value.isNotBlank() && parses(value)
    }

    private fun parses(value: String): Boolean = try {
        MAPPER.readTree(value)
        true
    } catch (_: com.fasterxml.jackson.core.JacksonException) {
        false
    }

    companion object {
        private val MAPPER: ObjectMapper =
            ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    }
}
