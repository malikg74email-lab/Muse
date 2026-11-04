package io.zer0.muse.tools.script

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.tools.TOOL_OUTPUTS_DIR
import io.zer0.muse.tools.ToolFailureText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * F-17 + v2.2.1 大沙盒:JS 桥接执行器（__bridge__）。
 *
 * JS 函数可通过返回桥接请求对象获得受控能力（网络/沙盒文件/剪贴板/通知/设备信息）：
 * ```js
 * return { __bridge__: true, action: "http_get", params: { url: "..." } };
 * ```
 * 一次执行只截获一个桥接请求，Kotlin 侧在脚本执行结果处取到它，审计后执行对应的
 * 安全实现，再把结果作为技能返回值返回（request-response 模式）。
 *
 * 注意边界：桥接是「让脚本拿到受控能力」的通道（沙盒文件/剪贴板/通知等），
 * 不是网络代理——沙盒里直接 fetch 不走这里，因而不受本文件的 SSRF 判据保护。
 *
 * 动作清单与门槛（v2.x 沙盒放开后不再需要逐项声明）：
 *  - `echo`：原样回显（验证通路）
 *  - `http_get` / `http_post`：SSRF 防护 HTTP（拒内网/本地地址）
 *  - `fs_list` / `fs_read` / `fs_write` / `fs_delete`：沙盒目录内文件操作
 *    （限宿主的 [Host.fsRoot]，路径 canonical 校验防逃逸）
 *  - `clipboard_read` / `clipboard_write`
 *  - `notify`：应用内提示
 *  - `device_info`：设备基础信息
 *
 * 调用方默认传 [ALL_ACTIONS] 全放行；能力声明仅用于展示，不再作为硬门槛。
 * 调用方（skill 执行结果处理处）先调用 [tryHandle]：若返回值不含 `__bridge__`
 * 字段则返回 [HandleResult.NotBridge]（按普通结果处理）；否则返回执行产物/错误。
 */
object SkillBridge {

    private const val TAG = "SkillBridge"

    /** v2.2.1: http_post 请求体上限(256KB)。 */
    private const val MAX_REQUEST_BYTES = 256 * 1024

    /** B-2: 手动跟随重定向的最大跳数,防止重定向链无限延伸。 */
    private const val MAX_REDIRECTS = 5

    /** 默认放行的最小动作集（向后兼容：仅 echo + http_get）。 */
    val DEFAULT_ALLOWED_ACTIONS: Set<String> = setOf("echo", "http_get")

    /**
     * v2.x（沙盒放开）：全部桥接动作。调用方默认传它，不再按 manifest 声明逐项报批。
     *
     * 保留的底线不在“动作是否允许”，而在各动作实现内：
     *  - `fs_*` 锁定在宿主 [Host.fsRoot]（路径 canonical 校验防逃逸）；
     *  - `http_*` 走 SSRF 防护（拒内网/本地地址）。
     */
    val ALL_ACTIONS: Set<String> = setOf(
        "echo",
        "http_get",
        "http_post",
        "fs_list",
        "fs_read",
        "fs_write",
        "fs_delete",
        "clipboard_read",
        "clipboard_write",
        "notify",
        "device_info",
    )

    /**
     * v2.2.1: 桥接宿主 —— 提供动作所需的系统上下文与沙盒根目录。
     *
     * @param context 剪贴板/提示等系统能力需要；这类动作之外的场景可为 null
     * @param fsRoot `fs_*` 动作的沙盒根（如 `workspace/plugins/<pluginId>`）；为空时 fs 动作拒绝
     */
    class Host(val context: Context?, val fsRoot: File?)

    /**
     * 桥接专用 HTTP 出口。DNS 解析、连接地址校验和逐跳重定向都在该出口内完成。
     * 不复用其他网络出口，避免把 DNS 策略泛化到聊天、插件或 WebView 流量。
     */
    private val bridgeHttpClient: SkillBridgeHttpClient by lazy { SkillBridgeHttpClient() }

