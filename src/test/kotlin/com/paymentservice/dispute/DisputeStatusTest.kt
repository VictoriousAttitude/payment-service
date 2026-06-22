package com.paymentservice.dispute

import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisputeStatusTest {

    @ParameterizedTest(name = "{0} -> {1} allowed")
    @MethodSource("validTransitions")
    fun `valid transitions succeed`(from: DisputeStatus, to: DisputeStatus) {
        assertTrue(from.canTransitionTo(to))
        assertEquals(to, from.transitionTo(to))
    }

    @ParameterizedTest(name = "{0} -> {1} rejected")
    @MethodSource("invalidTransitions")
    fun `invalid transitions throw`(from: DisputeStatus, to: DisputeStatus) {
        assertFalse(from.canTransitionTo(to))
        assertThrows<InvalidDisputeTransitionException> { from.transitionTo(to) }
    }

    @ParameterizedTest(name = "{0} terminal = {1}")
    @MethodSource("terminalStates")
    fun `terminal states have no outgoing transitions`(status: DisputeStatus, terminal: Boolean) {
        assertEquals(terminal, status.isTerminal)
        if (terminal) {
            DisputeStatus.entries.forEach { assertFalse(status.canTransitionTo(it)) }
        }
    }

    companion object {
        @JvmStatic
        fun validTransitions(): Stream<Arguments> = Stream.of(
            Arguments.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW),
            Arguments.of(DisputeStatus.OPEN, DisputeStatus.WON),
            Arguments.of(DisputeStatus.OPEN, DisputeStatus.LOST),
            Arguments.of(DisputeStatus.UNDER_REVIEW, DisputeStatus.WON),
            Arguments.of(DisputeStatus.UNDER_REVIEW, DisputeStatus.LOST)
        )

        @JvmStatic
        fun invalidTransitions(): Stream<Arguments> = Stream.of(
            // no going back to OPEN
            Arguments.of(DisputeStatus.UNDER_REVIEW, DisputeStatus.OPEN),
            // terminal to anything
            Arguments.of(DisputeStatus.WON, DisputeStatus.LOST),
            Arguments.of(DisputeStatus.LOST, DisputeStatus.WON),
            Arguments.of(DisputeStatus.WON, DisputeStatus.UNDER_REVIEW)
        )

        @JvmStatic
        fun terminalStates(): Stream<Arguments> = Stream.of(
            Arguments.of(DisputeStatus.OPEN, false),
            Arguments.of(DisputeStatus.UNDER_REVIEW, false),
            Arguments.of(DisputeStatus.WON, true),
            Arguments.of(DisputeStatus.LOST, true)
        )
    }
}
