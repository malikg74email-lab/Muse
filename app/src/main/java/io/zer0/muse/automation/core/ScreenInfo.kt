package io.zer0.muse.automation.core

/**
 * 屏幕信息快照。
 *
 * 由无障碍控件树 + dumpsys 摘要合成,供 AI 判断"屏幕上有什么、该点哪里"。
 */
data class ScreenInfo(
    /** 当前前台包名。 */
    val packageName: String? = null,
    /** 当前 Activity 类名(若可取)。 */
    val activityName: String? = null,
    /** 可交互/可读的控件节点列表。 */
    val nodes: List<UiNode> = emptyList(),
    /** 截屏的宽度(像素),用于把归一化坐标换算成绝对坐标。 */
    val screenWidth: Int = 0,
    /** 截屏的高度(像素)。 */
    val screenHeight: Int = 0,
    /** 数据来源标记: accessibility / shell / root。 */
    val source: String = "",
) {
    /**
     * 在控件树中选择最有可能代表用户目标的节点。
     * 评分优先级：可用性 → 精确匹配 → 前缀/包含匹配 → 可点击性 → 文本字段。
     * 这样视觉 Agent 和语义工作流不会因树遍历顺序变化而随机点击同名装饰文本。
     */
    fun findBestTextNode(query: String, exact: Boolean = false): UiNode? {
        val normalized = query.trim()
        if (normalized.isEmpty()) return null
        return nodes.asSequence()
            .filter { it.isEnabled }
            .mapNotNull { node ->
                val text = node.text?.trim()?.takeIf { it.isNotEmpty() }
                val description = node.contentDescription?.trim()?.takeIf { it.isNotEmpty() }
                val label = text ?: description ?: return@mapNotNull null
                val normalizedLabel = label.trim()
                val matches = if (exact) {
                    normalizedLabel == normalized
                } else {
                    normalizedLabel.equals(normalized, ignoreCase = true) ||
                        normalizedLabel.startsWith(normalized, ignoreCase = true) ||
                        normalizedLabel.contains(normalized, ignoreCase = true)
                }
                if (!matches) return@mapNotNull null
                val matchScore = when {
                    normalizedLabel == normalized -> 1_000
                    normalizedLabel.equals(normalized, ignoreCase = true) -> 900
                    normalizedLabel.startsWith(normalized, ignoreCase = true) -> 700
                    else -> 500
                }
                val fieldScore = if (text != null) 30 else 0
                val interactionScore = when {
                    node.isClickable -> 120
                    node.isEditable -> 60
                    else -> 0
                }
                node to (matchScore + interactionScore + fieldScore)
            }
            .sortedByDescending { it.second }
            .firstOrNull()
            ?.first
    }

    /** Choose a stable resource-id target, accepting either the full id or the suffix after '/'. */
    fun findBestViewIdNode(viewId: String): UiNode? {
        val query = viewId.trim().removePrefix("@").removePrefix("id/")
        if (query.isEmpty()) return null
        return nodes.asSequence()
            .filter { it.isEnabled }
            .mapNotNull { node ->
                val candidate = node.viewIdResourceName?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val suffix = candidate.substringAfterLast('/')
                if (candidate != query && suffix != query && candidate != "id/$query") return@mapNotNull null
                val score = (if (candidate == query) 1_000 else 900) +
                    (if (node.isClickable) 120 else if (node.isEditable) 60 else 0)
                node to score
            }
            .sortedByDescending { it.second }
            .firstOrNull()
            ?.first
    }

    /** 生成给 AI 阅读的紧凑文本摘要。 */
    fun toSummary(maxNodes: Int = 40): String = buildString {
        appendLine("Current app: ${packageName ?: "Unknown"}")
        if (!activityName.isNullOrBlank()) appendLine("Interface: $activityName")
        appendLine("Resolution: ${screenWidth}x$screenHeight")
        appendLine("Control count: ${nodes.size}")
        appendLine("---")
        nodes.take(maxNodes).forEachIndexed { i, n ->
            appendLine("[$i] ${n.toShortString()}")
        }
        if (nodes.size > maxNodes) appendLine("... ${nodes.size - maxNodes} more nodes")
    }
}

/**
 * UI 控件节点(跨层统一表示,屏蔽无障碍/dumpsys 差异)。
 */
data class UiNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val viewIdResourceName: String? = null,
    /** 控件左上角 x(绝对像素)。 */
    val boundsLeft: Int = 0,
    val boundsTop: Int = 0,
    /** 控件右下角 x。 */
    val boundsRight: Int = 0,
    val boundsBottom: Int = 0,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isChecked: Boolean? = null,
    val isEnabled: Boolean = true,
) {
    /** 中心 x,供 AI 返回点击目标时换算。 */
    val centerX: Int get() = (boundsLeft + boundsRight) / 2
    val centerY: Int get() = (boundsTop + boundsBottom) / 2

    fun toShortString(): String {
        val label = text?.take(30)
            ?: contentDescription?.take(30)
            ?: viewIdResourceName?.substringAfterLast('/')
            ?: className?.substringAfterLast('.')
            ?: "Unnamed"
        val tags = buildList {
            if (isClickable) add("Clickable")
            if (isEditable) add("Editable")
            if (isScrollable) add("Scrollable")
            if (isChecked == true) add("Checked")
            if (!isEnabled) add("Disabled")
        }
        val tagStr = if (tags.isEmpty()) "" else " [${tags.joinToString(",")}]"
        val bounds = "($centerX,$centerY)"
        return "$label$tagStr $bounds"
    }
}
