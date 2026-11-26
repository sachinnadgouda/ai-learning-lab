package com.acme.monolith.infrastructure

class SqlConnectionPool {
    fun execute(sql: String): Int = sql.length
}
