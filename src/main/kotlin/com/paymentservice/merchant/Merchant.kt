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

    // SHA-256 hex of the raw key; the plaintext key is never stored at rest.
    @Column(name = "api_key_hash", nullable = false, unique = true)
    val apiKeyHash: String,

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
