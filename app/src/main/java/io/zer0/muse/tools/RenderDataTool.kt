package io.zer0.muse.tools

import io.zer0.common.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Structured chart/table renderer for tool results.
 *
 * Charts are emitted as the existing ```card format and tables as bounded
 * Markdown. The chat tool-result renderer owns the visual presentation.
 */
object RenderDataTool {

    const val NAME = "render_data"

    // Keep only structural guardrails; the orchestrator can spill large results to a complete
    // file reference, so this renderer must not reject or truncate otherwise valid user data.
    private const val MAX_COLUMNS = 64
    private const val MAX_ROWS = 2_000

    private val CHART_TYPES = setOf("bar", "line", "donut")

    fun toolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME,
        description =
        "Display structured data directly in the chat. For type=bar/line/donut, data must be " +
            "{\"labels\":[...],\"values\":[...]}; for type=table, data must be " +
            "{\"columns\":[...],\"rows\":[[...]]}. The data is rendered locally only and does not access the network.",
        parameters = mapOf(
            "type" to "Required: bar | line | donut | table",
            "title" to "Required, chart or table title",
            "data" to "Required JSON data: charts use labels/values, tables use columns/rows",
        ),
        required = setOf("type", "title", "data"),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
        // data 是嵌套对象（图表 labels/values、表格 columns/rows）。
        // 旧实现未声明类型 → schema 默认标成 string，模型便把它按转义 JSON 字符串传递，
        // 叠加参数提取的转义还原缺陷会解析失败。这里显式声明 object。
        parameterTypes = mapOf("data" to "object"),
    )

    fun execute(args: Map<String, String>): String {
        val type = args["type"]?.trim()?.lowercase()
        val title = args["title"]?.let(::cleanText)
        val rawData = args["data"]?.trim()
        val data = rawData?.let { runCatching { AppJson.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val rendered =
            when {
                type == null -> error("Missing type")
                title == null -> error("Missing title")
                title.isBlank() -> error("title must not be empty")
                rawData == null -> error("Missing data")
                data == null -> error("data must be a JSON object")
                type in CHART_TYPES -> renderChart(type, title, data)
                type == "table" -> renderTable(title, data)
                else -> error("type only supports bar, line, donut or table")
            }
        return rendered
    }

    private fun renderChart(type: String, title: String, data: JsonObject): String {
        val labels = stringArray(data["labels"])
        val values = numberArray(data["values"])
        // 校验分支各自产出错误文案；只有全部通过才产出卡片 JSON。两侧都是 String，
        // 因此不需要额外的类型判断，也不会误把错误文案包进 ```card 代码块。
        return when {
            labels == null -> error("Chart labels must be a string array")
            values == null -> error("Chart values must be a finite number array")
            labels.isEmpty() || values.isEmpty() -> error("Chart data must not be empty")
            labels.size != values.size -> error("labels and values must have the same length")
            labels.size > MAX_ROWS -> error("Chart data supports at most $MAX_ROWS points")
            else -> {
                val cardJson =
                    buildJsonObject {
                        put("type", JsonPrimitive(type))
                        put("title", JsonPrimitive(title))
                        put("labels", buildJsonArray { labels.forEach { add(JsonPrimitive(it)) } })
                        put("values", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                    }
                "```card\n$cardJson\n```"
            }
        }
    }

    private fun renderTable(title: String, data: JsonObject): String {
        val columns = stringArray(data["columns"])
        val rows = data["rows"] as? JsonArray
        val shapeError = validateTableShape(columns, rows)
        val normalizedRows = if (shapeError == null && columns != null && rows != null) {
            rows.map { stringArray(it) }
        } else {
            null
        }
        val rowsError = if (columns != null && normalizedRows != null) {
            normalizedRows.withIndex()
                .firstOrNull { (_, cells) -> cells == null || cells.size != columns.size }
                ?.let { (index, cells) ->
                    if (cells == null) {
                        error("Row ${index + 1} must be a string/number array")
                    } else {
                        error("Row ${index + 1} has a column count inconsistent with columns")
                    }
                }
        } else {
            null
        }
        return when {
            shapeError != null -> shapeError
            rowsError != null -> rowsError
            columns != null && normalizedRows != null -> renderTableBody(title, columns, normalizedRows)
            else -> error("Table data is incomplete")
        }
    }

    /** 表格"形状"层面的校验（列本身、行数），返回 null 表示通过。 */
    private fun validateTableShape(columns: List<String>?, rows: JsonArray?): String? = when {
        columns == null -> error("Table columns must be a string array")
        columns.isEmpty() -> error("A table needs at least one column")
        columns.size > MAX_COLUMNS -> error("A table supports at most $MAX_COLUMNS columns")
        rows == null -> error("Table rows must be a two-dimensional array")
        rows.size > MAX_ROWS -> error("A table supports at most $MAX_ROWS rows")
        else -> null
    }

    private fun renderTableBody(title: String, columns: List<String>, rows: List<List<String>?>): String = buildString {
        appendLine("### $title")
        appendLine("| ${columns.joinToString(" | ")} |")
        appendLine("| ${columns.joinToString(" | ") { "---" }} |")
        rows.forEach { row ->
            appendLine("| ${row.orEmpty().joinToString(" | ")} |")
        }
    }.trimEnd()

    private fun stringArray(element: kotlinx.serialization.json.JsonElement?): List<String>? {
        val array = element as? JsonArray ?: return null
        val cleaned = array.map { primitive ->
            (primitive as? JsonPrimitive)?.contentOrNull()?.let(::cleanText)?.takeIf { it.isNotBlank() }
        }
        return cleaned.takeIf { values -> values.all { it != null } }?.mapNotNull { it }
    }

    private fun numberArray(element: kotlinx.serialization.json.JsonElement?): List<Double>? {
        val array = element as? JsonArray ?: return null
        val numbers = array.map { primitive ->
            (primitive as? JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
        return numbers.takeIf { values -> values.all { it != null } }?.mapNotNull { it }
    }

    private fun cleanText(value: String): String = value.replace('|', ' ').replace('\r', ' ').replace('\n', ' ').trim()

    private fun error(message: String): String = "Error: $message"

    private fun JsonPrimitive.contentOrNull(): String? = content.takeIf { it.isNotBlank() }
}
