package dev.netnavi.companion.net

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPolicyTest {
    private val policy = ConnectionPolicy(random = Random(42))

    @Test fun heartbeatFollowsScreenState() {
        assertEquals(25, policy.pingIntervalS(screenOn = true))
        assertEquals(120, policy.pingIntervalS(screenOn = false))
    }

    @Test fun serverPingOverridesOnlyWhenScreenOn() {
        assertEquals(40, policy.pingIntervalS(screenOn = true, serverPingS = 40))
        assertEquals(120, policy.pingIntervalS(screenOn = false, serverPingS = 40))
    }

    @Test fun firstBackoffIsOneSecond() {
        assertEquals(1_000, policy.backoffMs(0))
    }

    @Test fun backoffStaysWithinJitteredExponentialBounds() {
        repeat(200) {
            for (attempt in 0..40) {
                val cap = minOf(30_000L, 1_000L shl attempt.coerceAtMost(20))
                val d = policy.backoffMs(attempt)
                assertTrue("attempt $attempt gave $d", d in 1_000..cap)
            }
        }
    }

    @Test fun backoffActuallyJittersAndReachesTheCap() {
        val samples = (1..500).map { policy.backoffMs(10) }
        assertTrue(samples.toSet().size > 50)
        assertTrue(samples.max() > 25_000)
        assertTrue(samples.max() <= 30_000)
    }

    @Test fun dedupeIgnoresRepeatsAndForgetsOldest() {
        val d = CallIdDedupe(capacity = 3)
        assertTrue(d.firstSeen("a"))
        assertFalse(d.firstSeen("a"))
        assertTrue(d.firstSeen("b"))
        assertTrue(d.firstSeen("c"))
        assertTrue(d.firstSeen("d")) // evicts "a"
        assertTrue(d.firstSeen("a"))
    }
}
