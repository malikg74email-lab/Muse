package io.zer0.memory.ai

/**
 * v1.0.52 P2-3: 记忆 AI 提取提示词构建器。
 *
 * 用于 [MemoryAutoSaveScheduler.extractEntities] —— 从对话历史中提取结构化记忆:
 *  - 实体(原子事实)+ 实体间关系(知识图谱边)
 *  - 对已有事实的更新 + 相似记忆合并
 *  - 用户画像 Markdown 更新
 *
 * 输出严格 JSON 对象(符合 [ParsedAnalysis] 结构),无 markdown 围栏。
 *
 * 与 [io.zer0.memory.prompt.FactExtractionPrompt] 的区别:
 *  - FactExtractionPrompt: 从摘要 diff 提取原子事实(无关系/合并/画像)
 *  - MemoryExtractPrompt: 从完整对话提取结构化记忆(含关系/合并/画像),功能更全
 * 两者可共存:FactExtraction 用于 daily deep pipeline,MemoryExtract 用于实时 autoSave
 */
object MemoryExtractPrompt {

    // D3: 强调与边界准则补充(明确"什么值得记"的判据) → v3
    const val TEMPLATE_VERSION = "memory-extract.v3"
    const val CACHE_GROUP = "memory.extract_analysis"

    /**
     * 构建 system prompt。
     *
     * @param locale 语言(zh-CN / en-US ...)
     * @param existingFactsPreview 已有事实预览(可选,用于去重/合并/更新参考)
     *        格式: "- {title}: {content}" 每行一条,最多 20 条
     */
    fun buildSystemPrompt(locale: String = "zh-CN", existingFactsPreview: String? = null): String {
        val isZh = locale.startsWith("zh")

        val existingSection = if (!existingFactsPreview.isNullOrBlank()) {
            if (isZh) {
                """
## Existing Memories (for dedup/merge/update reference)

$existingFactsPreview

Check for duplicates/conflicts with existing memories when extracting:
 - If a new entity semantically duplicates an existing memory, put it in mergedEntities
 - If a new entity corrects/updates an existing memory, put it in updatedEntities
 - If no duplicate with existing, put it in extractedEntities
                """.trimIndent()
            } else {
                """
## Existing Memories (for dedup/merge/update reference)

$existingFactsPreview

Check for duplicates/conflicts with existing memories when extracting:
 - If a new entity semantically duplicates an existing memory, put it in mergedEntities
 - If a new entity corrects/updates an existing memory, put it in updatedEntities
 - If no duplicate with existing, put it in extractedEntities
                """.trimIndent()
            }
        } else {
            ""
        }

        if (isZh) {
            return """
You are a memory analyst. Analyze the conversation between the user and the assistant, and extract structured memories.

$existingSection

## Extraction Rules

1. mainProblem: A one-sentence summary of the main problem in this conversation (what the user wanted to solve), optional.

2. extractedEntities: Newly extracted atomic facts (one fact per entry).
   - title: Short title (≤20 chars, for display and matching)
   - content: Fact body (preserve original wording, do not add subject)
   - speaker: Fact speaker, one of USER / ASSISTANT / OTHER; user facts must be USER
   - credibility: 0.0~1.0, 1.0 for explicit user statements, 0.5~0.8 for inference
   - importance: 0.0~1.0, <0.4 normal, 0.4~0.7 important, >0.7 critical (medical/financial/safety);
      content the user explicitly emphasizes, states seriously, or expresses as boundaries/taboos ("don't...", "I don't like being...") should be at least 0.4
   - folderPath: Category (preference/identity/event/relationship/goal/medical/other)
   - tags: 2-5 distinctive keywords
   - entityKey: Entity normalization key. When the fact's subject is a specific person's name/title
     (e.g. "Mr. Zhang"), set it to the canonical name (e.g. "Zhang San"); the same entity MUST use
     exactly the same entityKey across entries. Use null otherwise.

3. links: Relationships between entities (knowledge graph edges).
   - sourceTitle / targetTitle must match an entity's title
   - linkType: causes / explains / part_of / related_to / contradicts
   - weight: 0.0~1.0 relationship strength

4. updatedEntities: Updates to existing memories (matched by matchTitle, fuzzy match allowed).
   - matchTitle: Title of existing memory (partial match allowed)
   - newContent: Replacement body
   - newImportance / newFolderPath / newTags: Optional updates (null to skip)

5. mergedEntities: Similar memory merges (deduplication).
   - sourceTitles: List of existing/new memory titles to merge
   - mergedTitle / mergedContent: New title and body after merge

6. profileMarkdown: User profile Markdown update (optional, overwrites).
   Includes identity/personality/interests/long-term goals, formatted like:
   ## Identity
   - Name: John
   ## Interests
   - Memory systems, AI Agents

7. Do NOT extract:
   - Do not turn the assistant's self-statements, promises, preferences, or temporary plans into user facts; mark them ASSISTANT so they are rejected from the user fact store
   - Workflow preferences, tool preferences, engineering rules, execution details
   - Assistant's inner thoughts, temporary debugging info
   - One-off task details (e.g. "modified line Y of file X")
   - Demo content from the examples below (examples illustrate JSON format only; never copy them; every entry must come from this conversation)

8. If nothing in the conversation is worth remembering, return an empty structure (all arrays empty, mainProblem null).

## Output Format

Strict JSON object, no markdown code fences:
{
  "mainProblem": {"title": "...", "content": "...", "credibility": 0.8, "importance": 0.5, "folderPath": "event", "tags": ["..."]},
  "extractedEntities": [
    {"title": "allergic to penicillin", "content": "allergic to penicillin", "speaker": "USER", "credibility": 1.0, "importance": 0.9, "folderPath": "medical", "tags": ["medical","allergy"], "entityKey": null},
    {"title": "Mr. Zhang prefers American coffee", "content": "Mr. Zhang prefers American coffee", "credibility": 1.0, "importance": 0.5, "folderPath": "preference", "tags": ["coffee"], "entityKey": "Zhang San"}
  ],
  "links": [
    {"sourceTitle": "allergic to penicillin", "targetTitle": "treated with amoxicillin", "linkType": "contradicts", "weight": 0.9}
  ],
  "updatedEntities": [],
  "mergedEntities": [],
  "profileMarkdown": null
}
        """.trimIndent()
        }

        return """
You are a memory analyst. Analyze the conversation between the user and the assistant, and extract structured memories.

$existingSection

## Extraction Rules

1. mainProblem: A one-sentence summary of the main problem in this conversation (what the user wanted to solve), optional.

2. extractedEntities: Newly extracted atomic facts (one fact per entry).
   - title: Short title (≤20 chars, for display and matching)
   - content: Fact body (preserve original wording, do not add subject)
   - speaker: Fact speaker, one of USER / ASSISTANT / OTHER; user facts must be USER
   - credibility: 0.0~1.0, 1.0 for explicit user statements, 0.5~0.8 for inference
   - importance: 0.0~1.0, <0.4 normal, 0.4~0.7 important, >0.7 critical (medical/financial/safety);
      content the user explicitly emphasizes, states seriously, or expresses as boundaries/taboos ("don't...", "I don't like being...") should be at least 0.4
   - folderPath: Category (preference/identity/event/relationship/goal/medical/other)
   - tags: 2-5 distinctive keywords
   - entityKey: Entity normalization key. When the fact's subject is a specific person's name/title
     (e.g. "Mr. Zhang"), set it to the canonical name (e.g. "Zhang San"); the same entity MUST use
     exactly the same entityKey across entries. Use null otherwise.

3. links: Relationships between entities (knowledge graph edges).
   - sourceTitle / targetTitle must match an entity's title
   - linkType: causes / explains / part_of / related_to / contradicts
   - weight: 0.0~1.0 relationship strength

4. updatedEntities: Updates to existing memories (matched by matchTitle, fuzzy match allowed).
   - matchTitle: Title of existing memory (partial match allowed)
   - newContent: Replacement body
   - newImportance / newFolderPath / newTags: Optional updates (null to skip)

5. mergedEntities: Similar memory merges (deduplication).
   - sourceTitles: List of existing/new memory titles to merge
   - mergedTitle / mergedContent: New title and body after merge

6. profileMarkdown: User profile Markdown update (optional, overwrites).
   Includes identity/personality/interests/long-term goals, formatted like:
   ## Identity
   - Name: John
   ## Interests
   - Memory systems, AI Agents

7. Do NOT extract:
   - Do not turn the assistant's self-statements, promises, preferences, or temporary plans into user facts; mark them ASSISTANT so they are rejected from the user fact store
   - Workflow preferences, tool preferences, engineering rules, execution details
   - Assistant's inner thoughts, temporary debugging info
   - One-off task details (e.g. "modified line Y of file X")
   - Demo content from the examples below (examples illustrate JSON format only; never copy them; every entry must come from this conversation)

8. If nothing in the conversation is worth remembering, return an empty structure (all arrays empty, mainProblem null).

## Output Format

Strict JSON object, no markdown code fences:
{
  "mainProblem": {"title": "...", "content": "...", "credibility": 0.8, "importance": 0.5, "folderPath": "event", "tags": ["..."]},
  "extractedEntities": [
    {"title": "allergic to penicillin", "content": "allergic to penicillin", "speaker": "USER", "credibility": 1.0, "importance": 0.9, "folderPath": "medical", "tags": ["medical","allergy"], "entityKey": null},
    {"title": "Mr. Zhang prefers American coffee", "content": "Mr. Zhang prefers American coffee", "credibility": 1.0, "importance": 0.5, "folderPath": "preference", "tags": ["coffee"], "entityKey": "Zhang San"}
  ],
  "links": [
    {"sourceTitle": "allergic to penicillin", "targetTitle": "treated with amoxicillin", "linkType": "contradicts", "weight": 0.9}
  ],
  "updatedEntities": [],
  "mergedEntities": [],
  "profileMarkdown": null
}
        """.trimIndent()
    }

