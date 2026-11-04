package io.zer0.muse.automation.agent

/**
 * v2.2.1 GUI Agent 环:动作协议解析(纯逻辑,可单测)。
 *
 * 视觉模型输出自由文本,协议取其中**最后一个** `do(...)` 或 `finish(...)` 表达式
 * (模型常先给推理再给结论)。参数形如 key=value,值支持单/双引号字符串(可含逗号)。
 */
object UiAgentProtocol {

    sealed class Action {
        data class Tap(val x: Int, val y: Int, val label: String? = null) : Action()
        data class TapText(val text: String, val exact: Boolean, val maxSwipes: Int, val verifyText: String?) : Action()
        data class TapId(val viewId: String, val maxSwipes: Int, val verifyText: String?) : Action()
        data class Swipe(
            val x1: Int,
            val y1: Int,
            val x2: Int,
            val y2: Int,
            val durationMs: Long,
        ) : Action()

        data class TextInput(val text: String) : Action()
        data class Key(val key: String) : Action()
        data class Launch(val packageName: String) : Action()
        data class Wait(val ms: Long) : Action()

        /** completed=null means the model did not prove whether the task succeeded. */
        data class Finish(val result: String, val completed: Boolean? = null) : Action()
    }

    private val EXPRESSION_START = Regex("""(?i)\b(do|finish)\s*\(""")

    private data class Expression(val kind: String, val body: String, val start: Int, val endExclusive: Int)

    /** 解析模型输出;无有效动作返回 null。表达式按引号/转义感知括号,支持参数中出现括号。 */
    fun parse(raw: String): Action? {
        val expressions = findExpressions(raw)
        val last = expressions.lastOrNull() ?: return null
        val args = parseArgs(last.body)
        return when (last.kind) {
            "finish" -> parseFinish(args)
            else -> parseDo(args)
        }
    }

    /** 找到最后一个完整的 do/finish 表达式;扫描完整表达式时会跳过引号内的括号与伪动作。 */
    private fun findExpressions(raw: String): List<Expression> {
        val expressions = mutableListOf<Expression>()
        var cursor = 0
        while (cursor < raw.length) {
            val start = EXPRESSION_START.find(raw, cursor) ?: break
            val kind = start.groupValues[1].lowercase()
            val open = raw.indexOf('(', start.range.first)
            val expression = scanExpression(raw, kind, start.range.first, open)
            if (expression == null) {
                cursor = start.range.first + 1
            } else {
                expressions += expression
                cursor = expression.endExclusive
            }
        }
        return expressions
    }

