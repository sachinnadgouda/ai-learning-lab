package com.acme.monolith.notifications

import com.acme.monolith.reporting.Summary

class BrokenWebhook {
    fun send(msg: String) {
        /* Intentionally truncated block comment to test resilient partial parsing
        println(msg)
