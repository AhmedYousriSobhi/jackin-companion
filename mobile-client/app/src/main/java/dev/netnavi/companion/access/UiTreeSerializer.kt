package dev.netnavi.companion.access

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Compact row format from spec §5.3. One snapshot = `pkg`, `hash` and a `cols` header followed by
 * array rows (no per-node JSON objects). Defaults are omitted (null), classes are short names
 * (`Btn`, `Txt`, `Edit`), `id` has the package prefix stripped and `flags` is a letter string
 * (c = clickable, e = editable, s = scrollable, f = focused, k = checked). `bounds` is sent for
 * the host's gestures only; the host never forwards it to the LLM.
 */
data class UiRow(
    val ref: Int,
    val cls: String,
    val id: String? = null,
    val text: String? = null,
    val desc: String? = null,
    val flags: String = "",
    /** "l,t,r,b" in screen pixels. */
    val bounds: String? = null,
) {
    fun toJson(): JsonArray = JsonArray(
        listOf(
            JsonPrimitive(ref),
            JsonPrimitive(cls),
            id.orNull(),
            text.orNull(),
            desc.orNull(),
            if (flags.isEmpty()) JsonNull else JsonPrimitive(flags),
            bounds.orNull(),
        ),
    )

    companion object {
        val COLS = listOf("ref", "cls", "id", "text", "desc", "flags", "bounds")
        const val MAX_TEXT_CHARS = 80
        const val MAX_NODES = 250

        private fun String?.orNull(): JsonElement = if (this == null) JsonNull else JsonPrimitive(this)
    }
}

data class UiSnapshot(val pkg: String, val hash: String, val rows: List<UiRow>) {
    fun toJson(): JsonElement = kotlinx.serialization.json.buildJsonObject {
        put("pkg", JsonPrimitive(pkg))
        put("hash", JsonPrimitive(hash))
        put("cols", JsonArray(UiRow.COLS.map(::JsonPrimitive)))
        put("rows", JsonArray(rows.map(UiRow::toJson)))
    }
}

/** M2 implements read_ui_tree / find_elements here; for M1 every call is `unsupported`. */
class UiTreeSerializer {
    suspend fun readUiTree(): ToolOutcome = unsupported("read_ui_tree")
    suspend fun findElements(): ToolOutcome = unsupported("find_elements")
}
