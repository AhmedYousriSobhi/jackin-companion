package dev.netnavi.companion.net

import java.util.UUID
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Wire protocol v1 (spec §3). Keep in sync with host-server/app/protocol.py. */
const val PROTOCOL_VERSION = 1

object CloseCode {
    const val BAD_TOKEN = 4401
    const val REPLACED = 4409
    const val CLIENT_TOO_OLD = 4426
    const val SHUTDOWN = 1001
}

object ErrCode {
    const val STALE_REF = "stale_ref"
    const val NOT_FOUND = "not_found"
    const val BLIND_WINDOW = "blind_window"
    const val SECURE_WINDOW = "secure_window"
    const val KILL_SWITCH = "kill_switch"
    const val CANCELLED = "cancelled"
    const val TIMEOUT = "timeout"
    const val DEVICE_DISCONNECTED = "device_disconnected"
    const val UNSUPPORTED = "unsupported"
    const val DENIED = "denied"
}

val NaviJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class Envelope(
    val v: Int = PROTOCOL_VERSION,
    val id: String,
    val type: String,
    val ts: Long,
    val payload: JsonObject = JsonObject(emptyMap()),
)

// ---- client -> server ---------------------------------------------------------------------

sealed interface ClientMessage

@Serializable
data class Screen(val w: Int, val h: Int, val dpi: Int)

@Serializable
data class Hello(
    val token: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("app_version") val appVersion: String,
    val capabilities: List<String> = emptyList(),
    val programs: List<String> = emptyList(),
    val screen: Screen,
    val locale: String,
    val tz: String,
) : ClientMessage

@Serializable
data class UserText(val text: String, val source: String = "typed") : ClientMessage

@Serializable
data class Cancel(@SerialName("turn_id") val turnId: String? = null) : ClientMessage

@Serializable
data class ForegroundApp(val `package`: String, val activity: String? = null) : ClientMessage

@Serializable
data class ToolError(val code: String, val message: String = "")

@Serializable
data class ToolResult(
    @SerialName("call_id") val callId: String,
    val ok: Boolean,
    val data: JsonElement? = null,
    val error: ToolError? = null,
) : ClientMessage

@Serializable
data class ConfirmResult(@SerialName("call_id") val callId: String, val approved: Boolean) : ClientMessage

@Serializable
data class Ping(@SerialName("next_s") val nextS: Int? = null) : ClientMessage

// ---- server -> client ---------------------------------------------------------------------

sealed interface ServerMessage

@Serializable
data class HelloConfig(@SerialName("ping_s") val pingS: Int, @SerialName("delta_flush_ms") val deltaFlushMs: Int)

@Serializable
data class HelloAck(
    @SerialName("session_id") val sessionId: String,
    @SerialName("server_version") val serverVersion: String,
    @SerialName("min_client_version") val minClientVersion: String,
    val config: HelloConfig,
    @SerialName("tools_enabled") val toolsEnabled: List<String> = emptyList(),
) : ServerMessage

@Serializable
data class NaviStateMsg(val state: String) : ServerMessage

@Serializable
data class NaviEmotionMsg(val emotion: String) : ServerMessage

@Serializable
data class AssistantDelta(@SerialName("turn_id") val turnId: String, val text: String) : ServerMessage

@Serializable
data class AssistantDone(
    @SerialName("turn_id") val turnId: String,
    val text: String,
    val cancelled: Boolean = false,
) : ServerMessage

@Serializable
data class NaviSay(val text: String, val reason: String) : ServerMessage

@Serializable
data class ToolCall(
    @SerialName("call_id") val callId: String,
    val name: String,
    val args: JsonObject = JsonObject(emptyMap()),
    @SerialName("deadline_ms") val deadlineMs: Long,
) : ServerMessage

@Serializable
data class ToolCancel(@SerialName("call_id") val callId: String) : ServerMessage

@Serializable
data class ConfirmRequest(
    @SerialName("call_id") val callId: String,
    val summary: String,
    val risk: String,
    val reason: String = "",
) : ServerMessage

@Serializable
data class ErrorMsg(val code: String, val message: String = "") : ServerMessage

data object Pong : ServerMessage

/** A message type this client version does not know; ignored (forward compatible). */
data class UnknownMessage(val type: String) : ServerMessage

// ---- codec --------------------------------------------------------------------------------

fun ClientMessage.encode(
    id: String = UUID.randomUUID().toString(),
    ts: Long = System.currentTimeMillis(),
): String {
    val (type, payload) = when (this) {
        is Hello -> "hello" to enc(Hello.serializer(), this)
        is UserText -> "user_text" to enc(UserText.serializer(), this)
        is Cancel -> "cancel" to enc(Cancel.serializer(), this)
        is ForegroundApp -> "foreground_app" to enc(ForegroundApp.serializer(), this)
        is ToolResult -> "tool_result" to enc(ToolResult.serializer(), this)
        is ConfirmResult -> "confirm_result" to enc(ConfirmResult.serializer(), this)
        is Ping -> "ping" to enc(Ping.serializer(), this)
    }
    return NaviJson.encodeToString(Envelope.serializer(), Envelope(id = id, type = type, ts = ts, payload = payload))
}

private fun <T> enc(s: KSerializer<T>, v: T): JsonObject = NaviJson.encodeToJsonElement(s, v) as JsonObject

/** Parses one server envelope; returns null for malformed input or an unsupported protocol version. */
fun decodeServerMessage(raw: String): ServerMessage? {
    val env = runCatching { NaviJson.decodeFromString(Envelope.serializer(), raw) }.getOrNull() ?: return null
    if (env.v != PROTOCOL_VERSION) return null
    return runCatching {
        when (env.type) {
            "hello_ack" -> dec(HelloAck.serializer(), env)
            "navi_state" -> dec(NaviStateMsg.serializer(), env)
            "navi_emotion" -> dec(NaviEmotionMsg.serializer(), env)
            "assistant_delta" -> dec(AssistantDelta.serializer(), env)
            "assistant_done" -> dec(AssistantDone.serializer(), env)
            "navi_say" -> dec(NaviSay.serializer(), env)
            "tool_call" -> dec(ToolCall.serializer(), env)
            "tool_cancel" -> dec(ToolCancel.serializer(), env)
            "confirm_request" -> dec(ConfirmRequest.serializer(), env)
            "error" -> dec(ErrorMsg.serializer(), env)
            "pong" -> Pong
            else -> UnknownMessage(env.type)
        }
    }.getOrNull()
}

private fun <T : ServerMessage> dec(s: KSerializer<T>, env: Envelope): T = NaviJson.decodeFromJsonElement(s, env.payload)
