package io.zer0.muse.tools

/**
 * 工具失败回执的结构化分类。
 *
 * ## 为什么需要它
 *
 * 助手自审：「工具失败只允许我修正一次 —— 这条约束本身是对的，但如果错误信息是含糊的
 * （比如"参数不合法"），我就只能猜，一猜就废」。
 *
 * 现状：失败时只注入一段固定的四级优先级引导（参数错→修正、权限→换工具、网络→重试、
 * 其他→告知用户），模型得自己从错误文本里猜这属于哪一级。猜错就浪费掉唯一一次修正机会。
 *
 * 这里把「这条错误属于哪一类、能不能安全重试」算出来，直接附在失败回执里。
 * 分类只依赖错误文本（各工具返回值没有统一结构），所以规则要保守：
 * 判断不了就归 [FailureKind.UNKNOWN] 且 `retryable=false` —— 宁可让模型来问，
 * 也不要让它对不可重试的失败反复重试。
 */
object ToolFailureClassifier {

    /** 失败类别。 */
    enum class FailureKind {
        /** 参数/路径/格式错误 —— 改参数后重试有意义。 */
        INVALID_ARGUMENT,

        /** 缺权限或通道未就绪 —— 重试无用，要用户去开。 */
        PERMISSION,

        /** 资源不存在（文件/记录/id）。 */
        NOT_FOUND,

        /** 网络或上游暂时不可用 —— 通常可重试一次。 */
        TRANSIENT,

        /** 超时。 */
        TIMEOUT,

        /** 被取消或中断，非错误。 */
        CANCELLED,

        /** 命中安全/审批限制。 */
        BLOCKED,

        /** 无法归类的失败。 */
        UNKNOWN,
    }

    /** 一条失败回执的结构化结论。 */
    data class Verdict(
        val kind: FailureKind,
        val retryable: Boolean,
        /** 给模型的一句话建议，直接拼进回执。 */
        val hint: String,
    )

    private val TIMEOUT_MARKERS = listOf("[超时]", "超时", "timed out", "timeout")
    private val CANCELLED_MARKERS = listOf("[中断]", "[取消]", "已取消", "[interrupted]", "cancelled", "canceled", "aborted")
    private val PERMISSION_MARKERS = listOf(
        "权限", "未授权", "permission denied", "unauthorized", "forbidden", "403",
        "shizuku", "root", "无障碍", "通知使用权", "使用情况访问",
    )
    private val NOT_FOUND_MARKERS = listOf(
        "不存在",
        "未找到",
        "找不到",
        "not found",
        "no such file",
        "404",
    )
    private val TRANSIENT_MARKERS = listOf(
        "网络", "连接失败", "连接超时", "timeout connecting", "connection", "503", "502", "429",
        "rate limit", "too many requests", "暂时", "稍后重试",
    )
    private val BLOCKED_MARKERS = listOf(
        "审批",
        "拒绝",
        "被拦截",
        "安全",
        "越权",
        "denied",
        "blocked",
        "approval",
    )
    private val INVALID_ARGUMENT_MARKERS = listOf(
        "缺少必填", "缺少参数", "参数", "格式", "不合法", "非法", "invalid", "must be", "required",
        "解析失败", "类型错误",
    )

    /**
     * 分类规则表。**顺序即优先级**：越靠前越先判定。
     *
     * 超时/取消必须排在权限之前 —— 取消消息里常夹带「授权」等词，
     * 先判权限会把它误报成「缺权限」。
     */
    private val RULES: List<Rule> = listOf(
        Rule(FailureKind.TIMEOUT, true, TIMEOUT_MARKERS, "Timeout: you may retry once as-is; if it times out again, tell the user the timeout fact; do not keep retrying with varied parameters."),
        Rule(FailureKind.CANCELLED, false, CANCELLED_MARKERS, "The operation was cancelled; that is not a failure. Do not retry; continue according to the user's current intent."),
        Rule(
            FailureKind.PERMISSION,
            false,
            PERMISSION_MARKERS,
            "Missing permission / channel not ready: retrying is useless. First call tool_status to see what is missing, then tell the user the path to enable it.",
        ),
        Rule(FailureKind.BLOCKED, false, BLOCKED_MARKERS, "Blocked by a security policy or approval: do not bypass it; explain to the user why it was blocked and wait for instructions."),
        Rule(
            FailureKind.NOT_FOUND,
            false,
            NOT_FOUND_MARKERS,
            "Target does not exist: first confirm the path/id is correct (list the directory or search if necessary); do not retry with the same value.",
        ),
        Rule(FailureKind.TRANSIENT, true, TRANSIENT_MARKERS, "Transient fault: you may retry once; if it still fails, report it truthfully; do not retry repeatedly and waste time."),
        Rule(
            FailureKind.INVALID_ARGUMENT,
            true,
            INVALID_ARGUMENT_MARKERS,
            "Parameter problem: fix the parameters according to the original error text and retry once; if you cannot fix them, ask the user; do not guess blindly.",
        ),
    )

    /** 一条分类规则。 */
    private class Rule(
        val kind: FailureKind,
        val retryable: Boolean,
        val markers: List<String>,
        val hint: String,
    ) {
        fun matches(lower: String): Boolean = markers.any { lower.contains(it) }
    }

    private val UNKNOWN = Verdict(
        kind = FailureKind.UNKNOWN,
        retryable = false,
        hint = "Cannot determine the cause: do not retry blindly. Read the original error text first; if you truly need to continue, explain the situation to the user first.",
    )

    /**
     * 分类一条失败结果。
     *
     * @param result 工具返回的原始文本（未注入引导语的版本）
     * @param timedOut 是否由执行器的超时逻辑判定（比文本更可靠）
     */
    fun classify(result: String, timedOut: Boolean = false): Verdict {
        val lower = result.trimStart().lowercase()
        // 执行器已判定超时时,文本再像别的类别也按超时处理 —— 那是更可靠的信号。
        val forced = if (timedOut) RULES.first() else null
        val rule = forced ?: RULES.firstOrNull { it.matches(lower) }
        return rule?.let { Verdict(it.kind, it.retryable, it.hint) } ?: UNKNOWN
    }

    /**
     * 生成拼进失败回执的结构化行。
     *
     * 形如：`[失败分类] kind=permission retryable=false hint=...`
     * 用 key=value 而不是自然语言，方便模型直接读取，也便于日志检索。
     */
    fun receiptLine(verdict: Verdict): String =
        "[Failure classification] kind=${verdict.kind.name.lowercase()} retryable=${verdict.retryable} hint=${verdict.hint}"
}