    /**
     * 桥接处理结果密封类。
     *
     *  - [NotBridge]：返回值不含 `__bridge__`，调用方按普通脚本结果继续处理
     *  - [Output]：桥接请求已执行，产出结果 JSON 字符串
     *  - [Failure]：桥接请求解析或执行失败（含"action 不支持"）
     */
    sealed class HandleResult {
        object NotBridge : HandleResult()
        data class Output(val json: String) : HandleResult()
        data class Failure(val message: String) : HandleResult()
    }

    /**
     * 尝试处理一个 skill 脚本的执行返回值。
     *
     * @param valueJson 脚本返回值的 JSON 字符串（SkillEngineResult.Success.valueJson）
     * @param allowedActions 放行动作集（v2.x 默认传 [ALL_ACTIONS] 全放行）
     * @param host 桥接宿主（fs/剪贴板等动作需要）
     * @return 见 [HandleResult]
     */
    suspend fun tryHandle(valueJson: String, allowedActions: Set<String> = DEFAULT_ALLOWED_ACTIONS, host: Host? = null): HandleResult {
        val obj = runCatching {
            AppJson.parseToJsonElement(valueJson) as? JsonObject
        }.getOrNull() ?: return HandleResult.NotBridge
        val isBridge = (obj["__bridge__"] as? JsonPrimitive)?.booleanOrNull == true
        if (!isBridge) return HandleResult.NotBridge

        val action = (obj["action"] as? JsonPrimitive)?.contentOrNull
            ?: return HandleResult.Failure("__bridge__ missing the action field")
        if (action !in allowedActions) {
            return HandleResult.Failure("__bridge__ action '$action' is not in the allowed action set")
        }
        val params = obj["params"] as? JsonObject ?: buildJsonObject { }
        return when (action) {
            "echo" -> HandleResult.Output(params.toString())
            "http_get" -> withContext(Dispatchers.IO) { execHttpGet(params, host) }
            "http_post" -> withContext(Dispatchers.IO) { execHttpPost(params, host) }
            "fs_list" -> withContext(Dispatchers.IO) { execFsList(host, params) }
            "fs_read" -> withContext(Dispatchers.IO) { execFsRead(host, params) }
            "fs_write" -> withContext(Dispatchers.IO) { execFsWrite(host, params) }
            "fs_delete" -> withContext(Dispatchers.IO) { execFsDelete(host, params) }
            "clipboard_read" -> execClipboardRead(host)
            "clipboard_write" -> execClipboardWrite(host, params)
            "notify" -> execNotify(host, params)
            "device_info" -> HandleResult.Output(deviceInfoJson())
            else -> HandleResult.Failure("__bridge__ action '$action' is not supported")
        }
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    /** 执行 http_get：SSRF 防护(含重定向逐跳校验) + 完整输出落盘。 */
    private fun execHttpGet(params: JsonObject, host: Host?): HandleResult {
        val url = (params["url"] as? JsonPrimitive)?.contentOrNull
            ?: return HandleResult.Failure("http_get missing the url parameter")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return HandleResult.Failure("http_get url only supports the http/https protocols")
        }
        val outputDirectory = host?.context?.let { File(it.filesDir, TOOL_OUTPUTS_DIR) }
            ?: return HandleResult.Failure("http_get missing the full-response storage context")
        return execHttpGetWithRedirects(url, outputDirectory)
    }

