package com.paymentservice.dispute

import org.camunda.bpm.engine.delegate.DelegateExecution
import org.camunda.bpm.engine.delegate.JavaDelegate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Service-task bridge from the dispute-deadline BPMN process into the domain.
 * Runs when the response deadline elapses before evidence is filed: the merchant
 * forfeits, so the dispute is resolved LOST (which posts the chargeback ledger
 * clawback through the same path a manual loss takes).
 */
@Component("disputeForfeitDelegate")
class DisputeForfeitDelegate(
    private val disputeService: DisputeService
) : JavaDelegate {

    override fun execute(execution: DelegateExecution) {
        val disputeId = UUID.fromString(execution.getVariable(VAR_DISPUTE_ID) as String)
        disputeService.resolve(disputeId, won = false)
    }

    companion object {
        const val VAR_DISPUTE_ID = "disputeId"
    }
}
