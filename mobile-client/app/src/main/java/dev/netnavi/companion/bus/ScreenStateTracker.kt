package dev.netnavi.companion.bus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager

/** Mirrors screen on/off into [NaviBus.screenOn]. Returns a function that unregisters. */
fun trackScreenState(context: Context): () -> Unit {
    val app = context.applicationContext
    val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
    NaviBus.setScreenOn(pm.isInteractive)
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            NaviBus.setScreenOn(intent.action == Intent.ACTION_SCREEN_ON)
        }
    }
    app.registerReceiver(
        receiver,
        IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        },
    )
    return { runCatching { app.unregisterReceiver(receiver) } }
}
