package dev.netnavi.companion.overlay

import android.app.Service
import android.content.Context
import android.provider.Settings
import android.view.WindowManager
import dev.netnavi.companion.bus.NaviBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * `lite` flavor: no Accessibility service. The link service hosts the avatar as a plain
 * "display over other apps" window (avatar only, no screen reading or control).
 */
object OverlayHost {
    private var controller: OverlayController? = null
    private var screenJob: Job? = null

    fun hasPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** Idempotent; does nothing until the user has granted the overlay permission. */
    fun attach(service: Service, scope: CoroutineScope) {
        if (controller != null || !hasPermission(service)) return
        val c = OverlayController(service, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        c.show()
        controller = c
        screenJob = scope.launch { NaviBus.screenOn.collect { c.setScreenOn(it) } }
    }

    fun detach() {
        screenJob?.cancel()
        screenJob = null
        controller?.hide()
        controller = null
    }
}