    private fun scanExpression(raw: String, kind: String, start: Int, open: Int): Expression? {
        var depth = 1
        var quote: Char? = null
        var escaped = false
        for (index in open + 1 until raw.length) {
            val char = raw[index]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    quote = null
                }
                continue
            }
            when (char) {
                '"', '\'' -> quote = char
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return Expression(kind, raw.substring(open + 1, index), start, index + 1)
                }
            }
        }
        return null
    }

    /** 动作的人类可读描述(用于动作史回填)。 */
    fun describe(action: Action): String = when (action) {
        is Action.Tap -> "Tap (${action.x},${action.y})"
        is Action.TapText -> "Semantic tap (bounded scrolling, ${action.maxSwipes} times)"
        is Action.TapId -> "Control-ID tap (bounded scrolling, ${action.maxSwipes} times)"
        is Action.Swipe -> "Swipe (${action.x1},${action.y1})→(${action.x2},${action.y2})"
        is Action.TextInput -> "Input text (${action.text.length} characters)"
        is Action.Key -> "Key press ${action.key}"
        is Action.Launch -> "Launch ${action.packageName}"
        is Action.Wait -> "Wait ${action.ms}ms"
        is Action.Finish -> "Finish"
    }

    private fun parseFinish(args: Map<String, String>): Action.Finish {
        val result = args["result"] ?: args["text"] ?: args["message"] ?: ""
        val status = args["status"]?.trim()?.lowercase()
        val completed = args["success"]?.trim()?.lowercase()?.toBooleanStrictOrNull()
            ?: args["completed"]?.trim()?.lowercase()?.toBooleanStrictOrNull()
            ?: when (status) {
                "success", "completed", "complete", "done", "ok" -> true
                "blocked", "failed", "failure", "incomplete", "error" -> false
                else -> null
            }
        return Action.Finish(result, completed)
    }

    private fun parseDo(args: Map<String, String>): Action? {
        val type = (args["action"] ?: args["type"] ?: args["name"] ?: return null).lowercase()
        return when (type) {
            "tap_id", "click_id", "tap_view_id", "tap_resource" -> {
                val viewId = args["view_id"] ?: args["id"] ?: args["resource_id"] ?: return null
                val maxSwipes = args["max_swipes"]?.toIntOrNull()?.coerceIn(0, 5) ?: 0
                Action.TapId(viewId, maxSwipes, args["verify_text"]?.takeIf { it.isNotBlank() })
            }

            "tap_text", "click_text", "tap_by_text" -> {
                val text = args["text"]?.takeIf { it.isNotBlank() } ?: return null
                val exact = args["exact"]?.equals("true", ignoreCase = true) == true
                val maxSwipes = args["max_swipes"]?.toIntOrNull()?.coerceIn(0, 5) ?: 0
                Action.TapText(text, exact, maxSwipes, args["verify_text"]?.takeIf { it.isNotBlank() })
            }

            "tap", "click" -> {
                val x = args["x"]?.toIntOrNull() ?: return null
                val y = args["y"]?.toIntOrNull() ?: return null
                val label = args["label"] ?: args["target"]
                Action.Tap(x, y, label?.takeIf { it.isNotBlank() })
            }

            "swipe" -> {
                val x1 = args["x1"]?.toIntOrNull() ?: return null
                val y1 = args["y1"]?.toIntOrNull() ?: return null
                val x2 = args["x2"]?.toIntOrNull() ?: return null
                val y2 = args["y2"]?.toIntOrNull() ?: return null
                val duration = (args["duration"] ?: args["duration_ms"])
                    ?.toLongOrNull()?.coerceIn(50L, 5_000L) ?: 400L
                Action.Swipe(x1, y1, x2, y2, duration)
            }

            "text", "input", "type" ->
                Action.TextInput(args["text"] ?: args["content"] ?: return null)

            "key", "keyevent" ->
                Action.Key((args["key"] ?: args["name"] ?: return null).lowercase())

            "launch", "open_app" ->
                Action.Launch(args["package"] ?: args["pkg"] ?: args["app"] ?: return null)

            "wait", "sleep" -> {
                val ms = (args["ms"] ?: args["duration"] ?: "800")
                    .toLongOrNull()?.coerceIn(50L, 10_000L) ?: 800L
                Action.Wait(ms)
            }

            else -> null
        }
    }

    /** 提取 key=value; 引号值支持逗号、括号和转义引号,无引号值到逗号/空白为止。 */
    private fun parseArgs(body: String): Map<String, String> {
        val args = linkedMapOf<String, String>()
        var index = 0
        while (index < body.length) {
            while (index < body.length && (body[index].isWhitespace() || body[index] == ',')) index++
            if (index >= body.length) break

            val keyStart = index
            if (!(body[index].isLetter() || body[index] == '_')) {
                index = skipToNextArgument(body, index)
                continue
            }
            index++
            while (index < body.length && (body[index].isLetterOrDigit() || body[index] == '_')) index++
            val key = body.substring(keyStart, index).lowercase()
            while (index < body.length && body[index].isWhitespace()) index++
            if (index >= body.length || body[index] != '=') {
                index = skipToNextArgument(body, index)
                continue
            }
            index++
            while (index < body.length && body[index].isWhitespace()) index++
            if (index >= body.length) break

            val quote = body[index].takeIf { it == '"' || it == '\'' }
            val value = StringBuilder()
            if (quote != null) {
                index++
                var closed = false
                while (index < body.length) {
                    val char = body[index++]
                    when {
                        char == quote -> {
                            closed = true
                            break
                        }
                        char == '\\' && index < body.length -> {
                            val escaped = body[index++]
                            if (escaped == quote || escaped == '\\') {
                                value.append(escaped)
                            } else {
                                value.append('\\').append(escaped)
                            }
                        }
                        else -> value.append(char)
                    }
                }
                if (!closed) break
            } else {
                while (index < body.length && !body[index].isWhitespace() && body[index] != ',') {
                    value.append(body[index++])
                }
            }
            args[key] = value.toString()
            index = skipToNextArgument(body, index)
        }
        return args
    }

    private fun skipToNextArgument(body: String, from: Int): Int {
        var index = from
        var quote: Char? = null
        var escaped = false
        while (index < body.length) {
            val char = body[index]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) quote = null
            } else if (char == '"' || char == '\'') {
                quote = char
            } else if (char == ',') {
                return index + 1
            }
            index++
        }
        return index
    }
}
