package dev.netnavi.companion.overlay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * A ComposeView added straight to the WindowManager has no Activity to supply a lifecycle, so we
 * provide one (spec §10). Call these on the main thread.
 */
class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun create() {
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    /** Visible and animating. */
    fun resume() { registry.currentState = Lifecycle.State.RESUMED }

    /** Screen off: STOPPED pauses Compose's frame clock so infinite animations stop. */
    fun stop() { registry.currentState = Lifecycle.State.CREATED }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }
}
