package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * P1-3b 拆域：核心基础工具注册器。
 *
 * 从 ToolRegistry.kt 抽出的 get_current_time / calculator / echo，
 * 只依赖 Context 字符串资源，不持有 ToolRegistry 内部状态。
 */
class CoreToolsRegistrar(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_current_time",
                description =
                "Get the current time. You can specify a timezone (e.g. Asia/Shanghai, UTC); daylight-saving status is annotated automatically." +
                    "The system prompt already includes this round's time and the time anchors of the user's two most recent messages; for ordinary current-time or message-interval questions, quote them directly;" +
                    "call only when the user asks for another timezone or explicitly asks to fetch the time again.",
                parameters = mapOf(
                    "timezone" to "Optional, IANA timezone (e.g. Asia/Shanghai, UTC), default Asia/Shanghai",
                    "format" to "Optional, custom format (SimpleDateFormat syntax), default yyyy-MM-dd HH:mm:ss z",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val tzId = args["timezone"]?.takeIf { it.isNotBlank() } ?: "Asia/Shanghai"
            val tz = if (TimeZone.getAvailableIDs().contains(tzId)) {
                TimeZone.getTimeZone(tzId)
            } else {
                return@register context.getString(R.string.tool_unknown_timezone, tzId)
            }
            val pattern = args["format"]?.takeIf { it.isNotBlank() } ?: "yyyy-MM-dd HH:mm:ss z"
            val fmt = resultOf { SimpleDateFormat(pattern, Locale.getDefault()) }
                .onError { msg, _ -> Logger.w("CoreTools", "Invalid time format: $msg (pattern=$pattern)") }
                .getOrNull() ?: SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
            fmt.timeZone = tz
            val dstLabel = if (tz.inDaylightTime(Date())) {
                context.getString(R.string.tool_dst)
            } else {
                context.getString(R.string.tool_non_dst)
            }
            context.getString(R.string.tool_current_time, tzId, fmt.format(Date()), dstLabel)
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "calculator",
                description = "A simple calculator supporting addition, subtraction, multiplication, division and brackets. Returns 'expression = result' text. Only the four arithmetic operations and brackets; no powers/modulo/unit conversion.",
                parameters = mapOf("expression" to "Required, mathematical expression, e.g. 1+2*3"),
                required = setOf("expression"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val expr = args["expression"]?.takeIf { it.isNotBlank() }
                ?: return@register context.getString(R.string.tool_missing_param_expression)
            val result = try {
                Calculator.eval(expr)
            } catch (e: ArithmeticException) {
                return@register context.getString(R.string.tool_calc_error, e.message ?: "")
            } catch (e: IllegalArgumentException) {
                return@register context.getString(R.string.tool_expr_invalid, e.message ?: "")
            }
            if (result.isInfinite() || result.isNaN()) {
                return@register context.getString(R.string.tool_calc_error_divzero, result.toString())
            }
            "$expr = $result"
        }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "echo",
                description = "Echo the input content (for testing).",
                parameters = mapOf("text" to "Required, the text to echo"),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            args["text"] ?: ""
        }
    }
}

/**
 * 简易计算器:支持 + - * / ( ) 和空格。
 * 不引入第三方表达式引擎,用递归下降解析。
 * 不用 javax.script(Nashorn 在 JDK 15+ 移除,Android 无)。
 */
private object Calculator {
    fun eval(input: String): Double {
        val chars = input.filterNot { it.isWhitespace() }.toCharArray()
        val pos = intArrayOf(0)
        val result = parseExpr(chars, pos)
        if (pos[0] != chars.size) throw IllegalArgumentException("cannot parse: ${input.substring(pos[0])}")
        return result
    }

    private fun parseExpr(chars: CharArray, pos: IntArray): Double {
        var v = parseTerm(chars, pos)
        while (pos[0] < chars.size) {
            when (chars[pos[0]]) {
                '+' -> {
                    pos[0]++
                    v += parseTerm(chars, pos)
                }
                '-' -> {
                    pos[0]++
                    v -= parseTerm(chars, pos)
                }
                else -> break
            }
        }
        return v
    }

    private fun parseTerm(chars: CharArray, pos: IntArray): Double {
        var v = parseFactor(chars, pos)
        while (pos[0] < chars.size) {
            when (chars[pos[0]]) {
                '*' -> {
                    pos[0]++
                    v *= parseFactor(chars, pos)
                }
                '/' -> {
                    pos[0]++
                    v /= parseFactor(chars, pos)
                }
                else -> break
            }
        }
        return v
    }

    private fun parseFactor(chars: CharArray, pos: IntArray): Double {
        if (pos[0] >= chars.size) throw IllegalArgumentException("unexpected end of expression")
        if (chars[pos[0]] == '-') {
            pos[0]++
            return -parseFactor(chars, pos)
        }
        if (chars[pos[0]] == '(') {
            pos[0]++
            val v = parseExpr(chars, pos)
            if (pos[0] >= chars.size || chars[pos[0]] != ')') throw IllegalArgumentException("missing closing parenthesis")
            pos[0]++
            return v
        }
        val start = pos[0]
        while (pos[0] < chars.size && (chars[pos[0]].isDigit() || chars[pos[0]] == '.')) pos[0]++
        if (start == pos[0]) throw IllegalArgumentException("expected number, got: ${chars[pos[0]]}")
        val len = pos[0] - start
        return String(chars, start, len).toDouble()
    }
}
