package io.zer0.muse.tools

import io.zer0.muse.data.experience.ExperienceEntity
import io.zer0.muse.data.experience.ExperienceRepository
import java.util.UUID

/**
 * record_experience 工具。
 *
 * 将学到的经验记录到经验库中。
 */
object RecordExperienceTool {

    fun toolDef() = ToolRegistry.ToolDef(
        name = "record_experience",
        description = "Record an experience into the experience library. Applicable situations: the user points out your mistake, shows dissatisfaction, or repeatedly emphasises something;" +
            "you found an effective approach after trying several methods; the user says 'do it this way from now on' or 'don't do it that way'." +
            "One sentence per entry; be specific.",
        parameters = mapOf(
            "category" to "Required. Category, 2-4 words, e.g. 'tool usage', 'search tips', 'response style'.",
            "content" to "Required. The experience content, concise and direct, one sentence.",
        ),
        required = setOf("category", "content"),
        category = "built-in",
        riskLevel = ToolRiskLevel.NORMAL,
    )

    suspend fun execute(args: Map<String, String>, repo: ExperienceRepository): String {
        val category = args["category"]?.trim()?.replace(Regex("^#+\\s*"), "")
            ?: return "Error: category parameter is required."
        val content = args["content"]?.trim()
            ?: return "Error: content parameter is required."
        if (category.isEmpty() || content.isEmpty()) {
            return "Error: category and content cannot be empty."
        }
        // 记忆工具本身已经运行在挂起工具链中,直接调用 Room suspend DAO。
        // 嵌套 runBlocking 会阻塞执行线程,在工具结果回填前制造“已写入但一直执行中”的假象。
        val existing = repo.getAll()
        if (existing.any { it.content == content && it.category.equals(category, ignoreCase = true) }) {
            return "Duplicate: this experience is already recorded."
        }
        val title = content.take(50) + if (content.length > 50) "..." else ""
        val entity = ExperienceEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            content = content,
            category = category,
            source = "llm",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        repo.upsert(entity)
        return "Experience recorded under '$category': $content"
    }
}
