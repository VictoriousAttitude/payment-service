package com.paymentservice.merchant

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "merchants")
class Merchant(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val name: String,

    val email: String? = null,

    @Column(name = "api_key", nullable = false, unique = true)
    val apiKey: String,

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    var status: MerchantStatus = MerchantStatus.ACTIVE,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

enum class MerchantStatus {
    PENDING, ACTIVE, SUSPENDED
}
