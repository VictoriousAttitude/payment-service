package com.paymentservice.payout

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface PayoutRepository : JpaRepository<Payout, UUID> {

    fun findByMerchantIdOrderByCreatedAtDesc(merchantId: UUID): List<Payout>

    /** PENDING payouts old enough to confirm (submitted at least the confirm delay ago). */
    @Query("""
        SELECT p FROM Payout p
        WHERE p.status = com.paymentservice.payout.PayoutStatus.PENDING
        AND p.createdAt <= :cutoff
        ORDER BY p.createdAt ASC
    """)
    fun findConfirmable(cutoff: Instant, pageable: Pageable): List<Payout>
}
