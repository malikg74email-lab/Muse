package io.zer0.muse.tools

import android.content.Context
import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.groupchat.GroupChatRepository
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.skill.SkillEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient

/**
 * Phase 8.8: Skill 执行器(Kotlin 直实现,不用 QuickJS)。
 *
 * 根据 [SkillEntity.implementationKotlin] 路由到预定义的 Kotlin 函数执行。
 * 当前支持 4 个内置 skill:
 *  - read_file — 读取应用沙盒内文件
 *  - write_file — 写入应用沙盒内文件
 *  - http_get — HTTP GET 请求
 *  - http_post — HTTP POST 请求
 *
 * 设计原则:
 *  - 文件操作限定在应用沙盒(filesDir/cacheDir),避免越权读写
 *  - HTTP 请求设 30s 超时,响应体最大 1MB
 *  - 所有 IO 在 IO Dispatcher 执行
 *
 * @param context 应用 Context(用于 filesDir / cacheDir)
 * @param client OkHttpClient(http_get / http_post 用,由 Koin 注入 named("chat"))
 */
class SkillExecutor(
    private val context: Context,
    private val client: OkHttpClient,
    /** v0.24: web_search 用(可为 null,测试时不注入)。 */
    private val webSearchService: io.zer0.muse.web.WebSearchService? = null,
    /** v0.24: knowledge_search 用(可为 null)。 */
    private val knowledgeDocDao: io.zer0.muse.data.knowledge.KnowledgeDocDao? = null,
    /** v0.24: install_skill 用(可为 null,避免循环依赖)。 */
    private val skillRepository: io.zer0.muse.data.skill.SkillRepository? = null,
    /** v0.46: delegate_agent 用 — 调子助手跑一轮 LLM。 */
    private val chatService: ChatService,
    /** v0.46: delegate_agent 用 — 根据 assistantId 取子助手配置。 */
    private val assistantRepository: AssistantRepository,
    /** v1.30: 群聊工具(channel_reply / channel_pass / channel_read_context)用。 */
    private val groupChatRepository: GroupChatRepository? = null,
    /** v1.54: knowledge_search 向量检索用(可为 null,降级到 LIKE 搜索)。 */
    private val ragService: io.zer0.muse.rag.RagService? = null,
    /** v1.54: 获取 RAG 配置(用于向量检索的 embedding provider 选择)。 */
    private val ragConfigProvider: suspend () -> io.zer0.muse.rag.RagConfig = { io.zer0.muse.rag.RagConfig() },
    /** v1.95: 表情包工具(list_stickers / send_sticker)用。 */
    private val stickerLibraryRepository: io.zer0.muse.data.sticker.StickerLibraryRepository? = null,
    /** v1.???: generate_image 用。 */
    private val imageService: io.zer0.ai.image.ImageService? = null,
    private val imageDrawConfigProvider: suspend () -> Pair<io.zer0.ai.core.ProviderConfig?, String?> = { null to null },
    /** v1.200: 多 Agent 团队配置提供,用于 delegateAgent 处理 TEAM 目标。 */
    private val multiAgentConfigProvider: () -> io.zer0.muse.data.MultiAgentConfig = { io.zer0.muse.data.MultiAgentConfig() },
    /** v1.201: LLM 综合评审聚合器,TeamWorkflowExecutor 的 LLM_REVIEW 策略时使用;为 null 时降级为 EXPERT_REVIEW。 */
    private val llmAggregator: LlmAggregator? = null,
    /** v1.201: 委派暂停管理器,null 时跳过所有暂停点。 */
    private val pauseManager: DelegationPauseManager? = null,
    /** v1.201: 委派链路追踪器,null 时不记录链路。ChatViewModel 共享同一实例用于 UI 展示。 */
    private val delegationChainTracker: DelegationChainTracker? = null,
    /** v1.202: Agent 间私信仓库,用于委派完成后把结果回填为一条 sub-agent → main-agent 的 DM。
     *  为 null 时不发送 DM(测试环境或未注入时降级)。 */
    private val agentDmRepository: io.zer0.muse.data.agentdm.AgentDmRepository? = null,
    /** v1.202: 异步委派结果回灌(非阻塞委派核心基础设施)。
     *  为 null 时 nonBlocking=true 自动降级为阻塞模式。 */
    private val deferredResultStore: DeferredResultStore? = null,
    /** v1.202: 子 agent 线程管理器(可续接的子 agent 会话线程,串行执行避免并发竞争)。
     *  为 null 时 nonBlocking=true 自动降级为阻塞模式。 */
    private val subagentThreadStore: io.zer0.muse.data.subagent.SubagentThreadStore? = null,
    /** v1.0.53: 子 agent 全局并发限流器(Koin 注入)。所有委派入口(TeamWorkflowExecutor 并行节点 / delegateAgent nonBlocking / SubagentRunner)共享同一配额。 */
    private val agentConcurrencyLimiter: AgentConcurrencyLimiter,
    /** v1.0.53 Phase 2: 工作流断点恢复日志(Koin 注入);null 时 TeamWorkflowExecutor 跳过 journal 读写。 */
    private val journal: WorkflowJournal? = null,
    /**
     * v1.0.53 Phase 5: GroupChatScheduler 懒加载 provider(避免与 SkillExecutor 循环依赖)。
     *
     * agent_phone 工具触发 whisper 私聊时调用 [io.zer0.muse.schedule.GroupChatScheduler.launchWhisper]。
     * 用 lambda 形式延迟解析:SkillExecutor 在 Koin 中先于 GroupChatScheduler 注册,
     * 但 lambda 体内 get() 在工具实际执行时才解析,此时 GroupChatScheduler 已初始化完成。
     *
     * 为 null 时(测试环境)agent_phone 工具返回"未配置"提示。
     */
    private val groupChatSchedulerProvider: (() -> io.zer0.muse.schedule.GroupChatScheduler?)? = null,
    /** B6-01: 外部插件管理器(可为 null,测试环境不注入)。 */
    private val pluginManager: PluginManager? = null,
    /** P1-3b: 文件/公共目录工具实现(可为 null,测试环境不注入)。 */
    private val fileTools: SkillFileToolsImpl? = null,
    /** P1-3b: 搜索/HTTP 工具实现(可为 null,测试环境不注入)。 */
    private val searchTools: SkillSearchToolsImpl? = null,
    /** P1-3b: Skill 管理工具实现(可为 null,测试环境不注入)。 */
    private val managementTools: SkillManagementToolsImpl? = null,
    /** P1-3b: 媒体/JS/插件工具实现(可为 null,测试环境不注入)。 */
    private val mediaTools: SkillMediaToolsImpl? = null,
    /** P1-3b: 翻译工具实现(可为 null,测试环境不注入)。 */
    /** P1-3e: Agent 工作流/群聊工具实现(可为 null,测试环境不注入)。 */
    private val agentTools: SkillAgentToolsImpl? = null,
    /** P1-3e: delegateAgent 实现(可为 null,测试环境不注入)。 */
    private val delegateTools: SkillDelegateAgentImpl? = null,
    private val translateTools: TranslateToolsImpl? = null,
    /** 助手自写插件工具实现(可为 null,测试环境不注入)。 */
    private val pluginAuthoringTools: PluginAuthoringToolsImpl? = null,
) {
    /**
     * 执行 skill。
     * @param skill skill 实体(含 implementationKotlin 路由 key)
     * @param argumentsJson LLM 传来的参数 JSON 字符串
     * @param onProgress v0.49: 进度回调,在耗时工具(web_fetch/web_search/delegate_agent/install_skill)
     *                   执行前调用,供 UI 在 TaskStep 上显示"正在搜索..."等进度文本。默认空实现,不破坏现有调用。
     * @return 执行结果字符串
     */
    suspend fun execute(
        skill: SkillEntity,
        argumentsJson: String,
        onProgress: (String) -> Unit = {},
        turnKey: String = "default",
        sessionId: String = "default",
    ): String = withContext(Dispatchers.IO) {
        val args = ToolArgsParser.parse(argumentsJson, skill.id)
        // H-SE1: 改用 resultOf{}(正确重抛 CancellationException),避免 runCatching 吞协程取消信号
        resultOf {
            when (skill.implementationKotlin) {
                "read_file" -> fileTools?.execReadFile(args) ?: context.getString(R.string.skill_impl_not_configured)
                "write_file" -> fileTools?.execWriteFile(args) ?: context.getString(R.string.skill_impl_not_configured)
                "http_get" -> searchTools?.execHttpGet(args) ?: context.getString(R.string.skill_impl_not_configured)
                "http_post" -> searchTools?.execHttpPost(args) ?: context.getString(R.string.skill_impl_not_configured)
                // v0.24: 搜索与信息获取类
                "web_search" -> {
                    onProgress(context.getString(R.string.skill_progress_searching))
                    searchTools?.execWebSearch(args, turnKey) ?: context.getString(R.string.skill_impl_not_configured)
                }
                "web_fetch" -> {
                    onProgress(context.getString(R.string.skill_progress_fetching))
                    searchTools?.execWebFetch(args) ?: context.getString(R.string.skill_impl_not_configured)
                }
                "knowledge_search" -> searchTools?.execKnowledgeSearch(args) ?: context.getString(R.string.skill_impl_not_configured)
                "arxiv_search" -> searchTools?.execArxivSearch(args) ?: context.getString(R.string.skill_impl_not_configured)
                // v0.24: 自我扩展(install_skill = LLM 生成 skill 定义入库)
                "install_skill" -> {
                    onProgress(context.getString(R.string.skill_progress_installing))
                    managementTools?.installSkill(args) ?: context.getString(R.string.skill_impl_not_configured)
                }
                // 助手自写插件:只产出未签名草稿,用户在插件管理里签名并启用后才可执行
                "author_plugin" -> {
                    onProgress(context.getString(R.string.plugin_author_progress))
                    pluginAuthoringTools?.authorPlugin(args) ?: context.getString(R.string.skill_impl_not_configured)
                }
                // v0.46: 多 Agent 协作(委托子助手执行任务)
                "delegate_agent" -> {
                    onProgress(context.getString(R.string.skill_progress_delegating))
                    execDelegateAgent(args)
                }
                // v1.55: Agent 工作流(结构化任务计划)
                "task_plan" -> {
                    onProgress(context.getString(R.string.skill_progress_planning))
                    agentTools?.execTaskPlan(args, sessionId) ?: context.getString(R.string.skill_impl_not_configured)
                }
                "update_plan_step" -> agentTools?.execUpdatePlanStep(args, sessionId) ?: context.getString(R.string.skill_impl_not_configured)
                // v1.30: 群聊工具(多 Agent 群聊中发言/跳过/读取上下文)
                "channel_reply" -> agentTools?.execChannelReply(args) ?: context.getString(R.string.skill_impl_not_configured)
                "channel_pass" -> agentTools?.execChannelPass(args) ?: context.getString(R.string.skill_impl_not_configured)
                "channel_read_context" -> agentTools?.execChannelReadContext(args) ?: context.getString(R.string.skill_impl_not_configured)
                // v1.0.53 Phase 5: Agent Phone(主 agent 触发群聊成员私聊)
                "agent_phone" -> {
                    onProgress(context.getString(R.string.skill_progress_whispering))
                    agentTools?.execAgentPhone(args) ?: context.getString(R.string.skill_impl_not_configured)
                }
                // 文件管理类
                "list_dir" -> fileTools?.execListDir(args) ?: context.getString(R.string.skill_impl_not_configured)
                "delete_file" -> fileTools?.execDeleteFile(args) ?: context.getString(R.string.skill_impl_not_configured)
                "file_exists" -> fileTools?.execFileExists(args) ?: context.getString(R.string.skill_impl_not_configured)
                // 公共目录与文件传输类
                "file_download" -> fileTools?.execFileDownload(args) ?: context.getString(R.string.skill_impl_not_configured)
                "read_public_file" -> fileTools?.execReadPublicFile(args) ?: context.getString(R.string.skill_impl_not_configured)
                "save_to_downloads" -> fileTools?.execSaveToDownloads(args) ?: context.getString(R.string.skill_impl_not_configured)
                "list_public_files" -> fileTools?.execListPublicFiles(args) ?: context.getString(R.string.skill_impl_not_configured)
                // Skill 管理类
                "list_skills" -> managementTools?.listSkills(args) ?: context.getString(R.string.skill_impl_not_configured)
                "uninstall_skill" -> managementTools?.uninstallSkill(args) ?: context.getString(R.string.skill_impl_not_configured)
                "disable_skill" -> managementTools?.disableSkill(args) ?: context.getString(R.string.skill_impl_not_configured)
                "enable_skill" -> managementTools?.enableSkill(args) ?: context.getString(R.string.skill_impl_not_configured)
                "update_skill" -> managementTools?.updateSkill(args) ?: context.getString(R.string.skill_impl_not_configured)
                // v1.95: 表情包库工具
                "list_stickers" -> mediaTools?.execListStickers(args) ?: context.getString(R.string.skill_impl_not_configured)
                "send_sticker" -> mediaTools?.execSendSticker(args) ?: context.getString(R.string.skill_impl_not_configured)
                // 新增工具
                "generate_image" -> {
                    onProgress(context.getString(R.string.skill_progress_generating_image))
                    mediaTools?.execGenerateImage(args) ?: context.getString(R.string.skill_impl_not_configured)
                }
                "translate" -> translateTools?.execTranslate(args) ?: context.getString(R.string.skill_impl_not_configured)
                "generate_qr" -> mediaTools?.execGenerateQr(args) ?: context.getString(R.string.skill_impl_not_configured)
                // JS 沙盒:让 LLM 能在 Skill 体系里执行 JavaScript 代码
                // (主入口为 ToolRegistry.execute_javascript,此处为 SkillExecutor 路由分支,供 skill 调用)
                "execute_javascript" -> mediaTools?.execExecuteJavascript(args) ?: context.getString(R.string.skill_impl_not_configured)
                // 提示词技能:不跑任何 Kotlin 实现,只把编码的指令文本交回模型(调用参数一律忽略)
                else ->
                    if (SkillImporter.isPromptSkill(skill.implementationKotlin)) {
                        managementTools?.execPromptSkill(skill, args) ?: context.getString(R.string.skill_impl_not_configured)
                    } else if (skill.implementationKotlin.startsWith("plugin:")) {
                        mediaTools?.execPluginTool(skill, argumentsJson) ?: context.getString(R.string.skill_impl_not_configured)
                    } else {
                        context.getString(R.string.skill_unknown_impl, skill.implementationKotlin)
                    }
            }
        }.onError { msg, t ->
            Logger.e("SkillExecutor", "skill ${skill.id} execution failed: $msg", t)
        }.getOrNull() ?: context.getString(R.string.skill_exec_exception)
    }

    /** v1.55: 供 ChatViewModel 读取活跃计划。 */
    fun getActivePlans(): Map<String, io.zer0.muse.ui.taskcard.AgentPlan> = agentTools?.getActivePlans().orEmpty()

    fun getActivePlans(sessionId: String): Map<String, io.zer0.muse.ui.taskcard.AgentPlan> = agentTools?.getActivePlans(sessionId).orEmpty()

    /** 将历史恢复的计划回灌给当前会话的 task_plan/update_plan_step 执行器。 */
    fun restoreActivePlans(plans: Map<String, io.zer0.muse.ui.taskcard.AgentPlan>, sessionId: String = "default") {
        agentTools?.restoreActivePlans(plans, sessionId)
    }

    /**
     * v2.6.4: 收尾未完成计划（把 PENDING / IN_PROGRESS 步骤落成 CANCELLED）。
     * 工具循环退出时调用，保证计划卡不会永远停在中途。
     */
    fun settleUnfinishedPlans(sessionId: String = "default"): Map<String, io.zer0.muse.ui.taskcard.AgentPlan> =
        agentTools?.settleUnfinishedPlans(sessionId).orEmpty()

    // v1.0.81: parseArgs 已抽取为 ToolArgsParser（可单测），不再在 SkillExecutor 内联。

    // ── 内置 skill 实现 ──────────────────────────────────────────────────────

    /** 读取应用沙盒内文件(限定 filesDir / cacheDir 子路径)。 */
    private fun validatePublicUrl(url: String): Boolean {
        val uri =
            try {
                java.net.URI(url)
            } catch (e: Exception) {
                return false
            }
        val host = uri.host?.lowercase() ?: return false
        if (host == "localhost") return false
        // 解析 DNS 后二次校验 IP(防 DNS rebinding):只要任一解析结果指向内网就拒绝
        val addresses =
            try {
                java.net.InetAddress.getAllByName(host)
            } catch (e: Exception) {
                return false
            }
        return addresses.all { addr ->
            // IPv4 私网/回环/链路本地等由 InetAddress 内置方法覆盖
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress ||
                addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
                addr.isMulticastAddress
            ) {
                return@all false
            }
            // IPv6 私网 fc00::/7(InetAddress.isSiteLocalAddress 对 IPv6 返回 false,需手动判断)
            if (addr is java.net.Inet6Address) {
                val bytes = addr.address
                // fc00::/7 的前 7 位是 1111110,即首字节范围 0xfc..0xfd
                if ((bytes[0].toInt() and 0xFE) == 0xFC) return@all false
            }
            true
        }
    }

    /** HTTP GET 请求。失败时(404/超时/连接失败)降级到搜索摘要;401/403 等业务错误不降级。 */

    /**
     * install_skill — LLM 自己生成 skill 定义并入库(Phase 2 自我扩展)。
     *
     * LLM 输出 .skill.json 格式的 skill 定义字符串,经 [SkillImporter.parse] 校验后入库。
     * 安全约束:implementationKotlin 必须是 4 个内置实现之一,不支持任意代码执行。
     */
    suspend fun delegateAgent(
        request: DelegationContract.DelegationRequest,
        policy: DelegationPauseManager.PausePolicy = DelegationPauseManager.PausePolicy(),
    ): DelegationContract.DelegationResult {
        return delegateTools?.delegateAgent(request, policy)
            ?: DelegationContract.DelegationResult(
                requestId = request.requestId,
                success = false,
                error = "delegateAgent not configured",
                metadata =
                DelegationContract.DelegationResult.ResultMetadata(
                    startedAt = System.currentTimeMillis(),
                    finishedAt = System.currentTimeMillis(),
                    durationMs = 0,
                ),
            )
    }

    /**
     * v0.46: 多 Agent 协作 — 把任务委托给指定子助手执行。
     *
     * LLM 通过 tool_call 触发,参数:
     *  - assistantId: 子助手 id(必填,如 "researcher"/"writer" 等)
     *  - task: 任务描述(必填,自然语言)
     *  - context: 可选上下文(可选,补充信息)
     *
     * 执行流程:
     *  1. 根据 assistantId 从 AssistantRepository 取子助手配置
     *  2. 用子助手的 systemPrompt + 任务描述构造消息列表
     *  3. 调用 ChatService.completeText 跑一轮(temperature/maxTokens 用子助手配置)
     *  4. 返回子助手生成的文本给主 LLM
     *
     * 注意:
     *  - 子助手独立调用 LLM,不走主 launchStream 的流式回环
     *  - 失败时返回错误信息字符串(主 LLM 能看到错误并决定是否重试)
     *  - 不递归(子助手不能再调 delegate_agent,避免无限嵌套)
     */
    @Deprecated("Use delegateAgent instead", ReplaceWith("delegateAgent"))
    private suspend fun execDelegateAgent(args: Map<String, String>): String {
        // v1.202 改造 1: 统一委派入口 — execDelegateAgent 是 LLM tool_call "delegate_agent" 的入口,
        // 但此前不通知 DelegationChainTracker,导致 LLM 委派在 UI 链路卡片中不可见。
        // 现在委托到结构化 delegateAgent,自动获得链路追踪 + 暂停点 + DM 集成 + 子 agent 工具调用。
        // 注意:execDelegateAgent 返回字符串(tool result),delegateAgent 返回 DelegationResult,
        // 这里把 result.resultText 作为字符串返回。失败时返回 result.error 让主 LLM 看到错误。
        val assistantId =
            args["assistantId"]?.trim()
                ?: return context.getString(R.string.skill_missing_param_assistant_id)
        val task =
            args["task"]?.trim()
                ?: return context.getString(R.string.skill_missing_param_task)
        if (task.isBlank()) return context.getString(R.string.skill_task_blank)
        val contextInfo = args["context"]?.trim().orEmpty()
        // timeout: 默认 60 秒;response_format: 默认 text,可选 json
        val timeoutSec = args["timeout"]?.toLongOrNull()?.coerceIn(1L, 600L) ?: 60L
        val responseFormat = args["response_format"]?.takeIf { it.isNotBlank() } ?: "text"

        // 构造上下文消息(把可选 context 作为 USER 消息的额外段落)
        val contextMessages: List<UIMessage> =
            if (contextInfo.isNotBlank()) {
                listOf(UIMessage(role = MessageRole.USER, content = "Context: \n$contextInfo"))
            } else {
                emptyList()
            }

        // 构造结构化 DelegationRequest,委托到 delegateAgent(自动走链路追踪/暂停/DM/工具调用)
        val requestId = "delegate-toolcall-${System.currentTimeMillis()}-${(100..999).random()}"
        val request =
            DelegationContract.DelegationRequest(
                requestId = requestId,
                task = task,
                targetType = DelegationContract.DelegationRequest.TargetType.ASSISTANT,
                targetId = assistantId,
                contextMessages = contextMessages,
                timeoutSec = timeoutSec.toInt().coerceAtLeast(1),
                responseFormat =
                when (responseFormat.lowercase()) {
                    "json" -> DelegationContract.DelegationRequest.ResponseFormat.JSON
                    "markdown", "md" -> DelegationContract.DelegationRequest.ResponseFormat.MARKDOWN
                    "code" -> DelegationContract.DelegationRequest.ResponseFormat.CODE
                    else -> DelegationContract.DelegationRequest.ResponseFormat.TEXT
                },
            )
        val result = delegateAgent(request)

        // 把 DelegationResult 转成字符串(tool result)
        // 失败时返回错误字符串(主 LLM 能看到错误并决定是否重试)
        if (!result.success) {
            return result.error ?: context.getString(R.string.skill_unknown_error)
        }
        val resultText = result.resultText
        // 取子助手名(从 metadata,若不可用则降级到 assistantId)
        val assistantName = result.metadata.assistantName ?: assistantId

        // response_format=json 时返回结构化 JSON,否则返回带前缀的文本
        return if (responseFormat.equals("json", ignoreCase = true)) {
            buildJsonObject {
                put("assistantId", JsonPrimitive(assistantId))
                put("assistantName", JsonPrimitive(assistantName))
                put("result", JsonPrimitive(resultText))
                put("success", JsonPrimitive(true))
                // 非阻塞模式下附带 taskId/threadId,供主 LLM 后续查询
                result.taskId?.let { put("taskId", JsonPrimitive(it)) }
                result.threadId?.let { put("threadId", JsonPrimitive(it)) }
            }.toString()
        } else {
            buildString {
                appendLine(context.getString(R.string.skill_delegate_done, assistantName))
                appendLine(resultText)
            }
        }
    }

    // ── v1.55: Agent 工作流(结构化任务计划)──────────────────────────────

    // ── 文件管理类 ──────────────────────────────────────────────────────

    /** list_dir — 列出目录下的文件和子目录(沙盒内)。 */

    // ── Skill 管理类 ────────────────────────────────────────────────────

    /** list_skills — 列出已安装的 Skill,可选按 category 筛选。 */

    /** uninstall_skill — 卸载 Skill(按 id 删除,或按 name 查找后删除)。 */
    companion object {
        /**
         * 预定义 skill 模板(首次启动时写入数据库)。
         * v2.x: 启动 seed 走 SkillRepository.seedOrRefreshBuiltIn —
         * 缺失则插入,已存在仅刷新定义字段(保留用户启停状态)。
         */
        val BUILT_IN_SKILLS: List<SkillEntity> =
            listOf(
                SkillEntity(
                    id = "read_file",
                    name = "Read file",
                    description = "Read a text file inside the app sandbox. The path is relative to filesDir; files larger than 1MB are automatically returned in segments — use offset_chars/length_chars to continue reading the rest, without discarding the remaining content.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "File path relative to filesDir, e.g. 'notes/todo.txt'")
                                    },
                                )
                                put(
                                    "offset",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Optional, starting line number (0-based), default 0")
                                    },
                                )
                                put(
                                    "length",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Optional, number of lines to read, default 0 = all")
                                    },
                                )
                                put(
                                    "offset_chars",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Optional, character paging start; when reading the next segment of a very large file, use the cursor returned last time")
                                    },
                                )
                                put(
                                    "length_chars",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Optional, character paging length; returns at most 65536 characters each time")
                                    },
                                )
                                put(
                                    "encoding",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, file encoding, default utf-8 (utf-16 supported)")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("path"))))
                    }.toString(),
                    requiredJson = """["path"]""",
                    implementationKotlin = "read_file",
                    category = "file",
                ),
                SkillEntity(
                    id = "write_file",
                    name = "Write file",
                    description = "Write text to a file inside the app sandbox. The path is relative to filesDir.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "File path relative to filesDir")
                                    },
                                )
                                put(
                                    "content",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The text content to write")
                                    },
                                )
                                put(
                                    "append",
                                    buildJsonObject {
                                        put("type", "boolean")
                                        put("description", "Whether to append (default false = overwrite)")
                                    },
                                )
                                put(
                                    "create_dirs",
                                    buildJsonObject {
                                        put("type", "boolean")
                                        put("description", "Optional, whether to create parent directories automatically, default true")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("path"),
                                    JsonPrimitive("content"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["path","content"]""",
                    implementationKotlin = "write_file",
                    category = "file",
                ),
                SkillEntity(
                    id = "http_get",
                    name = "HTTP GET",
                    description = "Send an HTTP GET request and return the full response; long responses are saved to an app file that can be read in segments.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "url",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Request URL, http:// or https://")
                                    },
                                )
                                put(
                                    "headers",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, request headers JSON, e.g. {\"Authorization\":\"Bearer xxx\"}")
                                    },
                                )
                                put(
                                    "timeout",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Optional, timeout in seconds, default 30")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("url"))))
                    }.toString(),
                    requiredJson = """["url"]""",
                    implementationKotlin = "http_get",
                    category = "http",
                ),
                SkillEntity(
                    id = "http_post",
                    name = "HTTP POST",
                    description = "Send an HTTP POST request and return the full response; long responses are saved to an app file that can be read in segments.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "url",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "Request URL",
                                        )
                                    },
                                )
                                put(
                                    "body",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Request body content")
                                    },
                                )
                                put(
                                    "content_type",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Content-Type, default application/json")
                                    },
                                )
                                put(
                                    "headers",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, request headers JSON")
                                    },
                                )
                                put(
                                    "timeout",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Optional, timeout in seconds, default 30")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("url"),
                                    JsonPrimitive("body"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["url","body"]""",
                    implementationKotlin = "http_post",
                    category = "http",
                ),
                // ── v0.24: 搜索与信息获取 ──────────────────────────────────────
                SkillEntity(
                    id = "web_search",
                    name = "Web search",
                    // v1.0.75 fix (工具审查 01): 删冗余 time_period,补 date_range 使用指引
                    description = "Search the web with the configured search engine (SearXNG/Tavily). Returns titles, URLs and summaries. Call this tool when the user asks a question that needs the latest information. When to use: the user asks about something needing the latest/real-time information (prices, versions, policies, market data, weather, etc.), or your knowledge is uncertain. When the time must be limited, pass date_range (e.g. if the user says 'the past week', pass past_week). Do not use: for common-knowledge questions or when the user has already provided enough information; if the search results are incomplete, retry with different keywords — do not fabricate.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "query",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Required, search keywords. For Chinese-language user questions, use Chinese keywords directly; add key qualifiers to improve precision")
                                    },
                                )
                                put(
                                    "max_results",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Optional, maximum number of results (1-10), default 5")
                                    },
                                )
                                put(
                                    "date_range",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "Optional, limit the result time range; values: past_day / past_week / past_month / past_year. Fill in when the user mentions 'recent/latest/this week/this month'; leave empty when there is no time requirement",
                                        )
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))))
                    }.toString(),
                    requiredJson = """["query"]""",
                    implementationKotlin = "web_search",
                    category = "search",
                ),
                SkillEntity(
                    id = "web_fetch",
                    name = "Fetch web page",
                    description = "Fetch the body text of the given URL (HTML tags are removed automatically, plain text is returned). Used to read the full content of a URL returned by web_search. When to use: reading the full text of a URL returned by web_search, or when the user gives an explicit URL. Do not use: when there is no URL; if the page needs login/interaction, explain that it cannot be fetched — do not guess the content.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "url",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The URL of the web page to fetch, http:// or https://")
                                    },
                                )
                                put(
                                    "headers",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, request headers JSON")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("url"))))
                    }.toString(),
                    requiredJson = """["url"]""",
                    implementationKotlin = "web_fetch",
                    category = "search",
                ),
                SkillEntity(
                    id = "knowledge_search",
                    name = "Knowledge base search",
                    // v1.0.75 fix (工具审查 01): 明确与 web_search 的边界,
                    // 原"可能与已导入文档相关时优先调用"太模糊,模型先试本地再退搜索浪费轮次。
                    description = "Semantically search the documents the user has actively imported into the knowledge base (vector retrieval + title/content matching). Use only when the answer may be in the user's knowledge base (e.g. the user imported materials before, or asks 'what did the document I saved say'). For general facts, real-time information, and anything searchable online, always use web_search — do not try this tool first. When the user asks about muse app features themselves (e.g. how to use deep thinking / how to set up proactive messages), pass include_internal=true to also search the built-in feature docs.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "query",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Search keywords")
                                    },
                                )
                                put(
                                    "top_k",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Optional, maximum number of results, default 5")
                                    },
                                )
                                put(
                                    "threshold",
                                    buildJsonObject {
                                        put("type", "number")
                                        put("description", "Optional, similarity threshold (0-1 decimal, not a 100-point scale), default 0.3. Results below the threshold are filtered out")
                                    },
                                )
                                put(
                                    "include_internal",
                                    buildJsonObject {
                                        put("type", "boolean")
                                        put(
                                            "description",
                                            "Optional, whether to include the muse app's built-in feature documentation, default false. Pass true only when the user asks about muse app features themselves (e.g. 'how to use deep thinking' / 'how to set up proactive messages')",
                                        )
                                    },
                                )
                                put(
                                    "folder",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "Optional, limit the search to a folder (a path inside the knowledge base, e.g. 'contracts' or 'contracts/2026')." +
                                                "Each folder is an independent search domain; when passed, the search covers only that folder and its subfolders, never other folders." +
                                                "Pass this when the user only wants to search a specific folder.",
                                        )
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))))
                    }.toString(),
                    requiredJson = """["query"]""",
                    implementationKotlin = "knowledge_search",
                    category = "knowledge",
                ),
                SkillEntity(
                    id = "arxiv_search",
                    name = "arXiv paper search",
                    description = "Search academic papers on arXiv (computer science, physics, mathematics, etc.). Returns paper titles, links, publication dates and abstracts. Call when the user asks about academic research or papers.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "query",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Search keywords (English works best), e.g. 'transformer attention'")
                                    },
                                )
                                put(
                                    "max_results",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Optional, maximum number of results (1-10), default 5")
                                    },
                                )
                                put(
                                    "category",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, subject category, e.g. cs.AI/cs.CL")
                                    },
                                )
                                put(
                                    "date_from",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, start date (YYYY-MM-DD)")
                                    },
                                )
                                put(
                                    "date_to",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, end date (YYYY-MM-DD)")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("query"))))
                    }.toString(),
                    requiredJson = """["query"]""",
                    implementationKotlin = "arxiv_search",
                    category = "search",
                ),
                // ── v0.24: 自我扩展 ─────────────────────────────────────────────
                SkillEntity(
                    id = "install_skill",
                    name = "Install Skill",
                    description = "Let the assistant generate a new skill definition itself and install it on the user's device.skill_json  parameter is a JSON string in .skill.json format. Required fields: id, name, description, category, implementationKotlin, parametersJson。category values: file/http/search/knowledge/system/agent/sticker/custom。implementationKotlin must be one of the built-in implementations (read_file/write_file/http_get/http_post/web_search/web_fetch/knowledge_search/arxiv_search), arbitrary code execution is not supported.After installation, the user can view/enable/disable it in Settings → Skill.Prompt skills: pass \"prompt\" as implementationKotlin, and put the instruction text in the prompt field (the form \"prompt:<text>\" form; the text is subject to the injection blacklist and an 8KB limit).When a prompt skill is called, an optional input parameter can be passed: the {{input}} placeholder in the instruction text is substituted; when there is no placeholder, the input is appended to the end of the instruction text.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "skill_json",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "skill definition JSON string (.skill.json format). Example: {\"id\":\"fetch_weather\",\"name\":\"Get weather\",\"description\":\"Get the weather for a city\",\"parametersJson\":\"{\\\"type\\\":\\\"object\\\",\\\"properties\\\":{\\\"url\\\":{\\\"type\\\":\\\"string\\\"}}}\",\"implementationKotlin\":\"http_get\"}",
                                        )
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("skill_json"))))
                    }.toString(),
                    requiredJson = """["skill_json"]""",
                    implementationKotlin = "install_skill",
                    category = "system",
                ),
                SkillEntity(
                    id = "author_plugin",
                    name = "Write plugin",
                    description = "Let the assistant write a plugin with JS tool implementations. code is the JS entry source (tool implementations must be top-level function declarations), tools is the array of tool declarations (each with name/description/parametersJson/requiredJson/functionName), and optional capabilities may only be resource.read/ui/ui.mood. This tool only generates an UNSIGNED DRAFT: the draft is disabled and not executable, and the assistant cannot call it; the user must review the tool list and code in Settings → Plugin Management and click 'Sign and enable' before it takes effect, signed with the local author key.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "id",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Plugin id: lowercase letters, digits, underscores, hyphens, starting with a letter or digit, e.g. 'daily_summary'")
                                    },
                                )
                                put(
                                    "name",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Plugin display name, e.g. 'Daily summary'")
                                    },
                                )
                                put(
                                    "description",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "What the plugin is for")
                                    },
                                )
                                put(
                                    "version",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Semantic version, e.g. '1.0.0'")
                                    },
                                )
                                put(
                                    "code",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "JS entry source (≤256KB). Each tool's functionName must have a top-level function declaration in the source, e.g. 'function summarize(args) { return ...; }'",
                                        )
                                    },
                                )
                                put(
                                    "tools",
                                    buildJsonObject {
                                        put("type", "array")
                                        put("description", "Tool declaration array, matching the manifest's tools field")
                                        put(
                                            "items",
                                            buildJsonObject {
                                                put("type", "object")
                                                put(
                                                    "properties",
                                                    buildJsonObject {
                                                        put("name", buildJsonObject { put("type", "string") })
                                                        put("description", buildJsonObject { put("type", "string") })
                                                        put("parametersJson", buildJsonObject { put("type", "string") })
                                                        put("requiredJson", buildJsonObject { put("type", "string") })
                                                        put("functionName", buildJsonObject { put("type", "string") })
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                                put(
                                    "capabilities",
                                    buildJsonObject {
                                        put("type", "array")
                                        put(
                                            "description",
                                            "Optional capabilities array, from the whitelist: resource.read / ui / ui.mood and controlled bridge capabilities (network / storage.read / storage.write / clipboard.read / clipboard.write / notify / device.info); empty by default",
                                        )
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("id"),
                                    JsonPrimitive("name"),
                                    JsonPrimitive("version"),
                                    JsonPrimitive("code"),
                                    JsonPrimitive("tools"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["id","name","version","code","tools"]""",
                    implementationKotlin = "author_plugin",
                    category = "system",
                ),
                // ── v0.46: 多 Agent 协作 ────────────────────────────────────────
                SkillEntity(
                    id = "delegate_agent",
                    name = "Delegate to sub-assistant",
                    description = "Delegate a task to the specified sub-assistant for multi-assistant collaboration. Pass assistantId (assistant id) and task (task description), and optionally context. The sub-assistant completes the task independently with its own persona and capabilities and returns the result.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "assistantId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Sub-assistant id, e.g. default / researcher / writer. Can be viewed on the assistant management page")
                                    },
                                )
                                put(
                                    "task",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The task to delegate, in natural language")
                                    },
                                )
                                put(
                                    "context",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional supplementary context")
                                    },
                                )
                                put(
                                    "timeout",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Optional, timeout in seconds, default 60")
                                    },
                                )
                                put(
                                    "response_format",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, return format, text (default) or json")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("assistantId"),
                                    JsonPrimitive("task"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["assistantId","task"]""",
                    implementationKotlin = "delegate_agent",
                    category = "agent",
                ),
                // ── v1.30: 群聊工具 ─────────────────────────────────────────────
                SkillEntity(
                    id = "channel_reply",
                    name = "Group chat message",
                    description = "Send a message as the specified agent in the specified group chat. Pass chatId (group chat id), assistantId (the speaking agent's id) and body (message text). The message is saved to the group chat history and is visible to other members and the user.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "chatId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Group chat id")
                                    },
                                )
                                put(
                                    "assistantId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The speaking agent id (assistantId)")
                                    },
                                )
                                put(
                                    "body",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Message text content")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("chatId"),
                                    JsonPrimitive("assistantId"),
                                    JsonPrimitive("body"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["chatId","assistantId","body"]""",
                    implementationKotlin = "channel_reply",
                    category = "agent",
                ),
                SkillEntity(
                    id = "channel_pass",
                    name = "Skip group chat turn",
                    description = "Skip this turn in the group chat rotation (send no message). Pass chatId (group chat id) and assistantId (agent id). Call when the agent thinks it does not need to speak right now.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "chatId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Group chat id")
                                    },
                                )
                                put(
                                    "assistantId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The agent id that skips speaking")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("chatId"),
                                    JsonPrimitive("assistantId"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["chatId","assistantId"]""",
                    implementationKotlin = "channel_pass",
                    category = "agent",
                ),
                SkillEntity(
                    id = "channel_read_context",
                    name = "Read group chat context",
                    description = "Read the recent messages of the specified group chat as context. Pass chatId (group chat id) and optionally limit (maximum number of messages, default 20). Returns a formatted message list including sender name, time and content.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "chatId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Group chat id")
                                    },
                                )
                                put(
                                    "limit",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Optional, maximum number of messages to read (1-100), default 20")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("chatId"))))
                    }.toString(),
                    requiredJson = """["chatId"]""",
                    implementationKotlin = "channel_read_context",
                    category = "agent",
                ),
                // ── v1.0.53 Phase 5: Agent Phone(主 agent 触发群聊成员私聊) ─────
                SkillEntity(
                    id = "agent_phone",
                    name = "Agent whisper",
                    description = "Start a private chat (whisper) with the specified agent inside a group chat. Pass chatId (group chat id), targetAssistantId (target agent id) and message (whisper content). Whisper messages are visible only to the target agent, do not join the group chat rotation context, and are persisted to a separate session ledger (resumable after an app restart). Returns immediately; the target agent's reply arrives asynchronously in the group chat message stream.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "chatId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Group chat id (used to isolate the whisper context)")
                                    },
                                )
                                put(
                                    "targetAssistantId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Target agent id (assistantId)")
                                    },
                                )
                                put(
                                    "message",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Whisper message text")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("chatId"),
                                    JsonPrimitive("targetAssistantId"),
                                    JsonPrimitive("message"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["chatId","targetAssistantId","message"]""",
                    implementationKotlin = "agent_phone",
                    category = "agent",
                ),
                // ── 文件管理类 ─────────────────────────────────────────────────
                SkillEntity(
                    id = "list_dir",
                    name = "List directory",
                    description = "List the files and subdirectories under the given directory inside the app sandbox. One entry per line; folders are prefixed [D], files are prefixed [F], with the file size noted at the end. The path is relative to filesDir.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Directory path relative to filesDir, e.g. 'notes' or '' (root)")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("path"))))
                    }.toString(),
                    requiredJson = """["path"]""",
                    implementationKotlin = "list_dir",
                    category = "file",
                ),
                SkillEntity(
                    id = "delete_file",
                    name = "Delete file",
                    description = "Delete a file or empty directory inside the app sandbox. The path is relative to filesDir. Deleting a non-empty directory fails. Batch deletion is supported (pass paths).",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Path of the file or empty directory relative to filesDir (single; choose one of path/paths)")
                                    },
                                )
                                put(
                                    "paths",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, list of paths to delete in batch, separated by commas or newlines, e.g. 'a.txt,b.txt'. Choose one of this and path; takes precedence over path")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("path"))))
                    }.toString(),
                    requiredJson = """["path"]""",
                    implementationKotlin = "delete_file",
                    category = "file",
                ),
                SkillEntity(
                    id = "file_exists",
                    name = "Check file exists",
                    description = "Check whether the file or directory at the given path inside the app sandbox exists. Returns exists or not_exists. The path is relative to filesDir.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Path relative to filesDir")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("path"))))
                    }.toString(),
                    requiredJson = """["path"]""",
                    implementationKotlin = "file_exists",
                    category = "file",
                ),
                // ── 公共目录与文件传输 ─────────────────────────────────────────
                SkillEntity(
                    id = "file_download",
                    name = "Download file",
                    description = "Download a file from a URL into the app sandbox. A timeout can be specified (default 60 seconds). The downloaded file is saved under the given relative path in filesDir; parent directories are created automatically.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "url",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Download URL, http:// or https://")
                                    },
                                )
                                put(
                                    "path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Relative path to save to in the sandbox (relative to filesDir), e.g. 'downloads/file.zip'")
                                    },
                                )
                                put(
                                    "timeout",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Optional, timeout in seconds, default 60")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("url"),
                                    JsonPrimitive("path"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["url","path"]""",
                    implementationKotlin = "file_download",
                    category = "file",
                ),
                SkillEntity(
                    id = "read_public_file",
                    name = "Read public file",
                    description = "Read a public file via a content:// URI. Can read URIs returned by list_public_files (including MediaStore URIs), as well as URIs passed in by user sharing/open-with. Returns text content (up to 1MB). Note: only text files are supported; binary files may come out garbled.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "uri",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "File URI, e.g. the uri=content://media/... output by list_public_files, or a content://... passed in via SAF",
                                        )
                                    },
                                )
                                put(
                                    "encoding",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, file encoding, default utf-8")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("uri"))))
                    }.toString(),
                    requiredJson = """["uri"]""",
                    implementationKotlin = "read_public_file",
                    category = "file",
                ),
                SkillEntity(
                    id = "save_to_downloads",
                    name = "Save to Downloads",
                    description = "Save text content or a local sandbox file to the system Download directory. On Android 10+ it writes via MediaStore; on Android 9 and below it writes directly to the public Download directory. When file_path is passed, binary files can be copied directly (no need to read_file then write text).",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "content",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The text content to save (choose one of this and file_path)")
                                    },
                                )
                                put(
                                    "file_path",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, source file path inside the sandbox (relative to filesDir); copied directly to Download, binary supported. Choose one of this and content")
                                    },
                                )
                                put(
                                    "filename",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "File name, e.g. 'notes.txt'")
                                    },
                                )
                                put(
                                    "mime_type",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, MIME type, default text/plain. When copying binary files, specifying it explicitly is recommended (e.g. image/png)")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("filename"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["filename"]""",
                    implementationKotlin = "save_to_downloads",
                    category = "file",
                ),
                SkillEntity(
                    id = "list_public_files",
                    name = "List public directory",
                    description = "List the files in the given public directory (Downloads/Documents/Pictures/Music, etc.). Queried via MediaStore; returns file names and sizes. Lists Downloads by default, at most 50 entries, in reverse modification-time order.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "directory",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, directory name, e.g. Downloads/Documents/Pictures/Music, default Downloads")
                                    },
                                )
                                put(
                                    "limit",
                                    buildJsonObject {
                                        // v1.52: 修正类型为 integer(原 string 与实际 parseIntOrNull 不匹配)
                                        put("type", "integer")
                                        put("description", "Optional, maximum number of entries to return, default 50")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf()))
                    }.toString(),
                    requiredJson = """[]""",
                    implementationKotlin = "list_public_files",
                    category = "file",
                ),
                // ── Skill 管理类 ───────────────────────────────────────────────
                SkillEntity(
                    id = "list_skills",
                    name = "List Skills",
                    description = "List all installed Skills, one per line in the format 'id | name | category | enabled/disabled'. Optionally filter by category.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "category",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, filter by category, e.g. file/http/search/knowledge/system/agent/skill")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf()))
                    }.toString(),
                    requiredJson = """[]""",
                    implementationKotlin = "list_skills",
                    category = "skill",
                ),
                SkillEntity(
                    id = "uninstall_skill",
                    name = "Uninstall Skill",
                    description = "Uninstall (delete) an installed Skill. Pass id or name (at least one); id is preferred. After deletion the Skill is no longer available.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "id",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The skill id to uninstall")
                                    },
                                )
                                put(
                                    "name",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, find by name and uninstall (used when id is not passed)")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf()))
                    }.toString(),
                    requiredJson = """[]""",
                    implementationKotlin = "uninstall_skill",
                    category = "skill",
                ),
                SkillEntity(
                    id = "disable_skill",
                    name = "Disable Skill",
                    description = "Disable an installed Skill (not deleted, just made unavailable). It can be restored later by enabling. Pass the id.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "id",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The skill id to disable")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("id"))))
                    }.toString(),
                    requiredJson = """["id"]""",
                    implementationKotlin = "disable_skill",
                    category = "skill",
                ),
                SkillEntity(
                    id = "enable_skill",
                    name = "Enable Skill",
                    description = "Enable an installed Skill (the counterpart of disable_skill). Pass the id. Built-in skills may be enabled; plugin skills follow the plugin's enable/disable state and should be managed on the plugin management page — this tool will refuse them.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "id",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The skill id to enable")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("id"))))
                    }.toString(),
                    requiredJson = """["id"]""",
                    implementationKotlin = "enable_skill",
                    category = "skill",
                ),
                SkillEntity(
                    id = "update_skill",
                    name = "Update Skill",
                    description = "Update the name / description / parametersJson (a JSON Schema string) of a user-created skill. Prompt skills additionally support replacing the instruction text with the prompt parameter. Only skills the user created themselves can be changed: built-in reserved skills and plugin skills are always refused; updated content still goes through import validation (reserved id/category whitelist/injection blacklist), and the id stays unchanged and enabled after the update.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "id",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The user skill id to update")
                                    },
                                )
                                put(
                                    "name",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, new display name")
                                    },
                                )
                                put(
                                    "description",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, new tool description (the model uses it to decide whether to call)")
                                    },
                                )
                                put(
                                    "parametersJson",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, new parameter JSON Schema string (ignored for prompt skills)")
                                    },
                                )
                                put(
                                    "prompt",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, prompt skills only: new instruction text (up to 8KB)")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("id"))))
                    }.toString(),
                    requiredJson = """["id"]""",
                    implementationKotlin = "update_skill",
                    category = "skill",
                ),
                // v1.55: Agent 工作流 — 结构化任务计划
                SkillEntity(
                    id = "task_plan",
                    name = "Create task plan",
                    description = "When facing a complex multi-step task, first create a structured plan and then execute it step by step. Pass a plan title and a list of steps (each with a title and description). The plan is shown in the user interface as a trackable checklist. After creating it, execute the steps in order, calling update_plan_step after each step to update its status.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "title",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Plan title, briefly describing the overall goal")
                                    },
                                )
                                put(
                                    "steps",
                                    buildJsonObject {
                                        put("type", "array")
                                        put("description", "List of steps, in execution order")
                                        put(
                                            "items",
                                            buildJsonObject {
                                                put("type", "object")
                                                put(
                                                    "properties",
                                                    buildJsonObject {
                                                        put(
                                                            "title",
                                                            buildJsonObject {
                                                                put("type", "string")
                                                                put("description", "Step title (short)")
                                                            },
                                                        )
                                                        put(
                                                            "description",
                                                            buildJsonObject {
                                                                put("type", "string")
                                                                put("description", "Step details, including what to do and the expected result")
                                                            },
                                                        )
                                                    },
                                                )
                                                put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("title"))))
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("steps"))))
                    }.toString(),
                    requiredJson = """["title","steps"]""",
                    implementationKotlin = "task_plan",
                    category = "agent",
                ),
                SkillEntity(
                    id = "update_plan_step",
                    name = "Update plan step",
                    description = "Update the status of a step in a task plan. Pass planId and stepIndex (0-based) and the new status (done/failed/in_progress/skipped) and an optional result summary.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "planId",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Plan id (the planId returned by task_plan)")
                                    },
                                )
                                put(
                                    "stepIndex",
                                    buildJsonObject {
                                        put("type", "integer")
                                        put("description", "Step index (0-based)")
                                    },
                                )
                                put(
                                    "status",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "New status: done / failed / in_progress / skipped")
                                    },
                                )
                                put(
                                    "result",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "Optional, step result summary",
                                        )
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(JsonPrimitive("planId"), JsonPrimitive("stepIndex"), JsonPrimitive("status")),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["planId","stepIndex","status"]""",
                    implementationKotlin = "update_plan_step",
                    category = "agent",
                ),
                // ── v1.95: 表情包库工具 ─────────────────────────────────────────
                SkillEntity(
                    id = "list_stickers",
                    name = "List stickers",
                    description = "List the stickers available in the sticker library (optionally filtered by category). Only available when the user has uploaded stickers. Before sending, judge the conversation mood first: when the user is angry/sad, prefer comforting, cute, healing categories; when happy/excited, prefer celebration and funny categories.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "category",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, filter stickers by category (e.g. cats/dogs). Leave empty to list all; passing a category suited to the conversation mood is recommended")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf()))
                    }.toString(),
                    requiredJson = """[]""",
                    implementationKotlin = "list_stickers",
                    category = "sticker",
                ),
                SkillEntity(
                    id = "send_sticker",
                    name = "Send sticker",
                    description = "Send a sticker to the user. First view the available stickers with list_stickers, then send with this tool. Selection principle: match the conversation mood and context — send comforting/healing stickers when angry/sad, celebration/funny ones when happy, and avoid overly silly stickers in serious topics.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "id",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The sticker id to send (from list_stickers)")
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("id"))))
                    }.toString(),
                    requiredJson = """["id"]""",
                    implementationKotlin = "send_sticker",
                    category = "sticker",
                ),
                // ── v1.???: 新增工具 ──────────────────────────────────────────────
                SkillEntity(
                    id = "generate_image",
                    name = "Generate image",
                    description = "Generate an image from a text description. Calls an AI drawing model (an OpenAI-compatible drawing provider must be configured). Returns the image URL. When to use: the user asks to generate an image/poster/cover. Do not use: when no drawing model is configured (it will fail; tell the user to configure it in Settings); describe the subject/style/composition in English in the prompt where possible.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "prompt",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Image description, English works better, e.g. 'a cute cat sitting on a sofa'")
                                    },
                                )
                                put(
                                    "size",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, image size, e.g. 1024x1024/1792x1024/1024x1792, default 1024x1024")
                                    },
                                )
                                put(
                                    "reference_image",
                                    buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            "Optional, reference image URL or base64 (for image-to-image/editing); when non-empty, the image-to-image endpoint is called. Note: the LLM cannot access the user's local photo album; local reference images are injected by the user picking from the album in the tool approval card, so the LLM does not need to and cannot fill in this parameter when calling",
                                        )
                                    },
                                )
                            },
                        )
                        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("prompt"))))
                    }.toString(),
                    requiredJson = """["prompt"]""",
                    implementationKotlin = "generate_image",
                    category = "image",
                ),
                SkillEntity(
                    id = "translate",
                    name = "Translate",
                    description = "Translate text into the specified language. A source language (optional) and target language (required) can be specified. Uses an AI model for high-quality translation.",
                    parametersJson =
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "text",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "The text content to translate")
                                    },
                                )
                                put(
                                    "target_language",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Target language, e.g. 中文/English/日本語/한국어")
                                    },
                                )
                                put(
                                    "source_language",
                                    buildJsonObject {
                                        put("type", "string")
                                        put("description", "Optional, source language, e.g. 中文/English/自動検出")
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    JsonPrimitive("text"),
                                    JsonPrimitive("target_language"),
                                ),
                            ),
                        )
                    }.toString(),
                    requiredJson = """["text","target_language"]""",
                    implementationKotlin = "translate",
                    category = "custom",
                ),
                // B-07: generate_qr 内置 skill 已下线 — 与本地工具 generate_qr_code 重复且语义冲突
                // (skill 版返回 cacheDir 文件路径,本地版渲染 data URI 到对话)。保留本地版。
                // implementationKotlin="generate_qr" 的 dispatch 仍保留,兼容已导入该 skill 的用户。
            )

        /**
         * B-32: 内置 skill 可路由实现集合 — 与 [execute] 内 when(implementationKotlin) 的
         * 专属分支 key 一一对应(plugin: 前缀与未知 else 分支除外)。
         *
         * 供一致性护栏测试(见 app/src/test/.../tools/ToolRegistrySmokeTest.kt)遍历
         * [BUILT_IN_SKILLS] 断言每个内置 skill 都能被 [execute] 路由到对应实现,
         * 防止"新增内置 skill 却漏写 when 路由分支"或"分支被删"导致静默走 skill_unknown_impl。
         *
         * 注意:generate_qr 属已下线实现(B-07),从此集排除;但 dispatch 分支仍保留以兼容旧数据。
         * 此集合须与 [execute] 的 when 分支保持同步,集合仅用于测试护栏,不驱动路由。
         */
        internal val ROUTABLE_SKILL_IMPL: Set<String> =
            setOf(
                // 文件
                "read_file", "write_file", "list_dir", "delete_file", "file_exists",
                // HTTP/搜索/信息
                "http_get", "http_post", "web_search", "web_fetch", "knowledge_search", "arxiv_search",
                // 自我扩展/Agent/群聊
                "install_skill", "author_plugin", "delegate_agent", "task_plan", "update_plan_step",
                "channel_reply", "channel_pass", "channel_read_context", "agent_phone",
                // 文件公共目录
                "file_download", "read_public_file", "save_to_downloads", "list_public_files",
                // Skill 管理
                "list_skills", "uninstall_skill", "disable_skill", "enable_skill", "update_skill",
                // 表情包/媒体/翻译/JS
                "list_stickers", "send_sticker", "generate_image", "translate", "execute_javascript",
            )
    }
}
