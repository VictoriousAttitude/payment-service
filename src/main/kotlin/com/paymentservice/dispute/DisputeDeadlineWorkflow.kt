package com.paymentservice.dispute

import org.camunda.bpm.engine.RuntimeService
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Starts and steers the dispute-deadline BPMN process. Kept separate from
 * DisputeService so the core domain has no dependency on the workflow engine:
 * the deadline orchestration is an optional layer over a self-sufficient
 * dispute model, not a precondition for it.
 *
 * The dispute id is the process business key, so evidence correlation and
 * lookups need only the id the caller already holds.
 */
@Service
class DisputeDeadlineWorkflow(
    private val runtimeService: RuntimeService
) {

    /**
     * Starts the deadline race for an open dispute. [deadline] is an ISO-8601
     * duration (e.g. P7D) after which an unanswered dispute auto-forfeits.
     */
    fun start(disputeId: UUID, deadline: String): String {
        val variables = mapOf(
            DisputeForfeitDelegate.VAR_DISPUTE_ID to disputeId.toString(),
            VAR_DEADLINE to deadline
        )
        return runtimeService
            .startProcessInstanceByKey(PROCESS_KEY, disputeId.toString(), variables)
            .id
    }

    /**
     * Correlates the merchant's evidence, taking the contested branch and
     * cancelling the pending deadline timer. No-op if no instance is waiting.
     */
    fun submitEvidence(disputeId: UUID) {
        runtimeService.createMessageCorrelation(MESSAGE_EVIDENCE)
            .processInstanceBusinessKey(disputeId.toString())
            .correlateAll()
    }

    companion object {
        const val PROCESS_KEY = "dispute-deadline"
        const val MESSAGE_EVIDENCE = "evidenceSubmitted"
        const val VAR_DEADLINE = "deadline"
    }
}
