package com.example.clean.infrastructure

import com.example.clean.application.OrderRepositoryPort
import com.example.clean.domain.Order

class InMemoryOrderRepository : OrderRepositoryPort {
    private val store = mutableMapOf<String, Order>()
    override fun save(order: Order) {
        store[order.id] = order
    }
    override fun findById(id: String): Order? = store[id]
}
