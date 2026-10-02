package dev.netnavi.companion.overlay

import android.app.Service
import android.content.Context
import kotlinx.coroutines.CoroutineScope

/** `full` flavor: the accessibility service hosts the overlay, so the link service does nothing. */
object OverlayHost {
    fun hasPermission(context: Context): Boolean = true
    fun attach(service: Service, scope: CoroutineScope) = Unit
    fun detach() = Unit
}
