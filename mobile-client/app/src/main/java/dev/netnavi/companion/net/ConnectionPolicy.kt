package dev.netnavi.companion.net

import kotlin.random.Random

enum class LinkMode { ALWAYS, ON_DEMAND }

/**
 * Pure link-timing rules (spec §7): one app heartbeat whose period depends on the screen, and
 * exponential backoff with full jitter. Kept free of Android types so it is unit-testable.
 */
class ConnectionPolicy(
    private val pingScreenOnS: Int = 25,
    private val pingScreenOffS: Int = 120,
    private val backoffMinMs: Long = 1_000,
    private val backoffMaxMs: Long = 30_000,
    private val random: Random = Random.Default,
) {
    /** `serverPingS` (from `hello_ack.config`) overrides the screen-on default when present. */
    fun pingIntervalS(screenOn: Boolean, serverPingS: Int? = null): Int =
        if (screenOn) serverPingS ?: pingScreenOnS else pingScreenOffS

    /**
     * Delay before reconnect attempt number [attempt] (0-based): uniform in
     * [backoffMinMs, min(backoffMaxMs, backoffMinMs * 2^attempt)]. The 1 s floor prevents a hot loop.
     */
    fun backoffMs(attempt: Int): Long {
        val exp = backoffMinMs shl attempt.coerceIn(0, 20)
        val cap = exp.coerceIn(backoffMinMs, backoffMaxMs)
        return if (cap <= backoffMinMs) backoffMinMs else random.nextLong(backoffMinMs, cap + 1)
    }
}

/** Remembers the last [capacity] call ids so a re-delivered `tool_call` is never executed twice. */
class CallIdDedupe(private val capacity: Int = 64) {
    private val seen = object : LinkedHashMap<String, Unit>(capacity + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > capacity
    }

    /** True the first time [id] is seen, false for duplicates. */
    @Synchronized
    fun firstSeen(id: String): Boolean = seen.put(id, Unit) == null
}
