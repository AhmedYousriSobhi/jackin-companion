package dev.netnavi.companion.net

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

enum class StopReason { BAD_TOKEN, REPLACED, CLIENT_TOO_OLD }

sealed interface ConnState {
    /** Not started. */
    data object Idle : ConnState
    data object Connecting : ConnState
    /** Socket open and `hello_ack` received. */
    data object Connected : ConnState
    /** Waiting before the next attempt. */
    data class Backoff(val attempt: Int, val delayMs: Long) : ConnState
    /** Terminal: reconnecting would not help (bad token, replaced, client too old). */
    data class Stopped(val reason: StopReason) : ConnState
}

data class WsConfig(val url: String, val buildHello: () -> Hello)

/**
 * OkHttp WebSocket with the spec §7 link rules: single app-level heartbeat (OkHttp pingInterval is
 * off), jittered exponential backoff, immediate retry on [kick], call_id dedupe, and a StateFlow /
 * SharedFlow surface for the rest of the app.
 */
class WsClient(
    private val scope: CoroutineScope,
    private val screenOn: StateFlow<Boolean>,
    private val policy: ConnectionPolicy = ConnectionPolicy(),
    private val http: OkHttpClient = defaultHttpClient(),
    private val pongTimeoutMs: Long = 20_000,
) {
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private val _inbound = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 256)
    val inbound: SharedFlow<ServerMessage> = _inbound.asSharedFlow()

    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val pongs = Channel<Unit>(Channel.CONFLATED)
    private val dedupe = CallIdDedupe()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var serverPingS: Int? = null
    private var loop: Job? = null

    @Synchronized
    fun start(config: WsConfig) {
        stop()
        loop = scope.launch { runLoop(config) }
    }

    @Synchronized
    fun stop() {
        loop?.cancel()
        loop = null
        socket?.close(1000, "stop")
        socket = null
        _state.value = ConnState.Idle
    }

    /** Skip the current backoff wait (e.g. the network just came back). */
    fun kick() {
        kicks.trySend(Unit)
    }

    /** Sends [msg] if the link is up. Returns false (message dropped) otherwise. */
    fun send(msg: ClientMessage): Boolean {
        if (_state.value != ConnState.Connected) return false
        return socket?.send(msg.encode()) ?: false
    }

    private suspend fun runLoop(config: WsConfig) {
        var attempt = 0
        while (scope.isActive) {
            _state.value = ConnState.Connecting
            val closed = connectOnce(config) { attempt = 0 }
            val stop = when (closed.code) {
                CloseCode.BAD_TOKEN -> StopReason.BAD_TOKEN
                CloseCode.REPLACED -> StopReason.REPLACED
                CloseCode.CLIENT_TOO_OLD -> StopReason.CLIENT_TOO_OLD
                else -> null
            }
            if (stop != null) {
                _state.value = ConnState.Stopped(stop)
                return
            }
            val wait = policy.backoffMs(attempt)
            attempt++
            _state.value = ConnState.Backoff(attempt, wait)
            kicks.tryReceive() // drop a stale kick; only a fresh one should cut the wait short
            withTimeoutOrNull(wait) { kicks.receive() }
        }
    }

    private class Closed(val code: Int, val reason: String)

    private suspend fun connectOnce(config: WsConfig, onAck: () -> Unit): Closed {
        val closed = CompletableDeferred<Closed>()
        val ws = http.newWebSocket(
            Request.Builder().url(config.url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(config.buildHello().encode()) // hello must be the first frame
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleText(text, onAck)
                }

                // The host may send envelopes as binary frames; treat them as UTF-8 JSON too.
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleText(bytes.utf8(), onAck)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    closed.complete(Closed(code, reason))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    NaviLog.w("socket failure: ${t.javaClass.simpleName}")
                    closed.complete(Closed(-1, t.message ?: ""))
                }
            },
        )
        socket = ws
        val heartbeat = scope.launch { heartbeat(ws) }
        try {
            return closed.await()
        } finally {
            heartbeat.cancel()
            ws.cancel()
            if (socket === ws) socket = null
        }
    }

    private fun handleText(text: String, onAck: () -> Unit) {
        when (val msg = decodeServerMessage(text)) {
            null -> NaviLog.d("dropped malformed frame")
            is HelloAck -> {
                serverPingS = msg.config.pingS
                onAck()
                _state.value = ConnState.Connected
                _inbound.tryEmit(msg)
            }
            is Pong -> pongs.trySend(Unit)
            is ToolCall -> if (dedupe.firstSeen(msg.callId)) _inbound.tryEmit(msg) else NaviLog.d("duplicate call_id ignored")
            is UnknownMessage -> NaviLog.d("ignored unknown message type ${msg.type}")
            else -> _inbound.tryEmit(msg)
        }
    }

    /** One heartbeat for the whole app; the period follows the screen state. */
    private suspend fun heartbeat(ws: WebSocket) {
        _state.first { it == ConnState.Connected } // the period depends on hello_ack.config.ping_s
        screenOn.collectLatest { on ->
            while (true) {
                val intervalS = policy.pingIntervalS(on, serverPingS)
                delay(intervalS * 1000L)
                if (_state.value != ConnState.Connected) continue
                pongs.tryReceive()
                if (!ws.send(Ping(nextS = intervalS).encode())) continue
                if (withTimeoutOrNull(pongTimeoutMs) { pongs.receive() } == null) {
                    NaviLog.w("no pong; dropping dead socket")
                    ws.cancel() // -> onFailure -> reconnect
                    return@collectLatest
                }
            }
        }
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .pingInterval(0, TimeUnit.SECONDS) // the app heartbeat is the only one (spec §7)
            .readTimeout(0, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
