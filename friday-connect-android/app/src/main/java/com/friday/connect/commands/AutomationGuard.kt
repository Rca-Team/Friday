package com.friday.connect.commands

import java.util.concurrent.atomic.AtomicLong

object AutomationGuard {
    private val generation = AtomicLong(0)
    fun stop() { generation.incrementAndGet() }
    fun token(): Long = generation.get()
    fun active(token: Long): Boolean = generation.get() == token
}
