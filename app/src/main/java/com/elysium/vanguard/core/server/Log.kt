package com.elysium.vanguard.core.server

/**
 * JVM-safe logger. On Android we delegate to [android.util.Log]; in unit tests
 * (where android.* classes are stubs) we fall back to println so the message at
 * least shows up in stdout and the accept loop doesn't crash on a missing class.
 *
 * Why we care: a NoClassDefFoundError from inside the accept coroutine kills the
 * launch entirely. New connections then never get accepted and the test hangs.
 * Routing through a guarded logger keeps the server usable from plain JVM tests.
 */
internal object Log {
    fun d(tag: String, msg: String) { safeLog(tag, msg) }
    fun i(tag: String, msg: String) { safeLog(tag, msg) }
    fun w(tag: String, msg: String) { safeLog(tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        safeLog(tag, msg)
        if (t != null) t.printStackTrace()
    }

    private fun safeLog(tag: String, msg: String) {
        try {
            // Reflection: keeps android.util.Log out of the unit-test classpath.
            val logClass = Class.forName("android.util.Log")
            val method = logClass.getMethod("i", String::class.java, String::class.java)
            method.invoke(null, tag, msg)
        } catch (_: Throwable) {
            // Not Android, or stub failed — emit to stdout so we still see it.
            println("[$tag] $msg")
        }
    }
}