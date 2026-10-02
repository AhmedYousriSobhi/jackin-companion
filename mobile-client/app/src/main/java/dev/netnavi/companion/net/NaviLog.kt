package dev.netnavi.companion.net

import android.util.Log

/** All app logs use tag "Navi". Never pass tokens, UI trees or notification text at INFO. */
object NaviLog {
    private const val TAG = "Navi"
    fun i(msg: String) { Log.i(TAG, msg) }
    fun w(msg: String, t: Throwable? = null) { Log.w(TAG, msg, t) }
    fun d(msg: String) { Log.d(TAG, msg) }
}
