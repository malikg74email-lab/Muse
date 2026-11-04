package io.zer0.muse.tools

import android.content.Context

/**
 * P1-3b 拆域：快速记录工具注册器。
 *
 * 注册 quick_note_add / list / search / get / update / delete / pin。
 * 实现位于 [QuickNoteToolsImpl.kt]。
 */
class QuickNoteToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    private val impl = QuickNoteToolsImpl(context)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_add",
                description = "Add a quick note (lightweight note). The model can note down to-dos/inspiration/reference content for the user during a conversation.",
                parameters = mapOf(
                    "title" to "Required, note title",
                    "content" to "Optional, note body",
                    "tags" to "Optional, tags, multiple separated by commas",
                ),
                required = setOf("title"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.add(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_list",
                // v1.0.75 fix (工具审查 01): 原 quick_note_search 与此完全重复已删除,
                // 模型二选一赌博浪费轮次。此工具统一处理列表/过滤/搜索。
                description = "List/search quick notes. Supports filtering title/body/tags by keyword and exact filtering by tag. Use quick_note_get to see a single note's full content.",
                parameters = mapOf(
                    "keyword" to "Optional, title/body/tag filter keyword",
                    "tag" to "Optional, exact filter by tag",
                    "limit" to "Optional, maximum number to return, default 20",
                ),
                required = emptySet(),
                category = "built-in",
                parameterTypes = mapOf("limit" to "integer"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.list(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_get",
                description = "Get a single quick note by id.",
                parameters = mapOf("id" to "Required, note id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.get(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_update",
                description = "Update the specified quick note.",
                parameters = mapOf(
                    "id" to "Required, note id",
                    "title" to "Optional, new title",
                    "content" to "Optional, new body",
                    "tags" to "Optional, new tags, multiple separated by commas",
                ),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.update(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_delete",
                description = "Delete the specified quick note (moves it to the recycle bin; can be restored from there).",
                parameters = mapOf("id" to "Required, note id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.delete(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "quick_note_pin",
                description = "Pin/unpin a quick note.",
                parameters = mapOf(
                    "id" to "Required, note id",
                    "pinned" to "Required, true/false",
                ),
                required = setOf("id", "pinned"),
                category = "built-in",
                parameterTypes = mapOf("pinned" to "boolean"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.pin(args) }
    }
}
