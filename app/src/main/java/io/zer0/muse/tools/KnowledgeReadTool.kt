package io.zer0.muse.tools

import io.zer0.muse.data.knowledge.KnowledgeDocDao
import kotlinx.coroutines.runBlocking

/**
 * v2.6.2: 按 docId 读取知识库文档全文。
 *
 * 此前助手对知识库只有 knowledge_search（向量检索，返回 topK 片段），对一篇
 * 195 页的 PDF 只能看到零散碎片，无法完整理解文档结构或按需阅读全文。
 *
 * 用法：先用 knowledge_list_docs 确认文档 id，再用本工具读全文。
 * 超长文档按 offset 分段读取（每次最多 [MAX_CHARS_PER_READ] 字符），避免
 * 一次性把 195 页塞进上下文窗口。
 *
 * 内部文档（is_internal=1）不允许通过本工具读取——那是 devdoc，与用户知识库隔离。
 */
object KnowledgeReadTool {

    const val TOOL_NAME = "knowledge_read_doc"

    /** 单次读取上限。195 页 PDF ≈ 50 万字符，分多次读。 */
    private const val MAX_CHARS_PER_READ = 30_000

    fun toolDef() = ToolRegistry.ToolDef(
        name = TOOL_NAME,
        description = "Read the full text of a knowledge-base document. Very long documents are segmented by offset (at most 30000 characters each time);" +
            "first read the first segment without passing offset; the return's end gives the remaining character count and the next offset." +
            "Document ids can be obtained with knowledge_list_docs.",
        parameters = mapOf(
            "doc_id" to "Required, document id (obtainable with knowledge_list_docs)",
            "offset" to "Optional, starting character offset; starts from 0 when omitted",
        ),
        required = setOf("doc_id"),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    fun executeFromArgs(args: Map<String, String>, docDao: KnowledgeDocDao): String {
        val docId = args["doc_id"]?.trim().orEmpty()
        if (docId.isBlank()) return "Error: doc_id is required."
        val offset = args["offset"]?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

        return runBlocking {
            val doc = docDao.getById(docId)
            when {
                doc == null -> "Error: document $docId not found. Use knowledge_list_docs to see the document list."
                doc.isInternal -> "Error: document $docId is an internal development document and may not be read through this tool."
                doc.content.isBlank() -> "Document ${doc.title} ($docId) has an empty body."
                else -> {
                    val content = doc.content
                    val total = content.length
                    if (offset >= total) {
                        "Already beyond the end of the document (total length $total characters, requested offset=$offset)."
                    } else {
                        val end = minOf(offset + MAX_CHARS_PER_READ, total)
                        val chunk = content.substring(offset, end)
                        val remaining = total - end
                        buildString {
                            appendLine("'${doc.title}' ($docId) — characters ${offset + 1}-$end / $total")
                            if (remaining > 0) {
                                appendLine("[There are $remaining characters left; continue reading with offset=$end]")
                            } else {
                                appendLine("[End of document]")
                            }
                            appendLine("---")
                            append(chunk)
                        }
                    }
                }
            }
        }
    }
}

/** 注册器：走 Koin 注入，由 AutomationInitializer 统一引导。 */
class KnowledgeReadToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val docDao: KnowledgeDocDao,
) {
    init {
        toolRegistry.register(KnowledgeReadTool.toolDef()) { args ->
            KnowledgeReadTool.executeFromArgs(args, docDao)
        }
    }
}
