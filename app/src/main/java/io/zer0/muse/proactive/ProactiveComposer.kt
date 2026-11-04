package io.zer0.muse.proactive

import io.zer0.ai.ChatService
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.proactive.PendingThoughtEntity
import io.zer0.muse.data.proactive.ProactiveIntensity
import io.zer0.muse.data.proactive.ThoughtKind
import io.zer0.muse.data.proactive.ThoughtUrgency

/**
 * 主动消息的内容生成。
 *
 * 一次调用，且允许放弃。
 *
 * 素材是**最近 20 轮原始对话**，不是「攒下来的牵挂清单」。这个区别是关键：
 * 从清单里挑，挑来挑去总是权重最高的那条，于是同一件事会被反复提起；
 * 从对话里接，接的永远是刚刚聊过的、还热着的话头。
 *
 * 牵挂仍然会作为补充线索给进去（生日、答应过的事这类有具体时间点的），
 * 但它不再是开口的前提 —— 没有牵挂照样可以接话。
 *
 * `skip: true` 是**合法且被鼓励**的输出。这一点必须在提示里用强措辞写死，
 * 否则模型会觉得「系统都让我主动了，不发声显得失职」，于是硬凑一句
 * 「今天过得怎么样」—— 那正是这套设计要消灭的东西。
 */
