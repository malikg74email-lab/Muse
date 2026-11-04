package io.zer0.muse.data.milestone

import io.zer0.common.Logger
import io.zer0.muse.data.session.MessageDao
import io.zer0.muse.data.session.SessionDao

/**
 * Phase 2 2B: 里程碑检查器 — 在消息发送/会话创建时检查是否触发里程碑。
 *
 * 触发条件:
 *  - first_message: 第一条消息
 *  - day_7: 相伴第 7 天
 *  - msg_100: 第 100 条消息
 *  - day_30: 相伴第 30 天
 *  - day_100: 相伴第 100 天
 *  - msg_1000: 第 1000 条消息
 *  - day_365: 相伴第 365 天
 */
class MilestoneChecker(
    private val milestoneDao: MilestoneDao,
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
) {
    companion object {
        private const val TAG = "MilestoneChecker"

        data class MilestoneDefinition(
            val condition: String,
            val title: String,
            val messageTemplate: String,
        )

        val DEFINITIONS = listOf(
            MilestoneDefinition("first_message", "First conversation", "Your story starts with this message ✨"),
            MilestoneDefinition("day_7", "Day 7 together", "A week of companionship is the most beautiful beginning 🌿"),
            MilestoneDefinition("msg_100", "100th message", "A hundred conversations, a hundred sparks of inspiration 💡"),
            MilestoneDefinition("day_30", "Day 30 together", "A month together — already the friend who knows you best 🌙"),
            MilestoneDefinition("day_100", "Day 100 together", "A hundred days together — thank you for walking this road with me ⭐"),
            MilestoneDefinition("msg_1000", "1000th message", "A thousand conversations, every one of them unique ✨"),
            MilestoneDefinition("day_365", "One year together", "A full year together — this is the most precious relationship 🎉"),
        )
    }

    /**
     * 检查并触发里程碑。应在消息发送后调用。
     */
    suspend fun checkAndTrigger(sessionId: String, assistantId: String? = null) {
        try {
            checkMessageMilestones(sessionId, assistantId)
            checkDayMilestones(sessionId, assistantId)
        } catch (e: Exception) {
            Logger.w(TAG, "Milestone check failed", e)
        }
    }

    private suspend fun checkMessageMilestones(sessionId: String, assistantId: String?) {
        val messageCount = messageDao.countBySession(sessionId)
        val thresholds = mapOf(
            "first_message" to 1L,
            "msg_100" to 100L,
            "msg_1000" to 1000L,
        )
        for ((condition, threshold) in thresholds) {
            if (messageCount >= threshold) {
                insertIfNew(condition, threshold, assistantId, sessionId)
            }
        }
    }

    private suspend fun checkDayMilestones(sessionId: String, assistantId: String?) {
        val session = sessionDao.getById(sessionId) ?: return
        val days = (System.currentTimeMillis() - session.createdAt) / (24 * 60 * 60 * 1000)
        val dayThresholds = mapOf(
            "day_7" to 7L,
            "day_30" to 30L,
            "day_100" to 100L,
            "day_365" to 365L,
        )
        for ((condition, threshold) in dayThresholds) {
            if (days >= threshold) {
                insertIfNew(condition, days, assistantId, sessionId)
            }
        }
    }

    private suspend fun insertIfNew(condition: String, triggerValue: Long, assistantId: String?, sessionId: String) {
        val existing = milestoneDao.findByCondition(condition)
        if (existing != null) return

        val def = DEFINITIONS.find { it.condition == condition } ?: return
        milestoneDao.upsert(
            MilestoneEntity(
                conditionType = condition,
                triggerValue = triggerValue,
                title = def.title,
                message = def.messageTemplate,
                assistantId = assistantId,
                sessionId = sessionId,
            ),
        )
        Logger.i(TAG, "Milestone triggered: $condition (value=$triggerValue)")
    }
}
