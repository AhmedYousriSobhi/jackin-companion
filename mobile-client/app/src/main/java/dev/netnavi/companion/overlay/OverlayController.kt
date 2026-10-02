package dev.netnavi.companion.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.netnavi.companion.bus.NaviBus
import dev.netnavi.companion.bus.NaviMode
import dev.netnavi.companion.net.ConnState
import kotlinx.coroutines.flow.combine

/**
 * Owns the floating avatar window. The `full` flavor passes TYPE_ACCESSIBILITY_OVERLAY (no "draw over
 * apps" permission needed, spec §2); `lite` passes TYPE_APPLICATION_OVERLAY. Main thread only.
 */
class OverlayController(private val context: Context, private val windowType: Int) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val owner = OverlayLifecycleOwner()
    private var view: ComposeView? = null
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        windowType,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 24
        y = 400
    }

    fun show() {
        if (view != null) return
        owner.create()
        val v = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setContent { OverlayContent(onDrag = ::moveBy) }
        }
        wm.addView(v, params)
        view = v
        owner.resume()
    }

    /** Screen off -> STOPPED so animations stop; screen on -> resume. */
    fun setScreenOn(on: Boolean) {
        if (view == null) return
        if (on) owner.resume() else owner.stop()
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        owner.destroy()
    }

    private fun moveBy(dx: Float, dy: Float) {
        val v = view ?: return
        params.x += dx.toInt()
        params.y += dy.toInt()
        wm.updateViewLayout(v, params)
    }
}

/** Derives the avatar mode from the bus: kill switch > connection > what the host says. */
@Composable
fun currentAvatarMode(): AvatarMode {
    val connection by NaviBus.connection.collectAsState()
    val mode by NaviBus.naviMode.collectAsState()
    val killed by NaviBus.killSwitch.collectAsState()
    return when {
        killed -> AvatarMode.KILLED
        connection != ConnState.Connected -> AvatarMode.DISCONNECTED
        mode == NaviMode.PROCESSING -> AvatarMode.PROCESSING
        mode == NaviMode.TALKING -> AvatarMode.TALKING
        else -> AvatarMode.IDLE
    }
}

/** Tap re-arms a engaged kill switch; long-press engages it. Shared by the overlay and the in-app avatar. */
@Composable
fun Modifier.killSwitchGestures(): Modifier {
    val view = LocalView.current
    return pointerInput(Unit) {
        detectTapGestures(
            onTap = { if (NaviBus.killSwitch.value) NaviBus.rearmKillSwitch() },
            onLongPress = {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                NaviBus.engageKillSwitch()
            },
        )
    }
}

@Composable
private fun OverlayContent(onDrag: (Float, Float) -> Unit) {
    NaviAvatar(
        mode = currentAvatarMode(),
        modifier = Modifier
            .killSwitchGestures()
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x, drag.y)
                }
            },
    )
}
