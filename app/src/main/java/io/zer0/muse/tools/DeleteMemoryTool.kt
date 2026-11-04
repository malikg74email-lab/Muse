package io.zer0.muse.tools

import io.zer0.memory.fact.FactStore

/**
 * v2.x: delete_memory 工具 — 助手删除一条长期记忆(事实)。
 *
 * 目标定位:优先 `id`(search_memory 结果里带回);否则用 `match` 关键词在当前
 * 作用域+空间内取最相关的一条。删除走 [FactStore.delete]:清 FTS、清知识图谱
 * 孤儿边、记录墓碑(防止后续摘要编译把已删内容"复活")。
 *
 * 作用域/空间由宿主注入的 [ToolExecutionContext] 决定;匹配到跨空间事实时拒绝,
 * 模型参数不可越界。删除不设二次确认(用户明确要求):删错了重新 save_memory 即可。
 */
object DeleteMemoryTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "delete_memory",
        description = "Delete a fact from the user's long-term memory. Use when the user says a piece of information is wrong or outdated, or asks you to forget it." +
            "Can match by the exact id (given in search_memory results) or by keyword; deletes the best-matching entry" +
            "in the current memory space. To correct a fact, delete the old one first, then save the corrected version with save_memory.",
        parameters = mapOf(
            "id" to "Optional. Exact fact id from search_memory results.",
            "match" to "Optional. Keywords to locate the fact when the id is unknown.",
        ),
        required = emptySet(),
        category = "built-in",
        parameterTypes = mapOf("id" to "integer"),
        // 与 pin_memory 同口径:长期记忆删除属隐私敏感,归 HIGH
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(args: Map<String, String>, factStore: FactStore, executionContext: ToolExecutionContext): String {
        val idArg = args["id"]?.trim()?.toLongOrNull()
        val match = args["match"]?.trim().orEmpty()
        val target = when {
            idArg != null && idArg > 0 -> factStore.getById(idArg)
            match.isNotEmpty() -> factStore.searchFullTextScoped(
                query = match,
                scope = executionContext.scope,
                spaceId = executionContext.spaceId,
                limit = 5,
            ).firstOrNull()
            else -> null
        } ?: return "No matching memory found to delete."

        // 作用域/空间校验 — 不允许借用工具越界删除其他空间/助手的事实
        if (target.scope != executionContext.scope || target.spaceId != executionContext.spaceId) {
            return "Error: the matched memory belongs to another memory space and cannot be deleted here."
        }

        val assistantId = executionContext.assistantId?.takeIf { it.isNotBlank() }
            ?: if (executionContext.scope == "main") "default" else executionContext.scope
        val removed = factStore.delete(
            id = target.id,
            assistantId = assistantId,
            scope = executionContext.scope,
            spaceId = executionContext.spaceId,
        )
        return if (removed) {
            "Deleted from long-term memory: \"${target.fact}\" (id: ${target.id})."
        } else {
            "Error: failed to delete the memory."
        }
    }
}
