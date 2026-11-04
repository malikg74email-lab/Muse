package io.zer0.muse.automation.tools

import io.zer0.common.AppJson
import io.zer0.muse.automation.appcontrol.AppControlCore
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.core.DeviceCommandPolicy
import io.zer0.muse.automation.vdisplay.VirtualDisplayClient
import io.zer0.muse.automation.vdisplay.VirtualDisplayInputCommand
import io.zer0.muse.automation.vdisplay.VirtualDisplayLeaseRegistry
import io.zer0.muse.automation.vdisplay.VirtualDisplaySemanticActions
import io.zer0.muse.automation.vdisplay.VirtualDisplayServerManager
import io.zer0.muse.tools.NodeScriptTool
import io.zer0.muse.tools.ToolOutcome
import io.zer0.muse.tools.WorkflowJournal
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private val DISPLAY_REQUIRED_VIRTUAL_ACTIONS = setOf(
    "virtual_launch",
    "virtual_tap",
    "virtual_tap_text",
    "virtual_swipe",
    "virtual_text",
    "virtual_key",
    "virtual_wait",
    "virtual_read",
)
private val CACHED_DISPLAY_ID_REGEX = Regex("""displayId=(\d+)""")

/**
 * 受控设备工作流 —— 把安全的手机动作原语组合成一次可审计调用。
 *
 * 这是 Agent 编排层的第一块：动作集合是显式白名单，不接受任意 shell；每一步失败就停止，
 * 结果同时保留给模型的摘要和给编排/UI 使用的结构化 details。
 */
