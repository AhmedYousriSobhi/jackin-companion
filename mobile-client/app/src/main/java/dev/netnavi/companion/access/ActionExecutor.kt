package dev.netnavi.companion.access

import kotlinx.serialization.json.JsonObject

/**
 * Executes tool calls inside the accessibility service. M1 ships the full tool registry as
 * `unsupported` stubs so the protocol is complete: M2 (eyes) fills in read_ui_tree/find_elements,
 * M4 (hands) open_app, click_element, type_text, scroll, swipe and global_action.
 */
class ActionExecutor(private val tree: UiTreeSerializer = UiTreeSerializer()) : ToolExecutor {
    override suspend fun execute(name: String, args: JsonObject): ToolOutcome = when (name) {
        "read_ui_tree" -> tree.readUiTree()
        "find_elements" -> tree.findElements()
        else -> unsupported(name)
    }
}
