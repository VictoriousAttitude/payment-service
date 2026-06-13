package com.paymentservice.merchant

import java.util.UUID

class MerchantNotFoundException(val merchantId: UUID) :
    RuntimeException("Merchant not found: $merchantId")

class MerchantNotActiveException(val merchantId: UUID) :
    RuntimeException("Merchant is not active: $merchantId")
