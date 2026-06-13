package com.paymentservice.merchant

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface MerchantRepository : JpaRepository<Merchant, UUID> {
    fun findByApiKey(apiKey: String): Merchant?
}
