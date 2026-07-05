package com.paymentservice.ledger

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Golden vectors computed by the independent Python hashlib reference (shared
 * verbatim with the anchor verifier test suite). Mutation tested.
 */
class AnchorChainTest {

    private val root1 = "3dc4a3e169aba383f31aa41cff239a715cb04c7f5fa114f740e9581fefbad646"
    private val root2 = "f0403eee8055c3dde05912e05d92c561655db532a2ac817cbff1309190af8419"

    @Test
    fun `golden chain vectors from genesis`() {
        val chain1 = AnchorChain.next(AnchorChain.GENESIS, root1, 1)
        assertEquals("79c6ae64ef06f450119bcfdb6f17e45a8672c0bacbfdea005fdcf746da9d5bc1", chain1)
        assertEquals(
            "83d6987ceeaa7d213f146ad02ce783d3774a49dd5e1d3f9c15e045d2b8d0b178",
            AnchorChain.next(chain1, root2, 2)
        )
    }

    @Test
    fun `chain hash commits to epoch root and predecessor`() {
        val base = AnchorChain.next(AnchorChain.GENESIS, root1, 1)
        assertNotEquals(base, AnchorChain.next(AnchorChain.GENESIS, root1, 2))
        assertNotEquals(base, AnchorChain.next(AnchorChain.GENESIS, root2, 1))
        assertNotEquals(base, AnchorChain.next(base, root1, 1))
    }

    @Test
    fun `genesis is sixty four zero characters`() {
        assertEquals(64, AnchorChain.GENESIS.length)
        assertEquals(setOf('0'), AnchorChain.GENESIS.toSet())
    }
}
