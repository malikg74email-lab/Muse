package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.data.knowledge.KnowledgeBaseDao
import io.zer0.muse.data.knowledge.KnowledgeBaseEntity
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.rag.RagService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 知识库管理工具集 —— 让助手能对知识库做写操作。
 *
 * 此前助手对知识库只有 knowledge_search（只读检索），删文档/删库/建库/列清单
 * 全部做不到——用户实测"叫 AI 删也没删掉，换 Agnes/DeepSeek 都不行"。
 *
 * 命名统一 `knowledge_`，与 `app_*`（应用控制）、`screen_*`（UI 自动化）并列：
 *  - `knowledge_list_docs`   列出指定知识库的全部文档（SAFE，只读）
 *  - `knowledge_delete_doc`  删除一篇文档（HIGH，走审批）
 *  - `knowledge_create_kb`   创建知识库（HIGH）
 *  - `knowledge_delete_kb`   删除知识库（HIGH，连带删其全部文档）
 *
 * 设计约束：
 *  - 不提供"改文档内容"——那是导入/重索引的职责，通过工具改正文容易引入脏数据；
 *  - 默认知识库（id=default）不可删，与 UI 层 KnowledgeScreen 的保护一致；
 *  - 删文档同时清 chunks 与向量索引（复用 RagService.deleteDocument），不留孤儿。
 */
class KnowledgeManageTools(
    private val docDao: KnowledgeDocDao,
    private val kbDao: KnowledgeBaseDao,
    private val ragService: RagService,
) {
    companion object {
        private const val TAG = "KnowledgeManageTools"
    }

    fun register(registry: ToolRegistry) {
        registerListDocs(registry)
        registerDeleteDoc(registry)
        registerCreateKb(registry)
        registerDeleteKb(registry)
    }

    /** 列出知识库的文档清单（SAFE，只读）。 */
    private fun registerListDocs(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "knowledge_list_docs",
                description = "List all user documents in the specified knowledge base (id, title, chunk count, update time)." +
                    "When kb_id is not given, lists the default knowledge base. Used to confirm a document id before deleting.",
                parameters = mapOf(
                    "kb_id" to "Optional, knowledge base id; when omitted, queries the default knowledge base",
                ),
                required = emptySet(),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val kbId = args["kb_id"]?.trim().takeUnless { it.isNullOrBlank() } ?: "default"
            val docs = kotlinx.coroutines.runBlocking { docDao.getByKbIds(listOf(kbId)) }
            if (docs.isEmpty()) {
                "There are no user documents in knowledge base $kbId."
            } else {
                docs.joinToString("\n") { doc ->
                    "${doc.id} | ${doc.title} | ${doc.chunkCount} chunks | ${doc.fileType}"
                }
            }
        }
    }

    /** 删除一篇知识库文档（HIGH，走审批）。 */
    private fun registerDeleteDoc(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "knowledge_delete_doc",
                description = "Delete a document from the knowledge base (together with its chunks and vector index)." +
                    "Confirm the document id with knowledge_list_docs before executing; irreversible.",
                parameters = mapOf(
                    "doc_id" to "Required, document id (get it with knowledge_list_docs)",
                ),
                required = setOf("doc_id"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val docId = args["doc_id"]?.trim().orEmpty()
            if (docId.isBlank()) {
                errorJson("Parameter doc_id is missing or empty")
            } else {
                kotlinx.coroutines.runBlocking {
                    val doc = docDao.getById(docId)
                    when {
                        doc == null -> errorJson("Document $docId not found")
                        doc.isInternal -> errorJson("Document $docId is an internal development document; deleting it via tools is not allowed")
                        else -> {
                            ragService.deleteDocument(docId)
                            Logger.i(TAG, "Tool deleted knowledge-base document: $docId (${doc.title})")
                            okJson("Deleted document ${doc.title} ($docId); its chunks and vector index were cleaned up together.")
                        }
                    }
                }
            }
        }
    }

    /** 创建知识库（HIGH）。 */
    private fun registerCreateKb(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "knowledge_create_kb",
                description = "Create a new knowledge base. id may be omitted (auto-generated); name is required." +
                    "The default knowledge base (id=default) cannot be created again.",
                parameters = mapOf(
                    "name" to "Required, knowledge base name",
                    "kb_id" to "Optional, knowledge base id (auto-generated when omitted)",
                    "description" to "Optional, knowledge base description",
                ),
                required = setOf("name"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val name = args["name"]?.trim().orEmpty()
            if (name.isBlank()) {
                errorJson("Parameter name is missing or empty")
            } else {
                val id = args["kb_id"]?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "kb-${System.currentTimeMillis()}"
                kotlinx.coroutines.runBlocking {
                    val existing = kbDao.getById(id)
                    if (existing != null) {
                        errorJson("Knowledge base id=$id already exists (name: ${existing.name}); please use another id or use it directly")
                    } else {
                        kbDao.upsert(
                            KnowledgeBaseEntity(
                                id = id,
                                name = name,
                                description = args["description"]?.trim().orEmpty(),
                            ),
                        )
                        Logger.i(TAG, "Tool created knowledge base: $id ($name)")
                        okJson("Created knowledge base \"$name\" (id=$id). You can now import documents into it.")
                    }
                }
            }
        }
    }

    /** 删除知识库（HIGH，连带删其全部文档与文件夹）。 */
    private fun registerDeleteKb(registry: ToolRegistry) {
        registry.register(
            ToolRegistry.ToolDef(
                name = "knowledge_delete_kb",
                description = "Delete a knowledge base and all its documents, folders and vector indexes. Irreversible." +
                    "The default knowledge base (id=default) cannot be deleted.",
                parameters = mapOf(
                    "kb_id" to "Required, knowledge base id (confirm first with knowledge_list_docs)",
                ),
                required = setOf("kb_id"),
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args ->
            val kbId = args["kb_id"]?.trim().orEmpty()
            when {
                kbId.isBlank() -> errorJson("Parameter kb_id is missing or empty")
                kbId == "default" -> errorJson("The default knowledge base cannot be deleted (consistent with the UI-layer protection)")
                else -> {
                    kotlinx.coroutines.runBlocking {
                        val kb = kbDao.getById(kbId)
                        if (kb == null) {
                            errorJson("Knowledge base $kbId not found")
                        } else {
                            val docs = docDao.getByKbIds(listOf(kbId))
                            docs.forEach { doc -> ragService.deleteDocument(doc.id) }
                            kbDao.delete(kbId)
                            Logger.i(TAG, "Tool deleted knowledge base: $kbId (${kb.name}), ${docs.size} documents")
                            okJson("Deleted knowledge base \"${kb.name}\" ($kbId) and its ${docs.size} documents.")
                        }
                    }
                }
            }
        }
    }

    private fun okJson(message: String): String = buildJsonObject {
        put("ok", JsonPrimitive(true))
        put("message", JsonPrimitive(message))
    }.toString()

    private fun errorJson(message: String): String = buildJsonObject {
        put("ok", JsonPrimitive(false))
        put("error", JsonPrimitive(message))
    }.toString()
}

/** 注册器：走 Koin 注入，由 AutomationInitializer 统一引导。 */
class KnowledgeManageToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val tools: KnowledgeManageTools,
) {
    init {
        tools.register(toolRegistry)
    }
}
