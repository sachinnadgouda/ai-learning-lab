package com.acme.monolith.billing

import com.acme.monolith.orders.OrderProcessor

class BillingService(private val orders: OrderProcessor) {
    fun chargeOrder(orderId: String): Boolean = orders.verifyOrder(orderId)
}
