package com.paymentservice

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<PaymentServiceApplication>().with(TestcontainersConfiguration::class).run(*args)
}
