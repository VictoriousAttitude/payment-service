package com.paymentservice.payment

import java.util.UUID

/**
 * Outbound port to the payment provider. The outbox dispatcher depends on this
 * abstraction, not the concrete simulator — swapping in a real Stripe/Adyen
 * adapter is a one-bean change with no caller impact (hexagonal boundary).
 */
interface PaymentProviderPort {
    fun requestAuthorization(transactionId: UUID)
}
