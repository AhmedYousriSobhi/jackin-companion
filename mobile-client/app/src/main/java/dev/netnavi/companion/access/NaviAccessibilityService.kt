package dev.netnavi.companion.access

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dev.netnavi.companion.bus.NaviBus
import dev.netnavi.companion.bus.trackScreenState
import dev.netnavi.companion.net.NaviLog
import dev.netnavi.companion.overlay.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Hosts the floating avatar and, from M2 on, the screen-reading/acting tools. */
class NaviAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: OverlayController? = null
    private var unregisterScreen: (() -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        NaviLog.i("accessibility service connected")
        val controller = OverlayController(this)
        overlay = controller
        controller.show()
        unregisterScreen = trackScreenState(this)
        scope.launch { NaviBus.screenOn.collect { controller.setScreenOn(it) } }
        NaviBus.executor.value = ActionExecutor()
        NaviBus.setA11yConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // M1 only needs the foreground package; trees are pulled on demand, never pushed (M2).
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg != packageName) NaviBus.reportForegroundApp(pkg)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    private fun teardown() {
        NaviBus.setA11yConnected(false)
        NaviBus.executor.value = null
        unregisterScreen?.invoke()
        unregisterScreen = null
        overlay?.hide()
        overlay = null
        scope.cancel()
    }
}