    /** v2.2.1: 执行 http_post（不跟随重定向，fail-closed）。 */
    private fun execHttpPost(params: JsonObject, host: Host?): HandleResult {
        val url = (params["url"] as? JsonPrimitive)?.contentOrNull
            ?: return HandleResult.Failure("http_post missing the url parameter")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return HandleResult.Failure("http_post url only supports the http/https protocols")
        }
        val outputDirectory = host?.context?.let { File(it.filesDir, TOOL_OUTPUTS_DIR) }
            ?: return HandleResult.Failure("http_post missing the full-response storage context")
        val body = (params["body"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (body.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            return HandleResult.Failure("http_post request body exceeds the limit (${MAX_REQUEST_BYTES / 1024}KB)")
        }
        val contentType = (params["content_type"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: "text/plain; charset=utf-8"
        return try {
            val result = bridgeHttpClient.postComplete(url, body, contentType, outputDirectory)
            HandleResult.Output(
                buildJsonObject {
                    put("status", JsonPrimitive(result.status))
                    put("body", JsonPrimitive(result.body))
                }.toString(),
            )
        } catch (e: Exception) {
            Logger.w(TAG, "http_post bridge failed: ${e.message}")
            val message = if (e is SkillBridgeHttpClient.PinnedAddressException) {
                "http_post refused access to an intranet/non-public address: ${e.url}"
            } else {
                // v2.2.1: 带地址与失败阶段(超时/解析/TLS)
                ToolFailureText.httpFailure("http_post", url, e)
            }
            HandleResult.Failure(message)
        }
    }

    /**
     * 手动跟随重定向的 http_get 执行。
     *
     * DNS 解析与连接级校验由 [SkillBridgeHttpClient] 完成；它为每一跳固定同一份已校验
     * 地址列表，避免“先校验 getAllByName、再由系统重新解析”的 TOCTOU。重定向仍由客户端
     * 禁用自动跟随并逐跳校验，任一跳失败即 fail-closed。
     */
    @Suppress("TooGenericExceptionCaught") // 底层网络异常统一转为用户可见失败信息
    private fun execHttpGetWithRedirects(startUrl: String, outputDirectory: File): HandleResult {
        return try {
            val result = bridgeHttpClient.getComplete(startUrl, outputDirectory, MAX_REDIRECTS)
            HandleResult.Output(
                buildJsonObject {
                    put("status", JsonPrimitive(result.status))
                    put("body", JsonPrimitive(result.body))
                }.toString(),
            )
        } catch (e: Exception) {
            Logger.w(TAG, "http_get bridge failed: ${e.message}")
            val message = if (e is SkillBridgeHttpClient.PinnedAddressException) {
                "http_get refused access to an intranet/non-public address: ${e.url}"
            } else {
                // v2.2.1: 带地址与失败阶段(超时/解析/TLS)
                ToolFailureText.httpFailure("http_get", startUrl, e)
            }
            HandleResult.Failure(message)
        }
    }

    // ── 沙盒文件 ──────────────────────────────────────────────────────────

    private fun fsRootOf(host: Host?): File = host?.fsRoot ?: throw SkillBridgeFs.BridgeFsException("The current action does not provide a sandbox directory")

    private fun execFsList(host: Host?, params: JsonObject): HandleResult {
        return try {
            val root = fsRootOf(host)
            val path = paramString(params, "path")
            val entries = SkillBridgeFs.list(root, path)
            HandleResult.Output(
                buildJsonObject {
                    put("path", JsonPrimitive(path.ifEmpty { "." }))
                    put(
                        "entries",
                        buildJsonArray {
                            entries.forEach { entry ->
                                add(
                                    buildJsonObject {
                                        put("name", JsonPrimitive(entry.name))
                                        put("dir", JsonPrimitive(entry.dir))
                                        put("size", JsonPrimitive(entry.size))
                                    },
                                )
                            }
                        },
                    )
                }.toString(),
            )
        } catch (e: Exception) {
            fsFailure("fs_list", e)
        }
    }

    private fun execFsRead(host: Host?, params: JsonObject): HandleResult {
        return try {
            val root = fsRootOf(host)
            val path = paramString(params, "path")
            if (path.isEmpty()) return HandleResult.Failure("fs_read missing the path parameter")
            val content = SkillBridgeFs.read(
                root = root,
                path = path,
                offsetChars = paramString(params, "offset_chars").toIntOrNull(),
                lengthChars = paramString(params, "length_chars").toIntOrNull(),
            )
            HandleResult.Output(
                buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("content", JsonPrimitive(content))
                }.toString(),
            )
        } catch (e: Exception) {
            fsFailure("fs_read", e)
        }
    }

    private fun execFsWrite(host: Host?, params: JsonObject): HandleResult {
        return try {
            val root = fsRootOf(host)
            val path = paramString(params, "path")
            if (path.isEmpty()) return HandleResult.Failure("fs_write missing the path parameter")
            val content = paramString(params, "content")
            val written = SkillBridgeFs.write(root, path, content)
            HandleResult.Output(
                buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("written_bytes", JsonPrimitive(written))
                }.toString(),
            )
        } catch (e: Exception) {
            fsFailure("fs_write", e)
        }
    }

    private fun execFsDelete(host: Host?, params: JsonObject): HandleResult {
        return try {
            val root = fsRootOf(host)
            val path = paramString(params, "path")
            if (path.isEmpty()) return HandleResult.Failure("fs_delete missing the path parameter")
            SkillBridgeFs.delete(root, path)
            HandleResult.Output(
                buildJsonObject {
                    put("path", JsonPrimitive(path))
                    put("deleted", JsonPrimitive(true))
                }.toString(),
            )
        } catch (e: Exception) {
            fsFailure("fs_delete", e)
        }
    }

    private fun fsFailure(action: String, e: Exception): HandleResult = HandleResult.Failure(
        if (e is SkillBridgeFs.BridgeFsException) {
            "$action rejected: ${e.message}"
        } else {
            "$action failed: ${e.message ?: "unknown error"}"
        },
    )

    // ── 系统能力 ──────────────────────────────────────────────────────────

    private suspend fun execClipboardRead(host: Host?): HandleResult {
        val context = host?.context ?: return HandleResult.Failure("The bridge host has no Context; cannot read the clipboard")
        return withContext(Dispatchers.Main) {
            try {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@withContext HandleResult.Failure("Clipboard service unavailable")
                val text = manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                HandleResult.Output(buildJsonObject { put("text", JsonPrimitive(text)) }.toString())
            } catch (e: Exception) {
                HandleResult.Failure("clipboard_read failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    private suspend fun execClipboardWrite(host: Host?, params: JsonObject): HandleResult {
        val context = host?.context ?: return HandleResult.Failure("The bridge host has no Context; cannot write the clipboard")
        val text = paramString(params, "text")
        return withContext(Dispatchers.Main) {
            try {
                val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    ?: return@withContext HandleResult.Failure("Clipboard service unavailable")
                manager.setPrimaryClip(ClipData.newPlainText("muse-skill", text))
                HandleResult.Output("""{"ok":true}""")
            } catch (e: Exception) {
                HandleResult.Failure("clipboard_write failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    private suspend fun execNotify(host: Host?, params: JsonObject): HandleResult {
        val context = host?.context ?: return HandleResult.Failure("The bridge host has no Context; cannot send a notification")
        val title = paramString(params, "title")
        val body = paramString(params, "body").ifBlank { title }
        if (body.isBlank()) return HandleResult.Failure("notify missing title/body")
        return withContext(Dispatchers.Main) {
            try {
                android.widget.Toast.makeText(context, body, android.widget.Toast.LENGTH_SHORT).show()
                HandleResult.Output("""{"ok":true}""")
            } catch (e: Exception) {
                HandleResult.Failure("notify failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    private fun deviceInfoJson(): String = buildJsonObject {
        put("brand", JsonPrimitive(Build.BRAND))
        put("model", JsonPrimitive(Build.MODEL))
        put("android", JsonPrimitive(Build.VERSION.RELEASE))
        put("sdk", JsonPrimitive(Build.VERSION.SDK_INT))
    }.toString()

    private fun paramString(params: JsonObject, key: String): String = (params[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
