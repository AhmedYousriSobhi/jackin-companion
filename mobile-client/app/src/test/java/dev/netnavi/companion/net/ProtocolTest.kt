package dev.netnavi.companion.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun payloadOf(msg: ClientMessage): Pair<String, JsonObject> {
        val env = NaviJson.decodeFromString(Envelope.serializer(), msg.encode(id = "id1", ts = 123))
        assertEquals(1, env.v)
        assertEquals("id1", env.id)
        assertEquals(123L, env.ts)
        return env.type to env.payload
    }

    private fun server(type: String, payload: String) =
        decodeServerMessage("""{"v":1,"id":"i","type":"$type","ts":1,"payload":$payload}""")

    @Test fun helloUsesSnakeCaseWireNames() {
        val (type, p) = payloadOf(
            Hello("tok", "dev", "0.1.0", listOf("a11y_overlay"), listOf("core"), Screen(1080, 2400, 420), "en-US", "Europe/Berlin"),
        )
        assertEquals("hello", type)
        assertEquals("dev", p["device_id"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", p["app_version"]!!.jsonPrimitive.content)
        assertEquals(1080, p["screen"]!!.jsonObject["w"]!!.jsonPrimitive.content.toInt())
    }

    @Test fun clientMessageTypes() {
        assertEquals("user_text", payloadOf(UserText("hi")).first)
        assertEquals("cancel", payloadOf(Cancel()).first)
        assertEquals("foreground_app", payloadOf(ForegroundApp("com.x")).first)
        assertEquals("confirm_result", payloadOf(ConfirmResult("c", true)).first)
        assertEquals("ping", payloadOf(Ping(nextS = 120)).first)
    }

    @Test fun nullsAreOmittedOnTheWire() {
        val (_, cancel) = payloadOf(Cancel())
        assertTrue(cancel.isEmpty())
        val (_, ping) = payloadOf(Ping())
        assertTrue(ping.isEmpty())
        val (_, ok) = payloadOf(ToolResult("c1", ok = true, data = buildJsonObject { put("x", JsonPrimitive(1)) }))
        assertTrue("error" !in ok)
    }

    @Test fun toolResultErrorShape() {
        val (type, p) = payloadOf(ToolResult("c1", ok = false, error = ToolError(ErrCode.KILL_SWITCH, "off")))
        assertEquals("tool_result", type)
        assertEquals("c1", p["call_id"]!!.jsonPrimitive.content)
        assertEquals("kill_switch", p["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test fun decodesHelloAck() {
        val m = server(
            "hello_ack",
            """{"session_id":"s","server_version":"0.1.0","min_client_version":"0.1.0","config":{"ping_s":25,"delta_flush_ms":50},"tools_enabled":["wait"]}""",
        ) as HelloAck
        assertEquals(25, m.config.pingS)
        assertEquals(listOf("wait"), m.toolsEnabled)
    }

    @Test fun decodesAllServerMessages() {
        assertEquals(NaviStateMsg("talking"), server("navi_state", """{"state":"talking"}"""))
        assertEquals(NaviEmotionMsg("happy"), server("navi_emotion", """{"emotion":"happy"}"""))
        assertEquals(AssistantDelta("t", "he"), server("assistant_delta", """{"turn_id":"t","text":"he"}"""))
        assertEquals(AssistantDone("t", "hey", true), server("assistant_done", """{"turn_id":"t","text":"hey","cancelled":true}"""))
        assertEquals(NaviSay("yo", "reminder"), server("navi_say", """{"text":"yo","reason":"reminder"}"""))
        assertEquals(ToolCancel("c"), server("tool_cancel", """{"call_id":"c"}"""))
        assertEquals(ConfirmRequest("c", "Pay?", "high", "kw"), server("confirm_request", """{"call_id":"c","summary":"Pay?","risk":"high","reason":"kw"}"""))
        assertEquals(ErrorMsg("busy", "m"), server("error", """{"code":"busy","message":"m"}"""))
        assertEquals(Pong, server("pong", "{}"))
        val call = server("tool_call", """{"call_id":"c","name":"read_ui_tree","args":{"since":"h"},"deadline_ms":10000}""") as ToolCall
        assertEquals("read_ui_tree", call.name)
        assertEquals(10_000L, call.deadlineMs)
        assertEquals("h", call.args["since"]!!.jsonPrimitive.content)
    }

    @Test fun unknownTypesAreToleratedAndBadInputRejected() {
        assertEquals(UnknownMessage("future_thing"), server("future_thing", "{}"))
        assertNull(decodeServerMessage("not json"))
        assertNull(decodeServerMessage("""{"v":2,"id":"i","type":"pong","ts":1,"payload":{}}"""))
        assertNull(server("navi_state", """{"nope":1}"""))
    }

    @Test fun unknownFieldsAreIgnored() {
        assertEquals(NaviStateMsg("idle"), server("navi_state", """{"state":"idle","extra":true}"""))
    }
}