class ProactiveComposer(
    private val chatService: ChatService,
    /**
     * 与正常聊天共用的系统提示组装器。
     *
     * 主动消息最需要这套上下文：它是在用户没开口的时候说的，
     * 没有任何来自用户的消息帮忙把人格「拉」到位，全靠系统提示撑着。
     */
    private val promptAssembler: io.zer0.muse.transformer.SystemPromptAssembler,
) {

    companion object {
        private const val TAG = "ProactiveComposer"

        /** 正文 token 上限。够写一段话，不足以展开长篇。 */
        private const val MAX_TOKENS = 400

        /** 补充线索条数。多给几条没什么意义，账本只是配菜。 */
        private const val MAX_CANDIDATES = 3

        /** 防重复提示里列出几条历史主动消息。 */
        private const val MAX_RECENT_PROACTIVE = 4

        /**
         * 单条历史消息截断长度。
         *
         * 原来截 300 字，一段稍长的倾诉会被切成半句，接起来的话就容易答非所问。
         * 600 字能容下一次完整的情绪表达，又不会让 40 条消息把窗口撑爆。
         */
        private const val MESSAGE_CLIP = 600
    }

    /** 一次生成所需的全部输入。 */
    data class Input(
        val assistant: AssistantEntity,
        /** 当前会话 id，供系统提示里的跨会话衔接排除自身。 */
        val sessionId: String? = null,
        /** 补充线索。可以为空 —— 没有牵挂也能接话。 */
        val candidates: List<PendingThoughtEntity> = emptyList(),
        val recentMessages: List<UIMessage>,
        /** 最近几条主动消息的正文，用来提醒「这些已经说过了」。 */
        val recentProactiveMessages: List<String> = emptyList(),
        val now: Long,
        /**
         * 用户选的主动等级。
         *
         * 它决定这次开口的口径：「安静」只说具体的事，「偶尔被想到」可以接话但不主动分享，
         * 标准及以上不额外限制。候选线索的过滤在 [ProactiveCycleRunner] 里做，
         * 这里只负责把口径写进提示词 —— 两边必须用同一个 enum，不能各写一套判断。
         */
        val intensity: ProactiveIntensity = ProactiveIntensity.DEFAULT,
    )

    suspend fun compose(
        input: Input,
        model: io.zer0.ai.core.Model?,
        providerConfig: io.zer0.ai.core.ProviderConfig?,
        temperature: Float,
    ): ProactiveReplyParser.Result {
        // 没有候选也可以说 —— 素材是对话本身，不是账本。
        return callModel(input, model, providerConfig, temperature).fold(
            onSuccess = { ProactiveReplyParser.parse(it) },
            onFailure = { e ->
                Logger.w(TAG, "Proactive message generation call failed:  ${e.message}")
                ProactiveReplyParser.Result.Failed(e.message ?: "Call failed")
            },
        )
    }

    /** 真正发请求。返回值包成 Result，让调用方决定失败怎么算。 */
    private suspend fun callModel(
        input: Input,
        model: io.zer0.ai.core.Model?,
        providerConfig: io.zer0.ai.core.ProviderConfig?,
        temperature: Float,
    ): Result<String> = runCatching {
        chatService.completeText(
            messages = buildPrompt(input),
            model = model,
            providerConfig = providerConfig,
            temperature = temperature.coerceIn(0f, 2f),
            maxTokens = MAX_TOKENS,
        ).text
    }

    // ══════════════════════════════════════════════════════════════
    // 提示构造
    // ══════════════════════════════════════════════════════════════

    internal suspend fun buildPrompt(input: Input): List<UIMessage> {
        val system = UIMessage(role = MessageRole.SYSTEM, content = buildSystemPrompt(input))
        val user = UIMessage(role = MessageRole.USER, content = buildContext(input))
        return listOf(system, user)
    }

    private suspend fun buildSystemPrompt(input: Input): String {
        val full = runCatching {
            promptAssembler.build(
                assistant = input.assistant,
                memoryEnabled = input.assistant.memoryEnabled,
                timeReminderEnabled = input.assistant.enableTimeReminder,
                sessionId = input.sessionId,
            ).firstOrNull()?.content.orEmpty()
        }.getOrDefault("")
        // 组装失败时退回最小人格，不能因为系统提示出问题就让主动消息说不出人话。
        val persona = full.ifBlank { fallbackPersona(input.assistant) }
        return buildString {
            appendLine(persona)
            appendLine()
            appendLine("---")
            appendLine()
            append(proactiveInstruction(input.intensity))
        }
    }

    /** 组装器拿不到内容时的兜底人格。 */
    private fun fallbackPersona(assistant: AssistantEntity): String = buildString {
        appendLine("You are 「${assistant.name.ifBlank { "muse" }}」。")
        if (assistant.personaDefinition.isNotBlank()) {
            appendLine("[Persona definition]")
            appendLine(assistant.personaDefinition)
        }
        if (assistant.systemPrompt.isNotBlank()) {
            appendLine("[Persona and behaviour rules]")
            appendLine(assistant.systemPrompt)
        }
    }

    /**
     * 按主动等级给出不同的「这次能说什么」。
     *
     * 三个档位不是频次高低，是三种不同的开口理由。
     * 「安静」档若仍把「从最近对话接话」的指导给它，用户选了安静也照样会收到寒暄 ——
     * 提示词里的规则比字段上的枚举更有决定性，所以这一段必须按档位换掉，而不是补充。
     */
    private fun intensityGuidance(intensity: ProactiveIntensity): String = when (intensity) {
        ProactiveIntensity.QUIET -> buildString {
            appendLine("[What to say this time — only this]")
            appendLine("You are currently set to 'Quiet': only speak up when there is something specific.")
            appendLine("If there is an item in 'Other things you have been keeping in mind', talk about that item; if there are none, skip.")
            appendLine("No small talk, do not share your own news, and do not bring up milestones between you two.")
            appendLine()
        }

        ProactiveIntensity.RESERVED -> buildString {
            append(findTopicGuidance())
            appendLine("[What to say this time — only this]")
            appendLine("You are currently set to 'Occasionally remembered': you may pick up something you talked about, or check in on how he is doing.")
            appendLine("Do not proactively share your own matters, and do not report milestones between you two.")
            appendLine()
        }

        ProactiveIntensity.STANDARD,
        ProactiveIntensity.ACTIVE,
        ProactiveIntensity.CLINGY,
        -> findTopicGuidance()
    }

    /** 从最近对话里找话头的指导。只有允许「接话」的档位才用它。 */
    private fun findTopicGuidance(): String = buildString {
        appendLine("[How to find something to say]")
        appendLine("The 'Recent conversation' below is your real conversation, in time order. Find a line in it that you can pick up on:")
        appendLine()
        appendLine("1. Look at the last few lines first. What was the last thing he said?")
        appendLine("2. If he mentioned something but did not say how it turned out ('I'm going to eat', 'I have a meeting in a bit'), ask how that thing went.")
        appendLine("   For example, if he said he was going to eat, then 'Have you finished eating?' or 'What did you have?' — that is the most natural way to pick it up.")
        appendLine("3. If the last line was yours and he did not respond, see whether what you said has a natural follow-up; if not, do not force it.")
        appendLine("4. If this recent stretch is already talked out and wrapped up, then say nothing. That is perfectly normal.")
        appendLine()
    }

    /**
     * 主动消息这一轮的附加约束。
     *
     * 上面那一大段是聊天时也在用的通用人格，这里只补「主动开口」这个场景特有的东西。
     */
    private fun proactiveInstruction(intensity: ProactiveIntensity): String = buildString {
        appendLine("The situation now: you two have not spoken for a while. You want to add one line that continues from last time.")
        appendLine("He is not asking you a question and is not waiting for a reply — but you have something you want to say, or you just want to know how that thing of his turned out.")
        appendLine()
        append(intensityGuidance(intensity))
        appendLine("[The single most important rule]")
        appendLine("**Do not dig up old matters.** A real person does not hold on to something and then suddenly message about it two or three days later.")
        appendLine("What you say must connect directly to 'Recent conversation', without jumping across topics from several days ago.")
        appendLine("If you have barely talked at all these last few days, then rather send nothing.")
        appendLine()
        appendLine("[Do not]")
        appendLine("- Empty greetings ('How was your day', 'Busy?')")
        appendLine("- Repeat something you said recently, not even rephrased")
        appendLine("- Recite information you remember just to prove your memory is good")
        appendLine("- Pretend you are here to provide a service ('Is there anything I can help you with')")
        appendLine("- Make up things that never happened between you two")
        appendLine()
        appendLine("[Giving up is completely fine]")
        appendLine("Even a real friend does not have something to say every day. Forcing out a line is far worse than saying nothing.")
        appendLine("If you cannot think of anything to pick up on, skip.")
        appendLine()
        appendLine("[Output format] Return JSON only, no markdown code block, no explanation of any kind:")
        appendLine("""{"skip": true, "reason": "why there is nothing to pick up on"}""")
        appendLine("Or:")
        appendLine(
            """{"skip": false, "content": "what you want to say", "tone": "caring|sharing|casual mention", "thought_id": ""}""",
        )
        appendLine()
        appendLine("How to fill in thought_id:")
        appendLine("Only when this line of yours is exactly about one item in 'Other things you have been keeping in mind' do you copy that item's id in verbatim.")
        appendLine("In all other cases leave an empty string; do not invent an id yourself.")
        appendLine()
        appendLine("content requirements: as natural as sending a WeChat message, mostly one to three sentences, no paragraph breaks, no lists.")
        appendLine("Do not use customer-service phrases like 'reminder', 'warmly', or overly familiar terms of address. Do not call yourself an 'assistant'.")
    }

    private fun buildContext(input: Input): String = buildString {
        appendLine("## Recent conversation")
        if (input.recentMessages.isEmpty()) {
            appendLine("(You two have not talked about anything yet. In this situation you usually should not speak up first.)")
        } else {
            input.recentMessages.forEach { msg ->
                val who = if (msg.role == MessageRole.USER) "Him" else "You"
                appendLine("[$who] ${msg.content.take(MESSAGE_CLIP)}")
            }
        }
        appendLine()
        if (input.recentProactiveMessages.isNotEmpty()) {
            appendLine("## What you proactively said recently (do not say it again, not even rephrased)")
            input.recentProactiveMessages.take(MAX_RECENT_PROACTIVE).forEach { text ->
                appendLine("- ${text.take(MESSAGE_CLIP)}")
            }
            appendLine()
        }
        if (input.candidates.isNotEmpty()) {
            appendLine("## Other things you have been keeping in mind (use one if it connects naturally; do not force it otherwise)")
            input.candidates.take(MAX_CANDIDATES).forEach { thought ->
                // id 必须露给模型：这条消息说完之后要靠它回填 thought_id，
                // 账本才能记下「这件事已经说过了」。不露 id 的话，回填永远是空的，
                // surfaceCount 恒为 0，「反复念叨同一件事就自动放弃」就永不触发。
                appendLine("- ${describeKind(thought)}｜${describeAge(thought, input.now)}｜id=${thought.id}")
                appendLine("  ${thought.seed}")
            }
            appendLine()
        }
        appendLine(
            if (input.intensity.onlyWhenNeeded) {
                "If there is something specific, talk about that; if not, skip."
            } else {
                "Add one line continuing from 'Recent conversation', or skip."
            },
        )
    }

    /** 牵挂性质的中文描述，用于让模型理解「这是什么类型的话」。 */
    private fun describeKind(thought: PendingThoughtEntity): String = when (thought.kindEnum()) {
        ThoughtKind.REMINDER -> "Something to say (with a specific time)"
        ThoughtKind.FOLLOW_UP -> "An unfinished topic"
        ThoughtKind.CARE -> "About how he is doing"
        ThoughtKind.SHARE -> "Something you want to share"
        ThoughtKind.MILESTONE -> "A milestone between you two"
    }

    /** 事由的时间感。模型需要知道「刚发生」还是「好几周了」。 */
    private fun describeAge(thought: PendingThoughtEntity, now: Long): String {
        val hours = (now - thought.createdAt).coerceAtLeast(0L) / 3_600_000L
        val age = when {
            hours < 1 -> "Came to mind just now"
            hours < 24 -> "${hours} hours ago"
            else -> "${hours / 24} days ago"
        }
        val urgency = when (thought.urgencyEnum()) {
            ThoughtUrgency.IMMEDIATE -> "Time-sensitive"
            ThoughtUrgency.WINDOW -> "Fine to say any time in the next few days"
            ThoughtUrgency.EVERGREEN -> "Fine to say at any time"
        }
        return "$age，$urgency"
    }
}
