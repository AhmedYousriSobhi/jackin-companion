package dev.netnavi.companion.bus

import dev.netnavi.companion.access.ToolExecutor
import dev.netnavi.companion.net.ClientMessage
import dev.netnavi.companion.net.ConnState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the host last told the avatar to do (`navi_state`). */
enum class NaviMode { IDLE, PROCESSING, TALKING }

/**
 * In-process bridge between [dev.netnavi.companion.service.NaviForegroundService] (owns the
 * socket), the accessibility service (overlay + tools) and the UI. Process-local on purpose.
 */
object NaviBus {
    private val _connection = MutableStateFlow<ConnState>(ConnState.Idle)
    val connection: StateFlow<ConnState> = _connection.asStateFlow()

    private val _naviMode = MutableStateFlow(NaviMode.IDLE)
    val naviMode: StateFlow<NaviMode> = _naviMode.asStateFlow()

    private val _killSwitch = MutableStateFlow(false)
    /** True after a long-press on the avatar: tool calls are refused with `kill_switch` until re-armed. */
    val killSwitch: StateFlow<Boolean> = _killSwitch.asStateFlow()

    private val _screenOn = MutableStateFlow(true)
    val screenOn: StateFlow<Boolean> = _screenOn.asStateFlow()

    private val _a11yConnected = MutableStateFlow(false)
    val a11yConnected: StateFlow<Boolean> = _a11yConnected.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _lastReply = MutableStateFlow("")
    val lastReply: StateFlow<String> = _lastReply.asStateFlow()

    /** Set by the accessibility service while it is connected. */
    val executor = MutableStateFlow<ToolExecutor?>(null)

    private val _foregroundApp = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val foregroundApp: SharedFlow<String> = _foregroundApp.asSharedFlow()

    /** UI -> service messages (the test-message box on the setup screen). */
    private val _outbox = MutableSharedFlow<ClientMessage>(extraBufferCapacity = 16)
    val outbox: SharedFlow<ClientMessage> = _outbox.asSharedFlow()

    fun setConnection(s: ConnState) { _connection.value = s }
    fun setNaviMode(m: NaviMode) { _naviMode.value = m }
    fun setScreenOn(on: Boolean) { _screenOn.value = on }
    fun setA11yConnected(c: Boolean) { _a11yConnected.value = c }
    fun setServiceRunning(r: Boolean) { _serviceRunning.value = r }
    fun setLastReply(t: String) { _lastReply.value = t }
    fun appendReply(t: String) { _lastReply.value += t }
    fun reportForegroundApp(pkg: String) { _foregroundApp.tryEmit(pkg) }
    fun send(msg: ClientMessage) { _outbox.tryEmit(msg) }

    fun engageKillSwitch() { _killSwitch.value = true }
    fun rearmKillSwitch() { _killSwitch.value = false }
}
