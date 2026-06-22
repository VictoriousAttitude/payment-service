package com.paymentservice.payment

import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaymentStatusTest {

    @ParameterizedTest(name = "{0} -> {1} should be allowed")
    @MethodSource("validTransitions")
    fun `valid transitions succeed`(from: PaymentStatus, to: PaymentStatus) {
        assertTrue(from.canTransitionTo(to))
        assertEquals(to, from.transitionTo(to))
    }

    @ParameterizedTest(name = "{0} -> {1} should be rejected")
    @MethodSource("invalidTransitions")
    fun `invalid transitions throw`(from: PaymentStatus, to: PaymentStatus) {
        assertFalse(from.canTransitionTo(to))
        assertThrows<InvalidStateTransitionException> {
            from.transitionTo(to)
        }
    }

    @ParameterizedTest(name = "{0} is terminal = {1}")
    @MethodSource("terminalStates")
    fun `terminal states have no outgoing transitions`(status: PaymentStatus, shouldBeTerminal: Boolean) {
        assertEquals(shouldBeTerminal, status.isTerminal)
        if (shouldBeTerminal) {
            // terminal states cannot transition to ANY state
            PaymentStatus.entries.forEach { target ->
                assertFalse(status.canTransitionTo(target))
            }
        }
    }

    companion object {
        @JvmStatic
        fun validTransitions(): Stream<Arguments> = Stream.of(
            Arguments.of(PaymentStatus.CREATED, PaymentStatus.PENDING),
            Arguments.of(PaymentStatus.CREATED, PaymentStatus.FAILED),
            Arguments.of(PaymentStatus.PENDING, PaymentStatus.AUTHORIZED),
            Arguments.of(PaymentStatus.PENDING, PaymentStatus.FAILED),
            // capture: full or partial off an authorization
            Arguments.of(PaymentStatus.AUTHORIZED, PaymentStatus.PARTIALLY_CAPTURED),
            Arguments.of(PaymentStatus.AUTHORIZED, PaymentStatus.CAPTURED),
            Arguments.of(PaymentStatus.AUTHORIZED, PaymentStatus.FAILED),
            // multi-capture self-loop then completion or refund
            Arguments.of(PaymentStatus.PARTIALLY_CAPTURED, PaymentStatus.PARTIALLY_CAPTURED),
            Arguments.of(PaymentStatus.PARTIALLY_CAPTURED, PaymentStatus.CAPTURED),
            Arguments.of(PaymentStatus.PARTIALLY_CAPTURED, PaymentStatus.PARTIALLY_REFUNDED),
            Arguments.of(PaymentStatus.PARTIALLY_CAPTURED, PaymentStatus.REFUNDED),
            // captured: settle or refund (partial/full)
            Arguments.of(PaymentStatus.CAPTURED, PaymentStatus.SETTLED),
            Arguments.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED),
            Arguments.of(PaymentStatus.CAPTURED, PaymentStatus.REFUNDED),
            // partial refund self-loop then completion
            Arguments.of(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.PARTIALLY_REFUNDED),
            Arguments.of(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.REFUNDED)
        )

        @JvmStatic
        fun invalidTransitions(): Stream<Arguments> = Stream.of(
            // backwards transitions
            Arguments.of(PaymentStatus.PENDING, PaymentStatus.CREATED),
            Arguments.of(PaymentStatus.AUTHORIZED, PaymentStatus.PENDING),
            Arguments.of(PaymentStatus.CAPTURED, PaymentStatus.AUTHORIZED),
            Arguments.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_CAPTURED),
            // refund is a one-way phase: no capture after a refund starts
            Arguments.of(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.CAPTURED),
            Arguments.of(PaymentStatus.PARTIALLY_REFUNDED, PaymentStatus.PARTIALLY_CAPTURED),
            // skip transitions
            Arguments.of(PaymentStatus.CREATED, PaymentStatus.CAPTURED),
            Arguments.of(PaymentStatus.PENDING, PaymentStatus.CAPTURED),
            Arguments.of(PaymentStatus.CREATED, PaymentStatus.SETTLED),
            // self transitions that are NOT valid resting states
            Arguments.of(PaymentStatus.AUTHORIZED, PaymentStatus.AUTHORIZED),
            Arguments.of(PaymentStatus.CAPTURED, PaymentStatus.CAPTURED),
            // terminal to anything
            Arguments.of(PaymentStatus.SETTLED, PaymentStatus.REFUNDED),
            Arguments.of(PaymentStatus.FAILED, PaymentStatus.PENDING),
            Arguments.of(PaymentStatus.REFUNDED, PaymentStatus.CAPTURED)
        )

        @JvmStatic
        fun terminalStates(): Stream<Arguments> = Stream.of(
            Arguments.of(PaymentStatus.CREATED, false),
            Arguments.of(PaymentStatus.PENDING, false),
            Arguments.of(PaymentStatus.AUTHORIZED, false),
            Arguments.of(PaymentStatus.PARTIALLY_CAPTURED, false),
            Arguments.of(PaymentStatus.CAPTURED, false),
            Arguments.of(PaymentStatus.PARTIALLY_REFUNDED, false),
            Arguments.of(PaymentStatus.SETTLED, true),
            Arguments.of(PaymentStatus.FAILED, true),
            Arguments.of(PaymentStatus.REFUNDED, true)
        )
    }
}
