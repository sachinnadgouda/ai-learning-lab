package com.example.clean.application

import com.example.clean.domain.Order

interface OrderRepositoryPort {
    fun save(order: Order)
    fun findById(id: String): Order?
}

class PlaceOrderUseCase(
    private val orders: OrderRepositoryPort,
) {
    fun execute(order: Order): Order {
        orders.save(order)
        return order
    }
}
