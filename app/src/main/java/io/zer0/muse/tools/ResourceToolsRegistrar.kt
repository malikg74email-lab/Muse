package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.tools.resource.ResourceLibraryStore

/**
 * P1-3b 拆域：资源库工具注册器。
 *
 * 注册 resource_add / resource_list / resource_search / resource_get / resource_delete。
 * 实现位于 [ResourceToolsImpl.kt]。
 */
class ResourceToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
    resourceLibrary: ResourceLibraryStore = ResourceLibraryStore(context),
) {
    private val impl = ResourceToolsImpl(context, resourceLibrary)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "resource_add",
                description = "Add a resource to the resource library (note/prompt/reference content, etc.).",
                parameters = mapOf(
                    "title" to "Required, resource title",
                    "content" to "Required, resource body",
                    "tags" to "Optional, tags, multiple separated by commas",
                ),
                required = setOf("title", "content"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.add(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "resource_list",
                description = "List the resources in the resource library, filterable by keyword.",
                parameters = mapOf(
                    "keyword" to "Optional, title/body/tag filter keyword",
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
                name = "resource_search",
                description = "Search the resource library (same behaviour as resource_list's keyword filtering).",
                parameters = mapOf(
                    "keyword" to "Optional, search keyword",
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
                name = "resource_get",
                description = "Get a single resource from the resource library by id.",
                parameters = mapOf("id" to "Required, resource id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.get(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "resource_delete",
                description = "Delete a resource from the resource library by id.",
                parameters = mapOf("id" to "Required, resource id"),
                required = setOf("id"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.delete(args) }
    }
}
