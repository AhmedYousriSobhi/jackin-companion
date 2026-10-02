package dev.netnavi.companion

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.netnavi.companion.bus.NaviBus
import dev.netnavi.companion.data.Prefs
import dev.netnavi.companion.net.ConnState
import dev.netnavi.companion.net.StopReason
import dev.netnavi.companion.overlay.NaviAvatar
import dev.netnavi.companion.overlay.OverlayHost
import dev.netnavi.companion.overlay.currentAvatarMode
import dev.netnavi.companion.overlay.killSwitchGestures
import dev.netnavi.companion.net.UserText
import dev.netnavi.companion.service.NaviForegroundService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)

    /** Loaded once; the text fields own their state afterwards (hoisted into the screen). */
    var initialUrl by mutableStateOf("")
        private set
    var initialToken by mutableStateOf("")
        private set
    var loaded by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            val s = prefs.settings.first()
            initialUrl = s.url
            initialToken = s.token
            loaded = true
        }
    }

    fun start(url: String, token: String) {
        viewModelScope.launch {
            prefs.save(url, token)
            val app = getApplication<Application>()
            ContextCompat.startForegroundService(
                app,
                Intent(app, NaviForegroundService::class.java).setAction(NaviForegroundService.ACTION_START),
            )
        }
    }

    fun stop() {
        val app = getApplication<Application>()
        app.startService(Intent(app, NaviForegroundService::class.java).setAction(NaviForegroundService.ACTION_STOP))
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { SetupScreen() }
            }
        }
    }
}

@Composable
private fun SetupScreen(vm: MainViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val connection by NaviBus.connection.collectAsState()
    val running by NaviBus.serviceRunning.collectAsState()
    val a11y by NaviBus.a11yConnected.collectAsState()
    val killed by NaviBus.killSwitch.collectAsState()
    val reply by NaviBus.lastReply.collectAsState()

    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var canDraw by remember { mutableStateOf(OverlayHost.hasPermission(context)) }
    LaunchedEffect(Unit) { // re-check while the screen is open so returning from Settings updates it
        while (true) {
            canDraw = OverlayHost.hasPermission(context)
            kotlinx.coroutines.delay(1000)
        }
    }
    LaunchedEffect(vm.loaded) {
        if (vm.loaded) {
            url = vm.initialUrl
            token = vm.initialToken
        }
    }

    val startWithPermission = {
        vm.start(url, token)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startWithPermission() // start either way; without the permission the notification is just hidden
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Navi", style = MaterialTheme.typography.headlineMedium)
            // The avatar also lives here, so the app works without any special permission.
            NaviAvatar(mode = currentAvatarMode(), modifier = Modifier.killSwitchGestures())
        }
        Text("Tip: long-press the avatar to stop Navi (kill switch), tap it to re-arm.", style = MaterialTheme.typography.bodySmall)
        Text("Status: ${describe(connection, running)}")
        val killText = if (killed) "ENGAGED (tap avatar)" else "armed"
        if (BuildConfig.LITE) {
            Text("Floating overlay: ${if (canDraw) "on" else "off (optional)"}   Kill switch: $killText")
        } else {
            Text("Accessibility: ${if (a11y) "enabled" else "not enabled"}   Kill switch: $killText")
        }
        if (reply.isNotEmpty()) Text("Navi: $reply", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = url, onValueChange = { url = it }, label = { Text("Host URL") },
            placeholder = { Text("wss://<host>.<tailnet>.ts.net/ws") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it }, label = { Text("Auth token") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        Text(
            "Emulator: ws://10.0.2.2:8765/ws. Plain ws:// to any other host is blocked; use wss via tailscale serve.",
            style = MaterialTheme.typography.bodySmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val needs = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needs) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else startWithPermission()
            }, enabled = url.startsWith("ws") && token.isNotBlank()) { Text("Start") }
            OutlinedButton(onClick = { vm.stop() }, enabled = running) { Text("Stop") }
            OutlinedButton(onClick = { NaviBus.rearmKillSwitch() }, enabled = killed) { Text("Re-arm") }
        }

        Text("Setup", style = MaterialTheme.typography.titleMedium)
        if (BuildConfig.LITE) {
            Text(
                "No special permission is needed: the avatar lives in this screen. Optionally allow \"Display over other apps\" " +
                    "(Android may require \"Allow restricted settings\" first) to also float it over other apps.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
                )
            }, modifier = Modifier.fillMaxWidth()) { Text("1. Allow display over other apps") }
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }, modifier = Modifier.fillMaxWidth()) { Text("2. Battery settings (set Navi to Unrestricted)") }
        } else {
            Text(
                "Android 13+ blocks Accessibility for sideloaded apps until you open App info, tap the ⋮ menu and choose " +
                    "\"Allow restricted settings\". Do that first, then enable Accessibility → Navi.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }, modifier = Modifier.fillMaxWidth()) { Text("1. App info (allow restricted settings)") }
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, modifier = Modifier.fillMaxWidth()) { Text("2. Accessibility settings") }
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }, modifier = Modifier.fillMaxWidth()) { Text("3. Battery settings (set Navi to Unrestricted)") }

        }

        Text("Test", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = message, onValueChange = { message = it }, label = { Text("Message to Navi") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            NaviBus.send(UserText(message))
            message = ""
        }, enabled = connection == ConnState.Connected && message.isNotBlank()) { Text("Send") }
    }
}

private fun describe(state: ConnState, running: Boolean): String = when {
    !running && state == ConnState.Idle -> "stopped"
    state == ConnState.Idle -> "starting"
    state == ConnState.Connecting -> "connecting"
    state == ConnState.Connected -> "connected"
    state is ConnState.Backoff -> "disconnected, retrying in ${state.delayMs / 1000}s"
    state is ConnState.Stopped -> when (state.reason) {
        StopReason.BAD_TOKEN -> "rejected: bad token"
        StopReason.REPLACED -> "replaced by another connection"
        StopReason.CLIENT_TOO_OLD -> "app too old for this host"
    }
    else -> "unknown"
}
