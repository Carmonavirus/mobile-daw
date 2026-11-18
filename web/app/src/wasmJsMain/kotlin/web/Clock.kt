package com.mobiledaw.web

private fun dateNow(): Double = js("Date.now()")
private fun perfNow(): Double = js("performance.now()")

object Clock {
    /** Milliseconds since 1970, like System.currentTimeMillis(). */
    fun now(): Long = dateNow().toLong()
    /** Milliseconds since the page opened, like SystemClock.uptimeMillis(). */
    fun uptime(): Long = perfNow().toLong()
    fun nanos(): Long = (perfNow() * 1_000_000.0).toLong()
}
