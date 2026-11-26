package com.example.clean.domain

// Note: documentation comment referencing infrastructure should NEVER trigger a violation:
// import com.example.clean.infrastructure.SqlOrderRepository

data class Order(
    val id: String,
    val customerId: String,
    val amountCents: Long,
) {
    fun describe(): String = "import com.example.clean.api.OrderController is just a string literal"
}
