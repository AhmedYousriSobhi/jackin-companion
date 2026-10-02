package dev.netnavi.companion.access

import dev.netnavi.companion.net.ErrCode
import dev.netnavi.companion.net.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

sealed interface ToolOutcome {
    data class Ok(val data: JsonElement) : ToolOutcome
    data class Err(val code: String, val message: String = "") : ToolOutcome
}

/** Runs a `tool_call` on the phone. Implemented by [ActionExecutor] inside the accessibility service. */
interface ToolExecutor {
    suspend fun execute(name: String, args: JsonObject): ToolOutcome
}

fun ToolOutcome.toResult(callId: String): ToolResult = when (this) {
    is ToolOutcome.Ok -> ToolResult(callId = callId, ok = true, data = data)
    is ToolOutcome.Err -> ToolResult(
        callId = callId,
        ok = false,
        error = dev.netnavi.companion.net.ToolError(code, message),
    )
}

fun unsupported(what: String) = ToolOutcome.Err(ErrCode.UNSUPPORTED, "$what is not implemented yet")
