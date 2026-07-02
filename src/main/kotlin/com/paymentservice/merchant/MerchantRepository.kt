package com.paymentservice.merchant

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface MerchantRepository : JpaRepository<Merchant, UUID> {
    fun findByApiKeyHash(apiKeyHash: String): Merchant?

    /**
     * SELECT ... FOR UPDATE on the merchant row: the serialization point for
     * payout creation. The payable balance is a ledger SUM, so no @Version can
     * guard it against a concurrent double-spend - two payouts reading the same
     * balance would both pass the amount guard. The row lock makes the
     * read-balance/post-entries sequence per merchant strictly sequential.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Merchant m WHERE m.id = :id")
    fun lockById(id: UUID): Merchant?
}
