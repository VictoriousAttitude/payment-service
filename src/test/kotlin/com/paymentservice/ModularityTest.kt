package com.paymentservice

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

/**
 * Enforces the module boundaries at build time. ApplicationModules derives one
 * module per direct sub-package of the application package; verify() fails the
 * build on a dependency cycle between modules or on a module reaching into
 * another module's internal (nested) types. This is the structural guardrail
 * that keeps the architecture from silently rotting back into a tangle.
 */
class ModularityTest {

    private val modules = ApplicationModules.of(PaymentServiceApplication::class.java)

    @Test
    fun `modules are acyclic and respect each other's boundaries`() {
        modules.verify()
    }
}
