package io.zer0.muse.tools

import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * P2-6 BrowserAutomationTool:浏览器自动化工具集(AI 可调用)。
 *
 * 把 [BrowserManager] 的能力封装为 6 个独立工具,注册到 [ToolRegistry]:
 *  1. [TOOL_NAVIGATE]   — browser_navigate(url)        导航到 URL
 *  2. [TOOL_CLICK]      — browser_click(selector)       点击元素
 *  3. [TOOL_TYPE]       — browser_type(selector, text)  输入文本
 *  4. [TOOL_EXTRACT]    — browser_extract(selector)     提取文本
 *  5. [TOOL_SCROLL_BOTTOM] — browser_scroll_bottom()    滚动到底部
 *  6. [TOOL_GET_HTML]   — browser_get_html()            获取当前页 HTML(截断)
 *
 * 桥接说明(同 [CodeExecutionTool]):
 *  - [executeFromArgs] 是同步函数,适配 ToolRegistry 的 ToolFn 签名 `(Map<String,String>) -> String`
 *  - 内部用 [runBlocking] 桥接到 [BrowserManager] 的 suspend 函数 — 因 [ToolRouteExecutionGuard.executeFromJson]
 *    在 IO 协程中调用,阻塞 IO 线程等待主线程
 *    WebView 回调,主线程未被阻塞,无死锁风险
 *
 * 注册方式(按 [ToolRegistry.init] 中的 CodeExecutionTool 注册):
 * ```kotlin
 * BrowserAutomationTool.toolDefs().forEach { def ->
 *     toolRegistry.register(def) { args ->
 *         BrowserAutomationTool.executeFromArgs(def.name, args, browserManager)
 *     }
 * }
 * ```
 */
object BrowserAutomationTool {

    private const val TAG = "BrowserAutomationTool"

    /** 工具名常量(注册到 ToolRegistry 的 id)。 */
    const val TOOL_NAVIGATE = "browser_navigate"
    const val TOOL_CLICK = "browser_click"
    const val TOOL_TYPE = "browser_type"
    const val TOOL_EXTRACT = "browser_extract"
    const val TOOL_SCROLL_BOTTOM = "browser_scroll_bottom"
    const val TOOL_GET_HTML = "browser_get_html"
    const val TOOL_SNAPSHOT = "browser_snapshot"
    const val TOOL_SCREENSHOT = "browser_screenshot"

    /**
     * 全部工具定义列表(供 [ToolRegistry.register] 批量注册)。
     *
     * @return 6 个 [ToolRegistry.ToolDef] 的列表
     */
    fun toolDefs(): List<ToolRegistry.ToolDef> = listOf(
        ToolRegistry.ToolDef(
            name = TOOL_NAVIGATE,
            // v1.0.75 fix (工具审查 02): 补时序依赖声明
            description = "Browser automation: navigate to the specified URL. You must call this tool to navigate first, then use browser_click/browser_type/browser_extract on the page. All browser tools return JSON containing success and data/error." +
                "The URL may omit the scheme (e.g. example.com automatically gets https://). 10-second timeout.",
            parameters = mapOf(
                "url" to "Required, target URL, e.g. https://example.com or example.com",
            ),
            required = setOf("url"),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_CLICK,
            description = "Browser automation: click the target element. Two positioning methods, choose one:" +
                "1) selector (CSS selector, same syntax as document.querySelector);" +
                "2) numberId (the element number returned by browser_snapshot, recommended — no need to know the page structure).",
            parameters = mapOf(
                "selector" to "Optional, CSS selector, e.g. '#login-btn' or 'button.submit'",
                "numberId" to "Optional, snapshot element number (alternative to selector; takes priority when provided)",
            ),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_TYPE,
            description = "Browser automation: type text into the target input/textarea." +
                "Positioning, choose one: selector (CSS selector) or numberId (snapshot number, recommended)." +
                "Automatically fires input + change events for compatibility with frameworks such as React/Vue.",
            parameters = mapOf(
                "selector" to "Optional, CSS selector, e.g. '#search-input'",
                "numberId" to "Optional, snapshot element number (alternative to selector; takes priority when provided)",
                "text" to "Required, the text content to input",
            ),
            required = setOf("text"),
            category = "built-in",
            riskLevel = ToolRiskLevel.HIGH,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_EXTRACT,
            // v1.0.75 fix (工具审查 02): 与 parse_link 区分
            description = "Browser automation: extract the textContent of elements matched by a CSS selector." +
                "Returns an empty string when nothing matches. Can be used to grab product prices, article bodies, list items, etc." +
                "Different from parse_link: this tool reads the browser's currently loaded page; parse_link fetches a standalone page from a URL.",
            parameters = mapOf(
                "selector" to "Required, CSS selector, e.g. '.price' or 'article h1'",
            ),
            required = setOf("selector"),
            category = "built-in",
            riskLevel = ToolRiskLevel.NORMAL,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_SCROLL_BOTTOM,
            description = "Browser automation: scroll to the bottom of the page. Often used in infinite-scroll scenarios to load more content (e.g. product lists, feeds).",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.NORMAL,
        ),
        ToolRegistry.ToolDef(
            name = TOOL_GET_HTML,
            description = "Browser automation: get the current page's full HTML source." +
                "Can be used to analyse page structure, extract form fields, etc.; long results offer a paged-reading entry.",
            parameters = emptyMap(),
            required = emptySet(),
            category = "built-in",
            riskLevel = ToolRiskLevel.NORMAL,
        ),
        snapshotToolDef(),
        screenshotToolDef(),
    )

