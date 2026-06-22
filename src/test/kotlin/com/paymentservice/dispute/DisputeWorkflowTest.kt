package com.paymentservice.dispute

import com.paymentservice.TestcontainersConfiguration
import com.paymentservice.ledger.AccountType
import com.paymentservice.ledger.LedgerService
import com.paymentservice.payment.PaymentService
import com.paymentservice.payment.dto.CreatePaymentRequest
import com.paymentservice.payment.outbox.OutboxDispatcher
import org.camunda.bpm.engine.ManagementService
import org.camunda.bpm.engine.RuntimeService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dispute-deadline BPMN process: a race between the merchant's evidence and
 * the response deadline. The test profile parks the job executor, so the timer
 * fires only when we drive it explicitly — keeping the race deterministic.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class DisputeWorkflowTest {

    @Autowired lateinit var paymentService: PaymentService
    @Autowired lateinit var ledgerService: LedgerService
    @Autowired lateinit var outboxDispatcher: OutboxDispatcher
    @Autowired lateinit var disputeService: DisputeService
    @Autowired lateinit var workflow: DisputeDeadlineWorkflow
    @Autowired lateinit var runtimeService: RuntimeService
    @Autowired lateinit var managementService: ManagementService

    private val merchantId = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11")

    private fun openedDispute(): UUID {
        val request = CreatePaymentRequest(
            merchantId = merchantId, amount = 10_000L, currency = "EUR", description = "wf"
        )
        val txn = paymentService.createPayment(request, "k-${UUID.randomUUID()}").transaction
        outboxDispatcher.dispatchPending()
        paymentService.handleProviderCallback(txn.id, authorized = true, providerReference = "ref")
        paymentService.capturePayment(txn.id)
        return disputeService.openDispute(txn.id, DisputeReason.FRAUDULENT).id
    }

    @Test
    fun `deadline elapsing forfeits the dispute as lost`() {
        val disputeId = openedDispute()
        val instanceId = workflow.start(disputeId, "PT5M")

        // the engine is parked at the event gateway with a pending timer job
        val timer = managementService.createJobQuery().processInstanceId(instanceId).singleResult()
        assertNotNull(timer, "deadline timer must be waiting")

        // drive the deadline
        managementService.executeJob(timer.id)

        assertEquals(DisputeStatus.LOST, disputeService.getDispute(disputeId).status)
        assertTrue(
            ledgerService.getEntriesForTransaction(disputeService.getDispute(disputeId).transactionId)
                .any { it.accountType == AccountType.CHARGEBACK },
            "forfeit must post the chargeback clawback"
        )
        assertNull(
            runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).singleResult(),
            "process completes on the forfeited branch"
        )
    }

    @Test
    fun `evidence wins the race and the dispute is not forfeited`() {
        val disputeId = openedDispute()
        val instanceId = workflow.start(disputeId, "PT5M")

        workflow.submitEvidence(disputeId)

        // contested branch ends the instance; the deadline timer is cancelled
        assertNull(
            runtimeService.createProcessInstanceQuery().processInstanceId(instanceId).singleResult(),
            "process completes on the contested branch"
        )
        assertNull(
            managementService.createJobQuery().processInstanceId(instanceId).singleResult(),
            "deadline timer is cancelled once evidence wins"
        )
        // the dispute was never auto-forfeited
        assertEquals(DisputeStatus.OPEN, disputeService.getDispute(disputeId).status)
    }
}
