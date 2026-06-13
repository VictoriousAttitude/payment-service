package com.paymentservice.reconciliation

import com.paymentservice.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import kotlin.test.assertTrue

/**
 * Verifies the scheduled reconciliation driver wires up and runs the full suite
 * against a real ledger. The scheduler itself is parked in the test profile, so
 * this drives reconcile() directly. The robust invariant to assert on a shared,
 * accumulated DB is the per-currency global balance: regardless of how many
 * payments other tests left behind, debits must equal credits in every currency.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class ReconciliationSchedulerTest {

    @Autowired lateinit var scheduler: ReconciliationScheduler

    @Test
    fun `scheduled reconciliation runs the suite and the ledger balances per-currency`() {
        val report = scheduler.reconcile()

        assertTrue(
            report.globalBalance.balanced,
            "global ledger must balance within every currency: ${report.globalBalance.byCurrency}"
        )
    }
}
