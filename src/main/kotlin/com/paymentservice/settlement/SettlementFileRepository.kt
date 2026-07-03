package com.paymentservice.settlement

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SettlementFileRepository : JpaRepository<SettlementFile, UUID> {

    fun findByContentSha256(contentSha256: String): SettlementFile?
}
