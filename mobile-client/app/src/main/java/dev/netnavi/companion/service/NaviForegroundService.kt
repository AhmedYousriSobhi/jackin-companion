package dev.netnavi.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.netnavi.companion.BuildConfig
import dev.netnavi.companion.MainActivity
import dev.netnavi.companion.R
import dev.netnavi.companion.access.ToolOutcome
import dev.netnavi.companion.access.toResult
import dev.netnavi.companion.bus.NaviBus
import dev.netnavi.companion.bus.NaviMode
import dev.netnavi.companion.bus.trackScreenState
import dev.netnavi.companion.data.Prefs
import dev.netnavi.companion.net.AssistantDelta
import dev.netnavi.companion.net.AssistantDone
import dev.netnavi.companion.net.Cancel
import dev.netnavi.companion.net.ConnState
import dev.netnavi.companion.net.ErrCode
import dev.netnavi.companion.net.ErrorMsg
import dev.netnavi.companion.net.ForegroundApp
import dev.netnavi.companion.net.Hello
import dev.netnavi.companion.net.NaviLog
import dev.netnavi.companion.net.NaviStateMsg
import dev.netnavi.companion.net.Screen
import dev.netnavi.companion.net.StopReason
import dev.netnavi.companion.net.ToolCall
import dev.netnavi.companion.net.ToolCancel
import dev.netnavi.companion.net.ToolError
import dev.netnavi.companion.net.ToolResult
import dev.netnavi.companion.net.WsClient
import dev.netnavi.companion.net.WsConfig
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Owns the WebSocket and its notification (spec §2). Type `specialUse`; the mic type comes in M6. */
class NaviForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var ws: WsClient
    private lateinit var prefs: Prefs
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Job>()
    private var unregisterScreen: (() -> Unit)? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopLink()
            return START_NOT_STICKY
        }
        goForeground(statusText(ConnState.Idle))
        if (!started) {
            started = true
            scope.launch {
                // A null intent means the system restarted us; only continue if the user left the link on.
                if (intent == null && !prefs.linkEnabled()) {
                    stopLink()
                    return@launch
                }
                prefs.setLinkEnabled(true)
                begin()
            }
        }
        return START_STICKY
    }

    private suspend fun begin() {
        NaviBus.setServiceRunning(true)
        unregisterScreen = trackScreenState(this)
        ws = WsClient(scope, NaviBus.screenOn)
        wireInbound()
        wireOutbound()
        registerNetworkCallback()

        val settings = prefs.settings.first()
        if (settings.url.isBlank() || settings.token.isBlank()) {
            NaviLog.w("host URL or token missing; not connecting")
            stopLink()
            return
        }
        val deviceId = prefs.deviceId()
        ws.start(WsConfig(settings.url) { buildHello(settings.token, deviceId) })
    }

    private fun buildHello(token: String, deviceId: String): Hello {
        val dm: DisplayMetrics = resources.displayMetrics
        return Hello(
            token = token,
            deviceId = deviceId,
            appVersion = BuildConfig.VERSION_NAME,
            capabilities = listOf("a11y_overlay"),
            programs = listOf("core"), // eyes/hands packs are advertised once M2/M4 land
            screen = Screen(dm.widthPixels, dm.heightPixels, dm.densityDpi),
            locale = Locale.getDefault().toLanguageTag(),
            tz = TimeZone.getDefault().id,
        )
    }

    private fun wireInbound() {
        scope.launch { ws.state.collect { onConnState(it) } }
        scope.launch {
            ws.inbound.collect { msg ->
                NaviLog.d("inbound ${msg::class.simpleName}")
                when (msg) {
                    is NaviStateMsg -> NaviBus.setNaviMode(
                        when (msg.state) {
                            "processing" -> NaviMode.PROCESSING
                            "talking" -> NaviMode.TALKING
                            else -> NaviMode.IDLE
                        },
                    )
                    is AssistantDelta -> NaviBus.appendReply(msg.text)
                    is AssistantDone -> NaviBus.setLastReply(msg.text + if (msg.cancelled) " [cancelled]" else "")
                    is ToolCall -> onToolCall(msg)
                    is ToolCancel -> onToolCancel(msg.callId)
                    is ErrorMsg -> NaviLog.w("server error ${msg.code}")
                    else -> Unit
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class) // debounce
    private fun wireOutbound() {
        // Avatar long-press: tell the host to stop, and abort anything running locally.
        scope.launch {
            NaviBus.killSwitch.collect { engaged ->
                if (engaged) {
                    ws.send(Cancel())
                    inFlight.values.forEach { it.cancel() }
                    inFlight.clear()
                    NaviBus.setNaviMode(NaviMode.IDLE)
                }
            }
        }
        scope.launch {
            NaviBus.foregroundApp.distinctUntilChanged().debounce(500).collect { pkg ->
                ws.send(ForegroundApp(pkg))
            }
        }
        scope.launch {
            NaviBus.outbox.collect { msg ->
                if (msg is dev.netnavi.companion.net.UserText) NaviBus.setLastReply("")
                if (!ws.send(msg)) NaviLog.d("not connected; outgoing message dropped")
            }
        }
    }

    private fun onConnState(state: ConnState) {
        NaviLog.d("conn $state")
        NaviBus.setConnection(state)
        if (state !is ConnState.Connected) {
            NaviBus.setNaviMode(NaviMode.IDLE)
            // The socket is gone: nothing can answer these calls any more.
            inFlight.values.forEach { it.cancel() }
            inFlight.clear()
        }
        notificationManager().notify(NOTIF_ID, buildNotification(statusText(state)))
        if (state is ConnState.Stopped && state.reason != StopReason.REPLACED) {
            NaviLog.w("link stopped: ${state.reason}")
        }
    }

    private fun onToolCall(call: ToolCall) {
        val job = scope.launch {
            val outcome: ToolOutcome = when {
                NaviBus.killSwitch.value -> ToolOutcome.Err(ErrCode.KILL_SWITCH, "kill switch engaged")
                else -> {
                    val executor = NaviBus.executor.value
                    if (executor == null) {
                        ToolOutcome.Err(ErrCode.UNSUPPORTED, "accessibility service is not enabled")
                    } else {
                        try {
                            withTimeout(call.deadlineMs) { executor.execute(call.name, call.args) }
                        } catch (_: TimeoutCancellationException) {
                            ToolOutcome.Err(ErrCode.TIMEOUT, "deadline exceeded")
                        }
                    }
                }
            }
            ws.send(outcome.toResult(call.callId))
        }
        inFlight[call.callId] = job
        job.invokeOnCompletion { inFlight.remove(call.callId) }
    }

    private fun onToolCancel(callId: String) {
        val job = inFlight.remove(callId) ?: return
        job.cancel()
        ws.send(ToolResult(callId, ok = false, error = ToolError(ErrCode.CANCELLED, "cancelled")))
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                ws.kick() // skip the backoff wait; the network just came back
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
    }

    private fun stopLink() {
        // Detached from `scope`, which is cancelled in onDestroy before this write could finish.
        ioScope.launch { prefs.setLinkEnabled(false) }
        if (started) ws.stop()
        NaviBus.setConnection(ConnState.Idle)
        NaviBus.setNaviMode(NaviMode.IDLE)
        NaviBus.setServiceRunning(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        networkCallback?.let {
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        }
        unregisterScreen?.invoke()
        if (started) ws.stop()
        NaviBus.setServiceRunning(false)
        scope.cancel()
        super.onDestroy()
    }

    // ---- notification -----------------------------------------------------------------

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NaviForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_navi)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun statusText(state: ConnState) = when (state) {
        ConnState.Idle -> "Starting…"
        ConnState.Connecting -> "Connecting…"
        ConnState.Connected -> "Connected"
        is ConnState.Backoff -> "Reconnecting…"
        is ConnState.Stopped -> when (state.reason) {
            StopReason.BAD_TOKEN -> "Rejected: bad token"
            StopReason.REPLACED -> "Replaced by another connection"
            StopReason.CLIENT_TOO_OLD -> "App too old for this host"
        }
    }

    private fun ensureChannel() {
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notificationManager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val ACTION_START = "dev.netnavi.companion.START"
        const val ACTION_STOP = "dev.netnavi.companion.STOP"
        private const val CHANNEL_ID = "navi_link"
        private const val NOTIF_ID = 1
    }
}
