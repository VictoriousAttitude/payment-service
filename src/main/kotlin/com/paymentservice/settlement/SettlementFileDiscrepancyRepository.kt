package com.paymentservice.settlement

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SettlementFileDiscrepancyRepository : JpaRepository<SettlementFileDiscrepancy, UUID> {

    fun findByFileIdOrderByReference(fileId: UUID): List<SettlementFileDiscrepancy>
}