class AutomationWorkflow(
    private val manager: AutomationManager,
    private val journal: WorkflowJournal? = null,
    private val virtualDisplayClient: VirtualDisplayClient? = null,
    private val virtualDisplayManager: VirtualDisplayServerManager? = null,
    private val nodeScriptExecutor: (suspend (String, Long) -> SkillEngineResult)? = null,
    private val displayLeases: VirtualDisplayLeaseRegistry = VirtualDisplayLeaseRegistry(),
) {
    suspend fun run(steps: List<AutomationWorkflowStep>, runId: String, retryUnknown: Boolean = false): ToolOutcome {
        val durableJournal = journal ?: return ToolOutcome.error("Workflow breakpoint log unavailable; refusing to execute an unrecoverable device action")
        return durableJournal.withRunLock(runId) { runLocked(steps, runId, retryUnknown, durableJournal) }
    }

    private suspend fun runLocked(
        steps: List<AutomationWorkflowStep>,
        runId: String,
        retryUnknown: Boolean,
        durableJournal: WorkflowJournal,
    ): ToolOutcome {
        val results = mutableListOf<AutomationWorkflowStepResult>()
        val expectedKeys = steps.mapIndexed { index, step ->
            val prompt = AppJson.encodeToString(AutomationWorkflowStep.serializer(), step)
            index to durableJournal.computeKey(prompt, "automation_workflow:$index")
        }.toMap()
        val resume = try {
            durableJournal.resume(runId, expectedKeys)
        } catch (error: Exception) {
            return ToolOutcome.error("Unable to read the workflow breakpoint: ${error.message ?: "invalid run_id or unreadable log"}")
        }
        val resumeFrom = resume.resumeFromSeq
        val previousAtResume = try {
            durableJournal.load(runId).get(resumeFrom)
        } catch (error: Exception) {
            return ToolOutcome.error("Unable to read the workflow breakpoint: ${error.message ?: "invalid run_id or unreadable log"}")
        }
        val uncertainPrevious = previousAtResume?.takeIf { it.status == STATUS_RUNNING || it.status == STATUS_FAILED }
        if (uncertainPrevious != null && !retryUnknown) {
            val action = steps.getOrNull(resumeFrom)?.action ?: "unknown"
            return ToolOutcome.error(
                "The workflow was interrupted at step ${resumeFrom + 1} ($action); the action result may be unknown. To avoid duplicate side effects," +
                    "confirm first, then retry with the same run_id and retry_unknown=true set explicitly.",
                mapOf(
                    "runId" to runId,
                    "resumeFrom" to resumeFrom,
                    "outcomeUnknown" to true,
                    "uncertainAction" to action,
                ),
            )
        }
        val nextDisplayActionIndex = steps.indices.firstOrNull { index ->
            index >= resumeFrom && steps[index].action.trim().lowercase() in DISPLAY_REQUIRED_VIRTUAL_ACTIONS
        }
        val nextEnsureIndex = steps.indices.firstOrNull { index ->
            index >= resumeFrom && steps[index].action.trim().equals("virtual_ensure", ignoreCase = true)
        }
        val cachedEnsureToRefresh =
            if (nextDisplayActionIndex != null && (nextEnsureIndex == null || nextDisplayActionIndex < nextEnsureIndex)) {
                steps.indices.lastOrNull { index ->
                    index < resumeFrom && steps[index].action.trim().equals("virtual_ensure", ignoreCase = true)
                }
            } else {
                null
            }
        for ((index, step) in steps.withIndex()) {
            val cached = resume.cached[index]
            val cachedResult = cached
                ?.takeIf { index < resumeFrom && it.status == "done" }
                ?.let { runCatching { AppJson.decodeFromString(AutomationWorkflowStepResult.serializer(), it.result) }.getOrNull() }
            val result = cachedResult?.let { cachedStep ->
                val restored = restoreCachedDisplayLease(
                    runId = runId,
                    index = index,
                    step = step,
                    cached = cachedStep,
                    refreshDisplay = index == cachedEnsureToRefresh,
                )
                if (!restored.success) {
                    restored
                } else if (step.action.trim().equals("node_script", ignoreCase = true)) {
                    restored.copy(message = "Node script result (safe breakpoint recovery): ${restored.message}")
                } else if (step.action.trim().equals("virtual_ensure", ignoreCase = true) && restored.displayId != null) {
                    restored.copy(message = "Virtual screen ready displayId=${restored.displayId}")
                } else {
                    restored.copy(message = "Restored from the workflow breakpoint")
                }
            } ?: runStepDurably(durableJournal, runId, index, expectedKeys[index].orEmpty(), step)
            results += result
            if (!result.success) break
        }
        val completed = results.count { it.success }
        val success = results.size == steps.size && completed == steps.size
        val content = buildString {
            append(if (success) "Workflow complete" else "Workflow stopped at step ${completed + 1}")
            append("($completed/${steps.size})")
            results.forEach { item ->
                appendLine()
                append("[${item.index}] ${item.action}: ${item.message}")
            }
        }
        return if (success) {
            ToolOutcome.ok(
                content,
                mapOf("runId" to runId, "resumeFrom" to resumeFrom, "completed" to completed, "total" to steps.size, "steps" to results),
            )
        } else {
            ToolOutcome.error(
                content,
                mapOf("runId" to runId, "resumeFrom" to resumeFrom, "completed" to completed, "total" to steps.size, "steps" to results),
            )
        }
    }

    /** Persist intent before side effects and completion after; if completion persistence fails, next run blocks. */
    private suspend fun runStepDurably(
        journal: WorkflowJournal,
        runId: String,
        index: Int,
        key: String,
        step: AutomationWorkflowStep,
    ): AutomationWorkflowStepResult {
        try {
            journal.recordRequired(runId, index, key, "", STATUS_RUNNING, WorkflowJournal.NODE_KIND_TOOL_ONLY)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return failure(index, step.action, "Unable to persist the action-start state; not executed: ${error.message ?: "log write failed"}")
        }
        val result = executeStep(index, step, runId)
        return try {
            journal.recordRequired(
                runId,
                index,
                key,
                resultForJournal(step, result),
                when {
                    result.success -> "done"
                    result.outcomeUnknown -> STATUS_FAILED
                    else -> STATUS_FAILED_KNOWN
                },
                if (step.action.trim().equals("node_script", ignoreCase = true)) {
                    WorkflowJournal.NODE_KIND_SENSITIVE_TOOL
                } else {
                    WorkflowJournal.NODE_KIND_TOOL_ONLY
                },
            )
            result
        } catch (cancelled: CancellationException) {
            // The action may already have taken effect. Keep the durable `running` marker so resume blocks
            // instead of silently replaying an unknown side effect.
            throw cancelled
        } catch (error: Exception) {
            failure(index, step.action, "The action was attempted but its result could not be persisted; on recovery it will be treated as an unknown side effect: ${error.message ?: "log write failed"}", outcomeUnknown = true)
        }
    }

    private fun resultForJournal(step: AutomationWorkflowStep, result: AutomationWorkflowStepResult): String {
        // Screen snapshots may contain private messages or account data; keep them in the live tool response only.
        if (step.action.trim().lowercase() in setOf("read", "virtual_read")) return ""
        return AppJson.encodeToString(AutomationWorkflowStepResult.serializer(), result)
    }

    private suspend fun executeStep(index: Int, step: AutomationWorkflowStep, runId: String): AutomationWorkflowStepResult {
        val action = step.action.trim().lowercase()
        return runCatching {
            when (action) {
                "launch" -> executeLaunch(index, action, step)
                "tap_text" -> executeTapText(index, action, step)
                "input_text" -> executeInputText(index, action, step)
                "swipe" -> executeSwipe(index, action, step)
                "back" -> result(index, action, manager.back(), "Back")
                "home" -> result(index, action, manager.home(), "Return to launcher")
                "wait" -> executeWait(index, action, step)
                "read" -> executeRead(index, action)
                "node_script" -> executeNodeScript(index, action, step)
                "virtual_ensure", "virtual_launch", "virtual_tap", "virtual_tap_text", "virtual_swipe", "virtual_text",
                "virtual_key", "virtual_wait", "virtual_read", "virtual_close",
                -> executeVirtualStep(index, action, step, runId)
                else -> failure(index, action, "Unsupported action")
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
        }.getOrElse { error -> failure(index, action, "Execution exception: ${error.message ?: "unknown"}", outcomeUnknown = true) }
    }

    private suspend fun executeNodeScript(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val code = step.code?.takeIf { it.isNotBlank() }
            ?: return failure(index, action, "Missing code")
        if (code.length > NodeScriptTool.MAX_SCRIPT_CHARS) {
            return failure(index, action, "Script exceeds the maximum length (${NodeScriptTool.MAX_SCRIPT_CHARS} characters)")
        }
        val executor = nodeScriptExecutor ?: return failure(index, action, "Node script runtime not initialized")
        val timeoutMs = (step.timeoutMs ?: DEFAULT_NODE_SCRIPT_TIMEOUT_MS).coerceIn(1_000L, MAX_NODE_SCRIPT_TIMEOUT_MS)
        return when (val outcome = executor(code, timeoutMs)) {
            is SkillEngineResult.Success -> result(
                index,
                action,
                true,
                NodeScriptTool.formatResultJson(outcome),
            )
            is SkillEngineResult.Error -> failure(
                index,
                action,
                "Node script execution failed; side effects may already have happened; it will not be replayed automatically." +
                    NodeScriptTool.formatResultJson(outcome),
                outcomeUnknown = true,
            )
        }
    }

    private suspend fun executeLaunch(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val pkg = step.packageName?.trim().orEmpty()
        return if (!AppControlCore.isValidPackageName(pkg)) {
            failure(index, action, "Invalid package name")
        } else {
            result(index, action, manager.launchApp(pkg), "Launch $pkg")
        }
    }

    private suspend fun executeTapText(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val text = step.text?.takeIf { it.isNotBlank() } ?: return failure(index, action, "Missing text")
        val outcome = manager.tapByTextWithRetryDetailed(
            text = text,
            exact = step.exact,
            maxSwipes = step.maxSwipes,
            verifyText = step.verifyText?.takeIf { it.isNotBlank() },
        )
        return result(index, action, outcome.success, "matched=${outcome.matched}, verified=${outcome.verified}, attempts=${outcome.attempts}")
    }

    private suspend fun executeInputText(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val text = step.text ?: return failure(index, action, "Missing text")
        return result(index, action, manager.inputText(text), "Text entered (${text.length} characters)")
    }

    private suspend fun executeSwipe(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val points = listOf(step.x1, step.y1, step.x2, step.y2)
        return if (points.any { it == null }) {
            failure(index, action, "Missing x1/y1/x2/y2")
        } else {
            result(
                index,
                action,
                manager.swipe(step.x1!!, step.y1!!, step.x2!!, step.y2!!, step.durationMs.coerceIn(50L, 5_000L)),
                "Swipe",
            )
        }
    }

    private suspend fun executeWait(index: Int, action: String, step: AutomationWorkflowStep): AutomationWorkflowStepResult {
        val ms = step.durationMs.coerceIn(50L, 10_000L)
        delay(ms)
        return result(index, action, true, "Wait ${ms}ms")
    }

    private suspend fun executeRead(index: Int, action: String): AutomationWorkflowStepResult {
        val screen = manager.readScreen()
        return result(index, action, true, screen.toSummary(20))
    }

    private suspend fun executeVirtualStep(
        index: Int,
        action: String,
        step: AutomationWorkflowStep,
        runId: String,
    ): AutomationWorkflowStepResult {
        val client = virtualDisplayClient ?: return failure(index, action, "Virtual screen workflow not initialized")
        val displayManager = virtualDisplayManager ?: return failure(index, action, "Virtual screen workflow not initialized")
        if (action == "virtual_ensure") {
            val display = ensureVirtualDisplay(step, client)
                .getOrElse { return failure(index, action, "Failed to create the virtual screen: ${it.message}") }
            displayLeases.acquire(runId, display.displayId)
            return result(
                index,
                action,
                true,
                "Virtual screen ready displayId=${display.displayId} (${display.width}x${display.height})",
                displayId = display.displayId,
            )
        }

        val leasedDisplayId = displayLeases.displayFor(runId)
        if (step.displayId != null && leasedDisplayId != null && !displayLeases.isKnownDisplayId(runId, step.displayId)) {
            return failure(index, action, "displayId=${step.displayId} does not belong to the current run_id's virtual screen lease")
        }
        if (action == "virtual_close" && leasedDisplayId == null) {
            return failure(index, action, "The current run_id has no virtual screen lease to release")
        }
        val displayId = leasedDisplayId ?: step.displayId ?: displayManager.lastDisplayId
        if (displayId < 0) {
            if (action != "virtual_launch") return failure(index, action, "Virtual screen not created; please run virtual_ensure first")
            val display = ensureVirtualDisplay(step, client)
                .getOrElse { return failure(index, action, "Failed to create the virtual screen: ${it.message}") }
            displayLeases.acquire(runId, display.displayId)
            return launchOnVirtualDisplay(index, action, step, client, display.displayId)
        }
        if (leasedDisplayId == null && action != "virtual_close") {
            // 兼容旧的 workflow：历史步骤依赖 manager.lastDisplayId，
            // 首次使用时把该兼容 display 登记为共享 lease，后续 close 仍受 owner 保护。
            displayLeases.acquire(runId, displayId)
        }

        return when (action) {
            "virtual_launch" -> launchOnVirtualDisplay(index, action, step, client, displayId)
            "virtual_read" -> {
                val screen = manager.readScreenOnDisplay(displayId)
                    ?: return failure(index, action, "Virtual screen semantic reading unavailable; requires Shizuku or Root")
                result(index, action, true, screen.toSummary(30))
            }
            "virtual_tap_text" -> executeVirtualTapText(index, action, step, displayManager, displayId)
            "virtual_close" -> {
                val canDestroy = displayLeases.release(runId, displayId)
                if (!canDestroy) {
                    result(index, action, true, "Released this run_id's lease on virtual screen $displayId; the screen is still used by other workflows")
                } else {
                    val destroyed = client.destroy(displayId)
                    // A service restart can make the old display disappear before close.
                    // VirtualDisplayClient clears the stale compatibility id in that case;
                    // treat the already-gone display as an idempotent successful close and
                    // never re-acquire a lease for a dead display.
                    val staleDisplayGone = !destroyed && displayManager.lastDisplayId != displayId
                    if (!destroyed && !staleDisplayGone) displayLeases.acquire(runId, displayId)
                    result(
                        index,
                        action,
                        destroyed || staleDisplayGone,
                        if (staleDisplayGone) "Virtual screen $displayId no longer exists; treating it as an idempotent close" else "Destroy virtual screen $displayId",
                    )
                }
            }
            else -> executeVirtualInput(index, action, step, displayManager, displayId)
        }
    }

    private suspend fun restoreCachedDisplayLease(
        runId: String,
        index: Int,
        step: AutomationWorkflowStep,
        cached: AutomationWorkflowStepResult,
        refreshDisplay: Boolean,
    ): AutomationWorkflowStepResult {
        if (!step.action.trim().equals("virtual_ensure", ignoreCase = true)) return cached
        val displayId = cached.displayId
            ?: CACHED_DISPLAY_ID_REGEX.find(cached.message)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return cached
        displayLeases.acquire(runId, displayId)
        if (!refreshDisplay) return cached.copy(displayId = displayId)

        val client = virtualDisplayClient ?: return failure(index, step.action, "Virtual screen workflow not initialized")
        val display = ensureVirtualDisplay(step, client)
            .getOrElse { return failure(index, step.action, "Failed to restore the virtual screen: ${it.message}") }
        displayLeases.acquire(runId, display.displayId)
        return cached.copy(
            message = "After the virtual screen service restarted, refreshed displayId=${display.displayId} (${display.width}x${display.height})",
            displayId = display.displayId,
        )
    }

    private suspend fun ensureVirtualDisplay(
        step: AutomationWorkflowStep,
        client: VirtualDisplayClient,
    ): Result<VirtualDisplayClient.DisplayHandle> {
        val width = step.width ?: 720
        val height = step.height ?: 1280
        val dpi = step.dpi ?: 320
        if (width !in 320..1920 || height !in 320..2560 || dpi !in 120..640) {
            return Result.failure(IllegalArgumentException("Virtual screen size or density is outside the safe range"))
        }
        return client.ensureDisplay(width, height, dpi)
    }

    private suspend fun launchOnVirtualDisplay(
        index: Int,
        action: String,
        step: AutomationWorkflowStep,
        client: VirtualDisplayClient,
        displayId: Int,
    ): AutomationWorkflowStepResult {
        val packageName = step.packageName?.trim().orEmpty()
        if (!AppControlCore.isValidPackageName(packageName)) return failure(index, action, "Invalid package name")
        return result(index, action, client.openApp(packageName, displayId), "Launch $packageName on virtual screen $displayId")
    }

    /** Semantic interaction stays on the selected virtual display and never falls back to the user's foreground UI. */
    private suspend fun executeVirtualTapText(
        index: Int,
        action: String,
        step: AutomationWorkflowStep,
        displayManager: VirtualDisplayServerManager,
        displayId: Int,
    ): AutomationWorkflowStepResult {
        val text = step.text?.trim()?.takeIf { it.isNotEmpty() }
            ?: return failure(index, action, "Missing text")
        val outcome = VirtualDisplaySemanticActions.tapText(
            manager = manager,
            displayManager = displayManager,
            displayId = displayId,
            text = text,
            exact = step.exact,
            maxSwipes = step.maxSwipes,
            verifyText = step.verifyText,
        )
        return if (outcome.success) {
            result(index, action, true, "matched=${outcome.matched}, verified=${outcome.verified}, attempts=${outcome.attempts}")
        } else {
            failure(
                index,
                action,
                "matched=${outcome.matched}, verified=${outcome.verified}, attempts=${outcome.attempts}${outcome.error?.let { ",$it" }.orEmpty()}",
                outcomeUnknown = outcome.outcomeUnknown,
            )
        }
    }

    private suspend fun executeVirtualInput(
        index: Int,
        action: String,
        step: AutomationWorkflowStep,
        displayManager: VirtualDisplayServerManager,
        displayId: Int,
    ): AutomationWorkflowStepResult {
        val inputAction = action.removePrefix("virtual_")
        val args = buildMap {
            step.x?.let { put("x", it.toString()) }
            step.y?.let { put("y", it.toString()) }
            step.x1?.let { put("x1", it.toString()) }
            step.y1?.let { put("y1", it.toString()) }
            step.x2?.let { put("x2", it.toString()) }
            step.y2?.let { put("y2", it.toString()) }
            put("duration_ms", step.durationMs.toString())
            put("ms", step.durationMs.toString())
            step.text?.let { put("text", it) }
            step.key?.let { put("key", it) }
        }
        val command = VirtualDisplayInputCommand.build(displayId, inputAction, args)
            ?: return failure(index, action, "Invalid virtual screen input parameters")
        if (command.waitMs != null) {
            delay(command.waitMs)
            return result(index, action, true, "Virtual screen $displayId wait ${command.waitMs}ms")
        }
        when (val check = DeviceCommandPolicy.validate(command.shellCommand)) {
            is DeviceCommandPolicy.Check.Invalid -> return failure(index, action, "Input command rejected by policy: ${check.reason}")
            DeviceCommandPolicy.Check.Valid -> Unit
        }
        val execution = displayManager.exec(command.shellCommand)
        return result(
            index,
            action,
            execution.exitCode == 0,
            if (execution.exitCode == 0) {
                "Virtual screen $displayId ${command.description} succeeded"
            } else {
                "Virtual screen $displayId ${command.description} failed (exit=${execution.exitCode})"
            },
        )
    }

    private fun result(index: Int, action: String, success: Boolean, message: String, displayId: Int? = null) =
        AutomationWorkflowStepResult(index, action, success, message, outcomeUnknown = !success, displayId = displayId)

    private fun failure(index: Int, action: String, message: String, outcomeUnknown: Boolean = false) =
        AutomationWorkflowStepResult(index, action, false, message, outcomeUnknown)
}

@Serializable
data class AutomationWorkflowStep(
    val action: String,
    val packageName: String? = null,
    val displayId: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val dpi: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    val key: String? = null,
    val text: String? = null,
    val verifyText: String? = null,
    val exact: Boolean = false,
    val maxSwipes: Int = 0,
    val x1: Int? = null,
    val y1: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val durationMs: Long = 400L,
    val code: String? = null,
    val timeoutMs: Long? = null,
)

@Serializable
data class AutomationWorkflowStepResult(
    val index: Int,
    val action: String,
    val success: Boolean,
    val message: String,
    val outcomeUnknown: Boolean = false,
    val displayId: Int? = null,
)

internal object AutomationWorkflowParser {
    private const val MAX_STEPS = 20

    fun parse(json: String): Result<List<AutomationWorkflowStep>> = runCatching {
        val steps = AppJson.decodeFromString(
            ListSerializer(AutomationWorkflowStep.serializer()),
            json,
        )
        require(steps.isNotEmpty()) { "steps must not be empty" }
        require(steps.size <= MAX_STEPS) { "steps supports at most $MAX_STEPS steps" }
        steps
    }
}

private const val STATUS_RUNNING = "running"
private const val STATUS_FAILED = "failed"
private const val STATUS_FAILED_KNOWN = "failed_known"
private const val DEFAULT_NODE_SCRIPT_TIMEOUT_MS = 30_000L
private const val MAX_NODE_SCRIPT_TIMEOUT_MS = 300_000L
