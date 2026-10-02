package dev.netnavi.companion.net

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the real WsClient against a running host-server (LLM_PROVIDER=fake). Skipped unless
 * NAVI_HOST_URL (e.g. ws://127.0.0.1:8765/ws) and NAVI_HOST_TOKEN are set:
 *   NAVI_HOST_URL=ws://127.0.0.1:8765/ws NAVI_HOST_TOKEN=devtoken ./gradlew testDebugUnitTest
 */
class RealHostIntegrationTest {
    @Test fun fullTurnAgainstTheRealHost() = runBlocking {
        val url = System.getenv("NAVI_HOST_URL")
        val token = System.getenv("NAVI_HOST_TOKEN")
        assumeTrue("NAVI_HOST_URL/NAVI_HOST_TOKEN not set", url != null && token != null)

        val scope = CoroutineScope(Dispatchers.IO + Job())
        val client = WsClient(scope, MutableStateFlow(true))
        val seen = CopyOnWriteArrayList<ServerMessage>()
        val collector = scope.launch { client.inbound.collect { seen += it } }
        delay(50)
        client.start(
            WsConfig(url) {
                Hello(token, "itest-device", "0.1.0", listOf("a11y_overlay"), listOf("core"), Screen(1080, 2400, 420), "en", "UTC")
            },
        )
        withTimeout(10_000) { client.state.first { it == ConnState.Connected } }
        assertTrue(client.send(UserText("hello from the integration test")))
        withTimeout(20_000) { while (seen.none { it is AssistantDone }) delay(20) }

        val states = seen.filterIsInstance<NaviStateMsg>().map { it.state }
        println("integration: states=$states deltas=${seen.count { it is AssistantDelta }} types=${seen.map { it::class.simpleName }}")
        assertTrue("processing" in states && "talking" in states && states.last() == "idle" || states.contains("idle"))
        assertTrue(seen.any { it is AssistantDelta })
        collector.cancel()
        client.stop()
        scope.cancel()
    }
}
