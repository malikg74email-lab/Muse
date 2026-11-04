package io.zer0.muse.automation.core

/**
 * v2.x 自动化一期:设备命令策略(纯逻辑,可单测)。
 *
 * device_shell 工具的唯一安全闸门:命令必须命中白名单动词(部分需二级子命令),
 * 且不允许任何管道/重定向/多命令拼接字符。执行层位于
 * [AutomationManager.execTiered](Shizuku 优先,Root 兜底)。
 */
object DeviceCommandPolicy {
    /** 校验结果。 */
    sealed class Check {
        object Valid : Check()

        data class Invalid(val reason: String) : Check()
    }

    /** 禁止字符:管道 / 重定向 / 命令分隔 / 子表达式 / 环境展开。 */
    private val FORBIDDEN_CHARS = setOf(';', '|', '&', '>', '<', '`', '$', '(', ')', '{', '}', '\n', '\r')

    /**
     * 动词白名单:value 为 null 表示第二段参数自由(如 dumpsys 的服务名);
     * 非 null 表示第二段必须命中子命令集合。
     * v2.2.1: `input` 额外允许 `-d/--display <displayId>` 前缀(虚拟屏输入注入)。
     */
    private val VERBS: Map<String, Set<String>?> =
        mapOf(
            "input" to setOf("tap", "swipe", "draganddrop", "text", "keyevent", "roll", "press", "motionevent"),
            "am" to setOf("start", "startservice", "broadcast", "force-stop", "kill"),
            "pm" to setOf("list", "path", "dump", "enable", "disable", "grant", "revoke", "clear"),
            "settings" to setOf("get", "put", "delete", "list"),
            "svc" to setOf("power", "wifi", "data", "bluetooth"),
            "wm" to setOf("size", "density"),
            "dumpsys" to null,
            "uiautomator" to setOf("dump"),
            "screencap" to null,
            "screendump" to null,
            "content" to setOf("query", "insert", "update", "delete", "call", "read"),
            "cmd" to setOf("statusbar", "notification", "activity", "package", "wifi", "power", "user", "media"),
            "getprop" to null,
            "date" to null,
        )

    /** 供描述/文档使用的动词清单。 */
    val allowedVerbs: List<String> get() = VERBS.keys.sorted()

    /** 校验一条命令。合法返回 [Check.Valid],否则带可读原因。 */
    fun validate(command: String): Check {
        val cmd = command.trim()
        if (cmd.isEmpty()) return Check.Invalid("The command is empty")
        cmd.forEach { ch ->
            if (ch in FORBIDDEN_CHARS) {
                return Check.Invalid("Contains a disallowed character '$ch' (pipes/redirection/multi-command joining are not supported; please split into multiple calls)")
            }
        }
        val tokens = cmd.split(Regex("\\s+"))
        val verb = tokens.first()
        // 注意:单靠 VERBS[verb] 无法区分"键不存在"与"值为 null(自由参数动词)",
        // 必须先用 containsKey 判定,否则 screencap/getprop 等会被误拒。
        if (verb !in VERBS) {
            return Check.Invalid("Command not in the whitelist: $verb (allowed: ${allowedVerbs.joinToString(" ")})")
        }
        val subVerbs = VERBS[verb]
        if (verb == "input" && subVerbs != null) {
            return validateInput(tokens, subVerbs)
        }
        if (subVerbs != null) {
            if (tokens.size < 2) return Check.Invalid("$verb is missing a subcommand (allowed: ${subVerbs.joinToString("/")})")
            if (tokens[1] !in subVerbs) {
                return Check.Invalid("$verb does not support the subcommand: ${tokens[1]} (allowed: ${subVerbs.joinToString("/")})")
            }
        }
        return Check.Valid
    }

    /**
     * v2.2.1 虚拟屏:`input` 的专用校验 —— 允许单个 `-d/--display <数字>` 前缀,
     * 其后必须跟白名单子命令;除此之外的旗标一律拒绝。
     */
    private fun validateInput(tokens: List<String>, subVerbs: Set<String>): Check {
        var index = 1
        var displaySpecified = false
        while (index < tokens.size && tokens[index].startsWith("-")) {
            val flag = tokens[index]
            if (flag != "-d" && flag != "--display") {
                return Check.Invalid("input does not support the argument $flag (only -d/--display <displayId> is allowed)")
            }
            if (displaySpecified) return Check.Invalid("input specifies -d/--display more than once")
            val value = tokens.getOrNull(index + 1)
            if (value == null || value.isEmpty() || !value.all(Char::isDigit)) {
                return Check.Invalid("input -d needs a numeric displayId")
            }
            displaySpecified = true
            index += 2
        }
        if (index >= tokens.size) {
            return Check.Invalid("input is missing a subcommand (allowed: ${subVerbs.joinToString("/")})")
        }
        if (tokens[index] !in subVerbs) {
            return Check.Invalid("input does not support the subcommand: ${tokens[index]} (allowed: ${subVerbs.joinToString("/")})")
        }
        return Check.Valid
    }
}
