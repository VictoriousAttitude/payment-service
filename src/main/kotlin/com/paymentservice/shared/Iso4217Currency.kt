package com.paymentservice.shared

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

/**
 * Validates that a string is a supported ISO 4217 currency. A bare length check
 * passes "ZZZ"; this rejects any code the registry does not back with a fundable
 * minor unit, at the request boundary, before it can reach the ledger.
 *
 * A null or blank value passes so emptiness stays owned by @NotBlank and the
 * caller does not get two errors for one missing field.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [Iso4217CurrencyValidator::class])
annotation class Iso4217Currency(
    val message: String = "Currency must be a supported ISO 4217 code",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

class Iso4217CurrencyValidator : ConstraintValidator<Iso4217Currency, String> {
    override fun isValid(value: String?, context: ConstraintValidatorContext?): Boolean {
        if (value.isNullOrBlank()) return true
        return MonetaryCurrency.isSupported(value)
    }
}
