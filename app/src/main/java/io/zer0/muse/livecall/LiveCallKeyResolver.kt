package io.zer0.muse.livecall

import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType

/**
 * Shaheen 2 Fix Phase 2: 通话启动策略(纯逻辑)—— 这是「去掉 ASR 门」的落点。
 *
 * 启动通话只需要一件事:Malik 已保存的 Gemini 聊天 key。整个决策函数只读
 * 供应商列表,**根本不接收 AsrConfig** —— 旧版「SYSTEM ASR 默认值把语音对话
 * 锁死」的门在这里构造上就不存在,单测会把这个性质钉死。
 *
 * key 的挑选顺序:
 *  1. 当前生效的供应商就是 GEMINI 且 key 非空 → 用它(与聊天同一个 key);
 *  2. 否则用任意一个已启用、key 非空的 GEMINI 供应商;
 *  3. 都没有 → [Decision.NoKey],界面给出「去设置 key」的明确入口,绝不假装能打。
 */
object LiveCallKeyResolver {

    sealed interface Decision {
        data class Ready(val apiKey: String) : Decision
        data object NoKey : Decision
    }

    fun resolveGeminiKey(providers: List<ProviderConfig>, activeProviderId: String?): String? {
        val active = providers.firstOrNull { it.id == activeProviderId }
        if (active != null && active.isUsableGemini()) return active.apiKey
        return providers.firstOrNull { it.isUsableGemini() }?.apiKey
    }

    fun decide(providers: List<ProviderConfig>, activeProviderId: String?): Decision {
        val key = resolveGeminiKey(providers, activeProviderId)
        return if (key.isNullOrBlank()) Decision.NoKey else Decision.Ready(key)
    }

    private fun ProviderConfig.isUsableGemini(): Boolean =
        enabled && type == ProviderType.GEMINI && apiKey.isNotBlank()
}
