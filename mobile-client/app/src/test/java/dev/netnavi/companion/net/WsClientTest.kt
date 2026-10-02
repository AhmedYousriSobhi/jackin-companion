package dev.netnavi.companion.net

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WsClientTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val screenOn = MutableStateFlow(true)
    private val received = CopyOnWriteArrayList<Envelope>()
    private val upgrades = AtomicInteger()

    private fun ack(pingS: Int = 25) =
        """{"v":1,"id":"a","type":"hello_ack","ts":1,"payload":{"session_id":"s","server_version":"0.1.0","min_client_version":"0.1.0","config":{"ping_s":$pingS,"delta_flush_ms":50},"tools_enabled":[]}}"""

    private fun toolCall(id: String) =
        """{"v":1,"id":"t$id","type":"tool_call","ts":1,"payload":{"call_id":"$id","name":"read_ui_tree","args":{},"deadline_ms":1000}}"""

    private fun pong() = """{"v":1,"id":"p","type":"pong","ts":1,"payload":{}}"""

    /** A scripted host: records frames and reacts through [onFrame]. */
    private fun enqueueHost(
        pingS: Int = 25,
        answerPings: Boolean = true,
        afterAck: (WebSocket) -> Unit = {},
        closeWith: Pair<Int, String>? = null,
    ) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    upgrades.incrementAndGet()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val env = NaviJson.decodeFromString(Envelope.serializer(), text)
                    received += env
                    when (env.type) {
                        "hello" -> if (closeWith != null) webSocket.close(closeWith.first, closeWith.second) else {
                            webSocket.send(ack(pingS))
                            afterAck(webSocket)
                        }
                        "ping" -> if (answerPings) webSocket.send(pong())
                    }
                }
            }),
        )
    }

    private fun client(policy: ConnectionPolicy = fastPolicy(), pongTimeoutMs: Long = 20_000) =
        WsClient(scope, screenOn, policy, pongTimeoutMs = pongTimeoutMs)

    private fun fastPolicy() = ConnectionPolicy(backoffMinMs = 20, backoffMaxMs = 60, random = Random(1))

    private fun config() = WsConfig(server.url("/ws").toString().replaceFirst("http", "ws")) {
        Hello("secret", "dev1", "0.1.0", listOf("a11y_overlay"), listOf("core"), Screen(1080, 2400, 420), "en", "UTC")
    }

    private suspend fun WsClient.await(target: ConnState) = withTimeout(5_000) { state.first { it == target } }

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        scope = CoroutineScope(Dispatchers.IO + Job())
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    @Test fun sendsHelloFirstThenBecomesConnected() = runBlocking {
        enqueueHost()
        val c = client()
        c.start(config())
        c.await(ConnState.Connected)
        val hello = received.first()
        assertEquals("hello", hello.type)
        assertEquals("dev1", hello.payload["device_id"].toString().trim('"'))
        c.stop()
        assertEquals(ConnState.Idle, c.state.value)
    }

    @Test fun sendIsRejectedUntilConnected() = runBlocking {
        val c = client()
        assertFalse(c.send(UserText("hi")))
    }

    @Test fun badTokenStopsWithoutReconnecting() = runBlocking {
        enqueueHost(closeWith = CloseCode.BAD_TOKEN to "bad token")
        val c = client()
        c.start(config())
        c.await(ConnState.Stopped(StopReason.BAD_TOKEN))
        Thread.sleep(300)
        assertEquals(1, upgrades.get())
        c.stop()
    }

    @Test fun replacedAndTooOldAreTerminalToo() = runBlocking {
        enqueueHost(closeWith = CloseCode.REPLACED to "replaced")
        val c1 = client()
        c1.start(config())
        c1.await(ConnState.Stopped(StopReason.REPLACED))
        c1.stop()

        enqueueHost(closeWith = CloseCode.CLIENT_TOO_OLD to "old")
        val c2 = client()
        c2.start(config())
        c2.await(ConnState.Stopped(StopReason.CLIENT_TOO_OLD))
        c2.stop()
    }

    @Test fun reconnectsAfterServerDropsTheSocket() = runBlocking {
        enqueueHost(closeWith = 1011 to "boom") // closes straight after hello
        enqueueHost()
        val c = client()
        c.start(config())
        c.await(ConnState.Connected)
        assertEquals(2, upgrades.get())
        c.stop()
    }

    @Test fun duplicateToolCallIsDeliveredOnce() = runBlocking {
        enqueueHost(afterAck = { ws ->
            ws.send(toolCall("c1"))
            ws.send(toolCall("c1"))
            ws.send(toolCall("c2"))
        })
        val c = client()
        val seen = CopyOnWriteArrayList<String>()
        val collector = scope.launch { c.inbound.collect { if (it is ToolCall) seen += it.callId } }
        Thread.sleep(50)
        c.start(config())
        c.await(ConnState.Connected)
        withTimeout(5_000) { while (seen.size < 2) kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.delay(150)
        assertEquals(listOf("c1", "c2"), seen.toList())
        collector.cancel()
        c.stop()
    }

    @Test fun heartbeatUsesServerPeriodAndAnnouncesNext() = runBlocking {
        enqueueHost(pingS = 1)
        val c = client()
        c.start(config())
        c.await(ConnState.Connected)
        withTimeout(5_000) { while (received.none { it.type == "ping" }) kotlinx.coroutines.delay(20) }
        val ping = received.first { it.type == "ping" }
        assertEquals("1", ping.payload["next_s"].toString())
        c.stop()
    }

    @Test fun missingPongDropsTheSocketAndReconnects() = runBlocking {
        enqueueHost(pingS = 1, answerPings = false)
        enqueueHost()
        val c = client(pongTimeoutMs = 300)
        c.start(config())
        c.await(ConnState.Connected)
        withTimeout(8_000) { while (upgrades.get() < 2) kotlinx.coroutines.delay(20) }
        assertTrue(upgrades.get() >= 2)
        c.stop()
    }
}
