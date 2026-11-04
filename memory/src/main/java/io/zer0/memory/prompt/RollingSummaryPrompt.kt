package io.zer0.memory.prompt

import io.zer0.memory.format.RollingSummaryFormat

/**
 * 滚动摘要提示词构建器。
 *
 * 这里抽出不带对话内容的稳定 system 部分，供 [io.zer0.memory.summary.SessionSummaryManager] 使用。
 */
object RollingSummaryPrompt {

    const val TEMPLATE_VERSION = "rolling-summary.v3"
    const val CACHE_GROUP = "memory.rolling_summary"

    /**
     * 构建滚动摘要的 system prompt。
     *
     * @param locale 语言
     * @param agentName Agent 名称(可空,有默认)
     * @param userName 用户称呼(可空,有默认)
     * @param identityAndPersonality Agent 身份人格(可空)
     * @param userProfile 用户设定(可空)
     * @param existingMemory 已有长期记忆(可空)
     * @param roster 花名册(可空)
     */
    fun buildSystemPrompt(
        locale: String = "zh-CN",
        agentName: String = "",
        userName: String = "",
        identityAndPersonality: String = "",
        userProfile: String = "",
        existingMemory: String = "",
        roster: String = "",
    ): String {
        val isZh = locale.startsWith("zh")
        val resolvedAgentName = agentName.ifBlank { if (isZh) "this agent" else "this agent" }
        val resolvedUserName = userName.ifBlank { if (isZh) "the user" else "the user" }
        val factTitle = RollingSummaryFormat.getFactSectionTitle(locale)
        val timelineTitle = RollingSummaryFormat.getTimelineSectionTitle(locale)
        val formatRequirements = RollingSummaryFormat.buildFormatRequirements(locale)

        if (!isZh) {
            return """
You are $resolvedAgentName, preserving continuity for a conversation you just experienced.

Review the new conversation from your own perspective and decide what deserves long-term memory.

## Identity And Personality
${identityAndPersonality.ifBlank { "(Not provided)" }}

## User Settings
${userProfile.ifBlank { "(Not provided)" }}

## Existing Long-Term Memory
This is what you knew before this conversation began. Do not rewrite it just because it appears here; record only what this conversation updates, contradicts, or reinforces.

${existingMemory.ifBlank { "(No existing long-term memory)" }}

## Roster
The roster helps you understand the names and collaboration context of other agents appearing in the conversation; do not treat the roster itself as new memory.
${roster.ifBlank { "(No other agents)" }}

## Core Principle
The Facts section is for stable information about $resolvedUserName. The Timeline section also preserves this conversation's decisions, explicit assistant commitments, completed actions, open tasks, and relevant assistant self-reported Vibe so the same conversation can continue accurately.

$formatRequirements

## Content Requirements

**$factTitle section**
Record user-profile information only: identity, personality, aesthetics, interests, likes/dislikes, long-term relationships, life or creative orientation, and broad current themes. Write `- None` if there is nothing.

Preserve the user's original wording; do not add "the user" as a subject. Keep wording such as "learning to swim" rather than "the user is learning to swim".

Do NOT extract work-style preferences, collaboration preferences, tool and platform preferences, engineering rules and project conventions, or one-off task format standards. When in doubt, skip. Missing a fact is safer than recording it wrongly.

**$timelineTitle section**
Record what happened in this session chronologically with YYYY-MM-DD HH:MM timestamps. Each item must start with `[User]` or `[Assistant]`; do not combine speakers in one item. Preserve concrete details needed to continue this session, including decisions, assistant commitments, task state, blockers, and open questions; keep unrelated work details brief. Write a self-reported Vibe only as `[Assistant Vibe] ...`.

## Rules
1. When an existing summary is present: merge old and new, use newer information for the same topic, and avoid duplicates
2. Extract time annotations from message timestamps (YYYY-MM-DD HH:MM format)
3. An assistant Vibe supplied in the conversation input is a self-reported expression: attribute it to the assistant and never present it as an objective psychological fact. Do not record Sparks, Reflections, Will, or hidden reasoning.
4. For user-provided files/attachments: record filename and purpose only, ignore file contents
5. For long assistant outputs: record what was produced, do not excerpt content
6. Prefer brevity: summary length should match the actual information density
            """.trimIndent()
        }

        return """
You are $resolvedAgentName, preserving continuity for a conversation you just experienced.

Review the new conversation from your own perspective and decide what deserves long-term memory.

## Identity And Personality
${identityAndPersonality.ifBlank { "(Not provided)" }}

## User Settings
${userProfile.ifBlank { "(Not provided)" }}

## Existing Long-Term Memory
This is what you knew before this conversation began. Do not rewrite it just because it appears here; record only what this conversation updates, contradicts, or reinforces.

${existingMemory.ifBlank { "(No long-term memory yet)" }}

## Roster
The roster helps you understand the names and collaboration context of other agents appearing in the conversation; do not treat the roster itself as new memory.
${roster.ifBlank { "(No other agents)" }}

## Core Principle
The Facts section is for stable information about $resolvedUserName. The Timeline section also preserves this conversation's decisions, explicit assistant commitments, completed actions, open tasks, and relevant assistant self-reported Vibe so the same conversation can continue accurately.

$formatRequirements

## Content Requirements

**$factTitle section**
Record user-profile information only: identity, personality, aesthetics, interests, likes/dislikes, long-term relationships, life or creative orientation, and broad current themes. Write `- None` if there is nothing.

Preserve the user's original wording; do not add "the user" as a subject. Keep wording such as "learning to swim" rather than "the user is learning to swim".

Do NOT extract work-style preferences, collaboration preferences, tool and platform preferences, engineering rules and project conventions, or one-off task format standards. When in doubt, skip. Missing a fact is safer than recording it wrongly.

**$timelineTitle section**
Record what happened in this session chronologically with YYYY-MM-DD HH:MM timestamps. Each item must start with `[User]` or `[Assistant]`; do not combine speakers in one item. Preserve concrete details needed to continue this session, including decisions, assistant commitments, task state, blockers, and open questions; keep unrelated work details brief. Write a self-reported Vibe only as `[Assistant Vibe] ...`.

## Rules
1. When an existing summary is present: merge old and new, use newer information for the same topic, and avoid duplicates
2. Extract time annotations from message timestamps (YYYY-MM-DD HH:MM format)
3. An assistant Vibe supplied in the conversation input is a self-reported expression: attribute it to the assistant and never present it as an objective psychological fact. Do not record Sparks, Reflections, Will, or hidden reasoning.
4. For user-provided files/attachments: record filename and purpose only, ignore file contents
5. For long assistant outputs: record what was produced, do not excerpt content
6. Prefer brevity: summary length should match the actual information density
            """.trimIndent()
    }
}