    /**
     * 构建 autoCategorize(自动分类未分类记忆)的 system prompt。
     *
     * 用于 [MemoryAutoSaveScheduler.autoCategorizeMemories] —— 批量为 folderPath 为空的
     * 记忆分配分类。
     *
     * @param locale 语言
     */
    fun buildCategorizeSystemPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (isZh) {
            return """
You are a memory categorizer. Assign a folderPath (category directory) to each uncategorized memory.

## Categories

folderPath values:
 - preference: Likes/dislikes (e.g. "likes americano", "hates cilantro")
 - identity: Identity/personality (e.g. "name is John", "introverted")
 - event: Events/plans (e.g. "report due Wednesday", "moving next week")
 - relationship: Relationships (e.g. "wife is Jane", "coworker Bob")
 - goal: Goals/dreams (e.g. "wants to learn Japanese", "plans to run for weight loss")
 - medical: Medical/health (e.g. "allergic to penicillin", "high blood pressure")
 - other: Other (cannot fit above categories)

## Output Format

Strict JSON array, each entry contains the original title and assigned folderPath:
[
  {"title": "...", "folderPath": "preference"},
  {"title": "...", "folderPath": "medical"}
]

No markdown code fences. If unable to categorize, assign "other".
        """.trimIndent()
        }
        return """
You are a memory categorizer. Assign a folderPath (category directory) to each uncategorized memory.

## Categories

folderPath values:
 - preference: Likes/dislikes (e.g. "likes americano", "hates cilantro")
 - identity: Identity/personality (e.g. "name is John", "introverted")
 - event: Events/plans (e.g. "report due Wednesday", "moving next week")
 - relationship: Relationships (e.g. "wife is Jane", "coworker Bob")
 - goal: Goals/dreams (e.g. "wants to learn Japanese", "plans to run for weight loss")
 - medical: Medical/health (e.g. "allergic to penicillin", "high blood pressure")
 - other: Other (cannot fit above categories)

## Output Format

Strict JSON array, each entry contains the original title and assigned folderPath:
[
  {"title": "...", "folderPath": "preference"},
  {"title": "...", "folderPath": "medical"}
]

No markdown code fences. If unable to categorize, assign "other".
        """.trimIndent()
    }
}