    /** 页面结构快照工具定义。 */
    private fun snapshotToolDef() = ToolRegistry.ToolDef(
        name = TOOL_SNAPSHOT,
        description = "Browser automation: extract a structured snapshot of the current page — title/URL/interactive elements" +
            "(links/buttons/input fields, with sequential numbers)/body preview. Use it to understand the page structure, then operate by number with" +
            "browser_click/browser_type's numberId.",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    /**
     * v2.6.0: 页面渲染图截图工具定义(单独抽出,避免 [toolDefs] 过长)。
     * 让模型能真正“看”到页面像素,而不只是 HTML/文本。
     */
    private fun screenshotToolDef() = ToolRegistry.ToolDef(
        name = TOOL_SCREENSHOT,
        description = "Browser automation: capture the visible rendering of the current page (real pixels, including images/CSS layout)," +
            "and return it to the model as an image. Use it when the page content needs to be 'seen' (e.g. images, charts, captchas, layout effects," +
            "canvas/maps/video frames) rather than just the HTML structure.",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    /**
     * 同步桥接:适配 ToolRegistry 的 ToolFn 签名。
     *
     * 内部用 [runBlocking] 调用 [BrowserManager] 的 suspend 函数:
     *  - [ToolRouteExecutionGuard.executeFromJson] 在 IO 协程中调用,
     *    当前线程为 IO 线程,阻塞等待主线程 WebView 回调时主线程空闲,无死锁风险
     *
     * @param toolName 工具名(用于分发到对应 BrowserManager API)
     * @param args 参数 map
     * @param browserManager 浏览器管理器单例
     * @return JSON 字符串,包含 success 字段(true/false)与 data/error 字段
     */
    fun executeFromArgs(toolName: String, args: Map<String, String>, browserManager: BrowserManager): String = runBlocking {
        try {
            when (toolName) {
                TOOL_NAVIGATE -> {
                    val url = args["url"]?.takeIf { it.isNotBlank() }
                        ?: return@runBlocking buildResult(success = false, error = "Parameter url is missing or empty")
                    browserManager.navigate(url).fold(
                        onSuccess = { buildResult(success = true, data = "Navigated to: $url") },
                        onFailure = { e -> buildResult(success = false, error = e.message ?: "Navigation failed") },
                    )
                }
                TOOL_CLICK -> {
                    val numberId = args["numberId"]?.trim()?.toIntOrNull()
                    val selector = args["selector"]?.takeIf { it.isNotBlank() }
                    when {
                        numberId != null -> browserManager.evaluateJs(buildNumberedClickJs(numberId)).fold(
                            onSuccess = { raw ->
                                val ok = raw.contains("OK")
                                buildResult(
                                    success = true,
                                    data = if (ok) "Clicked element number $numberId" else "Number $numberId not found (the page may have refreshed; run browser_snapshot again)",
                                )
                            },
                            onFailure = { e -> buildResult(success = false, error = e.message ?: "Click failed") },
                        )
                        selector != null -> browserManager.click(selector).fold(
                            onSuccess = { hit -> buildResult(success = true, data = if (hit) "Clicked element: $selector" else "No element matched: $selector") },
                            onFailure = { e -> buildResult(success = false, error = e.message ?: "Click failed") },
                        )
                        else -> buildResult(success = false, error = "Parameter selector or numberId: at least one is required")
                    }
                }
                TOOL_TYPE -> {
                    val numberId = args["numberId"]?.trim()?.toIntOrNull()
                    val selector = args["selector"]?.takeIf { it.isNotBlank() }
                    val text = args["text"] ?: return@runBlocking buildResult(success = false, error = "Parameter text is missing")
                    when {
                        numberId != null -> browserManager.evaluateJs(buildNumberedTypeJs(numberId, text)).fold(
                            onSuccess = { raw ->
                                val ok = raw.contains("OK")
                                buildResult(
                                    success = true,
                                    data = if (ok) "Text entered into element number $numberId" else "Number $numberId not found (the page may have refreshed; run browser_snapshot again)",
                                )
                            },
                            onFailure = { e -> buildResult(success = false, error = e.message ?: "Input failed") },
                        )
                        selector != null -> browserManager.type(selector, text).fold(
                            onSuccess = { hit -> buildResult(success = true, data = if (hit) "Text entered into: $selector" else "No element matched: $selector") },
                            onFailure = { e -> buildResult(success = false, error = e.message ?: "Input failed") },
                        )
                        else -> buildResult(success = false, error = "Parameter selector or numberId: at least one is required")
                    }
                }
                TOOL_EXTRACT -> {
                    val selector = args["selector"]?.takeIf { it.isNotBlank() }
                        ?: return@runBlocking buildResult(success = false, error = "Parameter selector is missing or empty")
                    browserManager.extractText(selector).fold(
                        onSuccess = { text -> buildResult(success = true, data = text) },
                        onFailure = { e ->
                            Logger.w(TAG, "extractText failed: selector=$selector | url=${browserManager.currentUrl.value} | err=${e.message}")
                            buildResult(success = false, error = e.message ?: "Extraction failed")
                        },
                    )
                }
                TOOL_SCROLL_BOTTOM -> {
                    browserManager.scrollToBottom().fold(
                        onSuccess = { buildResult(success = true, data = "Scrolled to the bottom") },
                        onFailure = { e -> buildResult(success = false, error = e.message ?: "Scroll failed") },
                    )
                }
                TOOL_SNAPSHOT -> {
                    browserManager.evaluateJs(SNAPSHOT_JS).fold(
                        onSuccess = { json -> buildResult(success = true, data = json) },
                        onFailure = { e -> buildResult(success = false, error = e.message ?: "Snapshot extraction failed") },
                    )
                }
                TOOL_GET_HTML -> {
                    val html = browserManager.currentHtml.value
                    buildResult(success = true, data = html)
                }
                TOOL_SCREENSHOT -> {
                    browserManager.captureScreenshot().fold(
                        onSuccess = { base64 ->
                            if (base64.isBlank()) {
                                buildResult(success = false, error = "Screenshot content is empty (the page may not have finished rendering)")
                            } else {
                                // v2.6.0: 截图 results 以特殊字段承载，编排器据此追加一条带图的
                                // 用户消息（工具结果本身无法携带图片给模型，见 ToolOrchestrator）。
                                // data 只给模型一个占位提示，实际图片走 screenshot 字段。
                                val sizeKb = base64.length / 1024
                                buildScreenshotResult(
                                    data = "Captured the current page (about ${sizeKb}KB, image attached)",
                                    screenshotBase64 = base64,
                                )
                            }
                        },
                        onFailure = { e -> buildResult(success = false, error = e.message ?: "Screenshot failed") },
                    )
                }
                else -> buildResult(success = false, error = "Unknown tool: $toolName")
            }
        } catch (e: Exception) {
            Logger.e(TAG, "executeFromArgs exception: toolName=$toolName, msg=${e.message}", e)
            buildResult(success = false, error = e.message ?: "Execution exception")
        }
    }

    /**
     * 构造执行结果 JsonObject 并序列化为字符串。
     *
     * 字段约定:
     *  - success: Boolean — 执行是否成功
     *  - data: String? — 成功时的返回值(导航/点击/输入为提示文本;提取为内容;get_html 为 HTML 源码)
     *  - error: String? — 失败时的错误信息
     */
    private fun buildResult(success: Boolean, data: String? = null, error: String? = null): String {
        val obj: JsonObject = buildJsonObject {
            put("success", JsonPrimitive(success))
            if (data != null) {
                put("data", JsonPrimitive(data))
            } else {
                put("data", JsonNull)
            }
            if (error != null) {
                put("error", JsonPrimitive(error))
            } else {
                put("error", JsonNull)
            }
        }
        return obj.toString()
    }

    /**
     * v2.6.0: 截图专用结果 —— 在标准结果上附加 `screenshot` 字段(Base64 PNG)。
     *
     * 工具结果通过 API 回填给模型时只能带文本(function_call_output 限字符串),
     * 所以编排器会识别本字段,把图片作为一条独立的带图用户消息追加到对话。
     *
     * 字段约定:
     *  - success: Boolean
     *  - data: String — 给模型的文本提示(如“已截取当前页面”)
     *  - screenshot: String — Base64 PNG(无 data: 前缀)
     */
    private fun buildScreenshotResult(data: String, screenshotBase64: String): String {
        val obj: JsonObject = buildJsonObject {
            put("success", JsonPrimitive(true))
            put("data", JsonPrimitive(data))
            put("error", JsonNull)
            // 标记字段名与编排器约定一致(见 ToolOrchestrator 的截图结果处理)。
            put(SCREENSHOT_RESULT_FIELD, JsonPrimitive(screenshotBase64))
        }
        return obj.toString()
    }

    /**
     * v2.6.0: 截图结果在工具返回 JSON 中的字段名。
     * 编排器读到该字段就把图片作为带图用户消息追加到对话。
     */
    const val SCREENSHOT_RESULT_FIELD = "screenshot"

    /**
     * v1.0.92: 页面结构化快照脚本 — 提取可交互元素并缓存到 window.__museEls,
     * 供 numberId 模式的 click/type 按编号操作(页面刷新后缓存失效,需重新快照)。
     *
     * 预算: 链接≤40 / 按钮≤30 / 输入框≤20,正文预览≤3000 字符。
     */
    private const val SNAPSHOT_JS = """(function(){
  try {
    function textOf(el){ return (el && (el.innerText || el.textContent) || '').replace(/\s+/g,' ').trim(); }
    var els = [];
    var links = [], buttons = [], inputs = [];
    document.querySelectorAll('a[href]').forEach(function(a){
      if (links.length >= 40) return;
      var t = textOf(a); if (!t) return;
      links.push({id: els.length, text: t.substring(0, 80), href: a.href});
      els.push(a);
    });
    document.querySelectorAll('button,[role=button],input[type=submit],input[type=button]').forEach(function(b){
      if (buttons.length >= 30) return;
      var t = textOf(b) || b.value || ''; if (!t) return;
      buttons.push({id: els.length, text: t.substring(0, 80)});
      els.push(b);
    });
    document.querySelectorAll('input:not([type=hidden]),textarea,select').forEach(function(inp){
      if (inputs.length >= 20) return;
      inputs.push({id: els.length, name: inp.name || '', placeholder: inp.placeholder || '', type: inp.type || 'text'});
      els.push(inp);
    });
    window.__museEls = els;
    var body = textOf(document.body);
    return {
      url: location.href,
      title: document.title,
      links: links,
      buttons: buttons,
      inputs: inputs,
      bodyPreview: body.substring(0, 3000),
      truncated: body.length > 3000
    };
  } catch (e) { return {error: String((e && e.message) || e)}; }
})()"""

    /** v1.0.92: numberId 模式点击脚本。 */
    private fun buildNumberedClickJs(index: Int): String = "(function(){var el=(window.__museEls||[])[$index];" +
        "if(!el){return 'MISS';}" +
        "try{el.scrollIntoView({block:'center'});}catch(e){/* scroll is best-effort */}" +
        "el.click();return 'OK';})()"

    /** v1.0.92: numberId 模式输入脚本(text 经 JSON 编码防注入)。 */
    private fun buildNumberedTypeJs(index: Int, text: String): String {
        val encoded = AppJson.encodeToString(String.serializer(), text)
        return "(function(){var el=(window.__museEls||[])[$index];" +
            "if(!el){return 'MISS';}" +
            "el.focus();el.value=$encoded;" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));return 'OK';})()"
    }
}
