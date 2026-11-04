package io.zer0.muse.tools

import android.content.Context
import io.zer0.muse.runtime.PythonRuntime
import io.zer0.muse.tools.script.PythonSkillEngine
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * v2.7 扩展运行时（Python）：Python 脚本执行工具（execute_python_script）。
 *
 * 在内置 Python 运行时中执行 Python 脚本：完整标准库（含 ssl/sqlite3/ctypes 等
 * C 扩展）、文件读写、网络。可 `import` 运行时 site-packages 区
 * （filesDir/muse-python/lib/python3.14/site-packages）中的第三方纯 Python 包。
 *
 * 与 [NodeScriptTool]（execute_node_script）同构：高风险工具，ASK / STRICT 模式
 * 要求审批；TRUSTED 模式依用户明确选择免确认。
 *
 * 返回结构（JSON，与 execute_node_script / execute_javascript 对齐）：
 *  - result: string — 脚本 `result` 变量的 JSON 字符串形式
 *  - logs: array — print 输出行数组
 *  - error: string | null
 */
object PythonScriptTool {

    const val TOOL_NAME = "execute_python_script"

    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val MAX_TIMEOUT_MS = 300_000L
    const val MAX_SCRIPT_CHARS = 128_000

    fun toolDef() = ToolRegistry.ToolDef(
        name = TOOL_NAME,
        description = "Execute a Python script in the built-in Python 3.14 runtime (full standard library: ssl/sqlite3/" +
            "ctypes/json/re, etc.; file reading/writing, network; can import third-party pure-Python packages)." +
            "Assign the result to return to the variable result. Returns JSON (result/logs/error). Default timeout 30 seconds." +
            "This is the only entry point for running Python: the built-in runtime has no python/python3 command, so do not use " +
            "terminal_exec/execute_shell to look for an interpreter or rummage through the bin directory (it is empty);" +
            "when the script needs to spawn a subprocess, use sys.executable or os.environ['MUSE_PYTHON_BIN'].",
        parameters = mapOf(
            "code" to "Required, Python script code (may import the standard library; assign the result to the variable result)",
            "timeout_ms" to "Optional, timeout in milliseconds, default 30000, maximum 300000",
        ),
        required = setOf("code"),
        category = "built-in",
        parameterTypes = mapOf("timeout_ms" to "integer"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(context: Context, args: Map<String, String>): String {
        val code = args["code"].orEmpty()
        val timeoutMs = args["timeout_ms"]?.toLongOrNull()?.coerceIn(1L, MAX_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS
        return when {
            code.isBlank() -> errorJson("Parameter code is missing or empty")
            code.length > MAX_SCRIPT_CHARS -> errorJson("Script exceeds the maximum length ($MAX_SCRIPT_CHARS characters)")
            else -> formatResultJson(evaluate(context, code, timeoutMs))
        }
    }

    /** 共享执行路径（供直接脚本工具与手机 Agent 工作流复用）。 */
    suspend fun evaluate(context: Context, code: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): SkillEngineResult {
        return when {
            code.isBlank() -> SkillEngineResult.Error("Parameter code is missing or empty")
            code.length > MAX_SCRIPT_CHARS -> SkillEngineResult.Error("Script exceeds the maximum length ($MAX_SCRIPT_CHARS characters)")
            else -> {
                // 确保运行时数据（stdlib）已解压；首次调用会解压一次。
                PythonRuntime.ensureData(context).getOrElse { e ->
                    return SkillEngineResult.Error("Python runtime data not ready: ${e.message}")
                }
                PythonSkillEngine(context).eval(
                    script = code,
                    timeoutMs = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS),
                    scopeKey = "builtin-python-script",
                    pluginConfigJson = null,
                )
            }
        }
    }

    fun formatResultJson(result: SkillEngineResult): String = when (result) {
        is SkillEngineResult.Success -> resultJson(result.valueJson, result.consoleLogs, null)
        is SkillEngineResult.Error -> errorJson(result.message, result.consoleLogs)
    }

    /** 同步桥接（适配 ToolRegistry 的 `(Map<String,String>) -> String` 签名）。 */
    fun executeFromArgs(args: Map<String, String>, context: Context): String = runBlocking {
        execute(context, args)
    }

    private fun resultJson(result: String, logs: List<String>, error: String?): String = buildJsonObject {
        put("result", result)
        put("logs", buildJsonArray { logs.forEach { add(JsonPrimitive(it)) } })
        put("error", error?.let { JsonPrimitive(it) } ?: JsonNull)
    }.toString()

    private fun errorJson(error: String, logs: List<String> = emptyList()): String = buildJsonObject {
        put("result", "")
        put("logs", buildJsonArray { logs.forEach { add(JsonPrimitive(it)) } })
        put("error", JsonPrimitive(error))
    }.toString()
}

/**
 * v2.7 扩展运行时（Python）：Python 脚本执行工具注册器。
 */
class PythonScriptToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val context: Context,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(PythonScriptTool.toolDef()) { args ->
            PythonScriptTool.executeFromArgs(args, context)
        }
    }
}
