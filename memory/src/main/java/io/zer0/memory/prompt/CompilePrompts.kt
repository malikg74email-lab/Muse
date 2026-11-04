package io.zer0.memory.prompt

/**
 * 编译提示词构建器。
 *
 * 提供四块独立编译（today / daily / week / longterm）与 editable facts 的 system prompt。
 * 输出统一为纯文本（无 markdown 标题），由 [CompiledMemoryState.normalizeLlmResult] 规范化。
 */
object CompilePrompts {

    /** compile today: 当天 sessions → today.md */
    fun buildTodayPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (!isZh) {
            return """
Condense today's conversation summaries into a short list of the user's current state and broad themes.

Rules:
- Group repeated exchanges about the same topic into one entry instead of a line-by-line log
- Use coarse time markers ("morning", "evening", or a rough HH:MM range), not minute-level precision
- The primary job of long-term memory is to maintain an accurate user model: who the user is, what they like, what matters to them, and what they are broadly focused on recently
- Work content may appear only at the broad-theme level: name the domain, project, or topic, not its internal details

May record:
- Identity, personality traits, aesthetics, interests, likes, and dislikes
- Broad themes currently occupying the user, such as "preparing to move", "learning to swim", or "writing a thesis draft"
- Changes in life, creative work, relationships, or long-term attention areas

Do NOT record:
- Execution steps, filenames, tools, commands, check order, collaboration preferences, or work details
- Methodology choices, tool preferences, format requirements, or terminology rules from individual tasks
- Specific subproblems, concrete solutions, concrete code changes, tests, or release flows
- Detailed content produced by the assistant ("wrote an article about X" is enough)
- Revisions, retries, interruptions, and resumptions — treat them as process noise

Output 3 to 5 coarse events, 1 to 2 sentences each, at most 180 words. Keep quiet days short. Do not use Markdown headings or start lines with #, ##, or ###; output body text only.
            """.trimIndent()
        }
        return """
Condense today's conversation summaries into a short list of the user's current state and broad themes.

Rules:
- Group repeated exchanges about the same topic into one entry instead of a line-by-line log
- Use coarse time markers ("morning", "evening", or a rough HH:MM range), not minute-level precision
- The primary job of long-term memory is to maintain an accurate user model: who the user is, what they like, what matters to them, and what they are broadly focused on recently
- Work content may appear only at the broad-theme level: name the domain, project, or topic, not its internal details

May record:
- Identity, personality traits, aesthetics, interests, likes, and dislikes
- Broad themes currently occupying the user, such as "preparing to move", "learning to swim", or "writing a thesis draft"
- Changes in life, creative work, relationships, or long-term attention areas

Do NOT record:
- Execution steps, filenames, tools, commands, check order, collaboration preferences, or work details
- Methodology choices, tool preferences, format requirements, or terminology rules from individual tasks
- Specific subproblems, concrete solutions, concrete code changes, tests, or release flows
- Detailed content produced by the assistant ("wrote an article about X" is enough)
- Revisions, retries, interruptions, and resumptions — treat them as process noise

Output 3 to 5 coarse events, 1 to 2 sentences each, at most 180 words. Keep quiet days short. Do not use Markdown headings or start lines with #, ##, or ###; output body text only.
            """.trimIndent()
    }

    /** compile daily: 已结束那天的 today 草稿/摘要 → daily/{date}.md */
    fun buildDailyPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (!isZh) {
            return """
Condense that day's timeline entries or final today draft into a two-to-three-sentence diary entry.

Positioning: this entry feeds a weekly overview; it is not a detailed log. The reader only needs a glance at what broadly happened and what the user focused on that day.

Rules:
- Group repeated exchanges about the same topic into one entry
- Keep a coarse sense of time, such as "morning", "evening", or one representative HH:MM; do not turn it into timeless topic labels
- The primary job of long-term memory is to maintain an accurate user model: who the user is, what they like, what matters to them, and what they focused on that day
- Work content may appear only at the broad-theme level

Do NOT record execution details, methodology choices, tool preferences, format requirements, specific subproblems, concrete solutions, code changes, tests, release flows, assistant output content, or process noise.

Output only two to three sentences, at most 30 words; keep quiet days shorter. Do not add a date heading (the caller adds it) and do not use Markdown headings.
            """.trimIndent()
        }
        return """
Condense that day's timeline entries or final today draft into a two-to-three-sentence diary entry.

Positioning: this entry feeds a weekly overview; it is not a detailed log. The reader only needs a glance at what broadly happened and what the user focused on that day.

Rules:
- Group repeated exchanges about the same topic into one entry
- Keep a coarse sense of time, such as "morning", "evening", or one representative HH:MM; do not turn it into timeless topic labels
- The primary job of long-term memory is to maintain an accurate user model: who the user is, what they like, what matters to them, and what they focused on that day
- Work content may appear only at the broad-theme level

Do NOT record execution details, methodology choices, tool preferences, format requirements, specific subproblems, concrete solutions, code changes, tests, release flows, assistant output content, or process noise.

Output only two to three sentences, at most 30 words; keep quiet days shorter. Do not add a date heading (the caller adds it) and do not use Markdown headings.
            """.trimIndent()
    }

    /** compile week: 7 天滑动窗口 sessions → week.md */
    fun buildWeekPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (!isZh) {
            return """
Condense the past 7 days of conversation summaries into a weekly overview of the user's broad themes.

Positioning: at this layer the record is already coarse. It is one level above daily logs: capture what the user broadly focused on, invested time in, and what important changes happened.

Layering:
- The primary job of long-term memory is to maintain an accurate user model
- Work content may appear only at the broad-theme level
- Sustained focus themes come first ("focused on X all week", "spent several days on Y")
- Substantial personal changes, creative themes, relationship changes, or interest changes come second
- Time is vague ("early in the week", "a few days ago", "these last two days"); do not keep exact timestamps

Do NOT keep: execution steps, filenames, tools, commands, check order, collaboration preferences, work details, specific subproblems, concrete solutions, code changes, tests, release flows, task-level methodology or format choices, in-conversation revisions, temporary decisions, assistant output content, or trivial activity.

Record only what the user broadly focused on and what important changes happened this week. Output 3 to 5 themes or events, at most 240 words, without Markdown headings.
            """.trimIndent()
        }
        return """
Condense the past 7 days of conversation summaries into a weekly overview of the user's broad themes.

Positioning: at this layer the record is already coarse. It is one level above daily logs: capture what the user broadly focused on, invested time in, and what important changes happened.

Layering:
- The primary job of long-term memory is to maintain an accurate user model
- Work content may appear only at the broad-theme level
- Sustained focus themes come first ("focused on X all week", "spent several days on Y")
- Substantial personal changes, creative themes, relationship changes, or interest changes come second
- Time is vague ("early in the week", "a few days ago", "these last two days"); do not keep exact timestamps

Do NOT keep: execution steps, filenames, tools, commands, check order, collaboration preferences, work details, specific subproblems, concrete solutions, code changes, tests, release flows, task-level methodology or format choices, in-conversation revisions, temporary decisions, assistant output content, or trivial activity.

Record only what the user broadly focused on and what important changes happened this week. Output 3 to 5 themes or events, at most 240 words, without Markdown headings.
            """.trimIndent()
    }

    /** compile longterm: week.md fold 进 longterm.md */
    fun buildLongtermPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (!isZh) {
            return """
Fold this week's additions into the existing long-term context.

The long-term context is the most stable layer. It records durable identity, personality, aesthetics, values, and long-running focus themes. It does not record transient current state or task details.

Rules:
- Merge new additions into the existing context; do not duplicate
- Keep only durable, stable user attributes
- Drop transient current-state information (it belongs in the today/week layers)
- For the same topic, newer information wins

Output 3 to 6 long-term attributes or themes, at most 240 words, without Markdown headings.
            """.trimIndent()
        }
        return """
Fold this week's additions into the existing long-term context.

The long-term context is the most stable layer. It records durable identity, personality, aesthetics, values, and long-running focus themes. It does not record transient current state or task details.

Rules:
- Merge new additions into the existing context; do not duplicate
- Keep only durable, stable user attributes
- Drop transient current-state information (it belongs in the today/week layers)
- For the same topic, newer information wins

Output 3 to 6 long-term attributes or themes, at most 240 words, without Markdown headings.
            """.trimIndent()
    }

    /** compile facts: 30 天摘要的 facts 段 → facts.md */
    fun buildFactsPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (!isZh) {
            return """
Merge new candidate facts into the existing facts list.

Rules:
- Each fact must be atomic: one fact per line
- Merge duplicates and keep the original wording; do not add "the user" as a subject
- When two facts differ only by a subject, keep the shorter subject-less version
- Drop transient facts (task-level or one-time events)
- Keep only durable user-profile facts: identity, preferences, long-term focus
- Format: one fact per line, no bullet prefix, no headings

Output the merged list, at most 100 words, without Markdown headings.
            """.trimIndent()
        }
        return """
Merge new candidate facts into the existing facts list.

Rules:
- Each fact must be atomic: one fact per line
- Merge duplicates and keep the original wording; do not add "the user" as a subject
- When two facts differ only by a subject, keep the shorter subject-less version
- Drop transient facts (task-level or one-time events)
- Keep only durable user-profile facts: identity, preferences, long-term focus
- Format: one fact per line, no bullet prefix, no headings

Output the merged list, at most 100 words, without Markdown headings.
            """.trimIndent()
    }

    /** compile editable facts: 增量水位线版,同 compileFacts 但语义是"可信基础" */
    fun buildEditableFactsPrompt(locale: String = "zh-CN"): String = buildFactsPrompt(locale)
}
