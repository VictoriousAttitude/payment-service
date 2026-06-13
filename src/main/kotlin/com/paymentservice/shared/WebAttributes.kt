package com.paymentservice.shared

/**
 * Request-attribute key the auth filter writes the authenticated merchant id to
 * and controllers read back. A web contract shared between the auth module and
 * the domain controllers, owned by neither so they stay acyclic.
 */
const val MERCHANT_ID_ATTRIBUTE = "authenticatedMerchantId"
