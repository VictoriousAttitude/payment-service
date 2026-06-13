package com.paymentservice.shared

import java.util.UUID

/**
 * Raised when an authenticated merchant tries to act for another merchant.
 * An access-control concern shared by the payment and merchant boundaries, so
 * it lives in the shared kernel rather than coupling those modules together.
 */
class PaymentAccessDeniedException(val merchantId: UUID) :
    RuntimeException("Authenticated merchant $merchantId may not act for another merchant")
