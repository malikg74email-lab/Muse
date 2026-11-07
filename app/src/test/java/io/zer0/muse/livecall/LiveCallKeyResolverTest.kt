package io.zer0.muse.livecall

import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 Fix Phase 2: 通话启动策略测试 —— 同时是「ASR 门已移除」的构造性证明。
 *
 * 旧版失败的形状:新装默认 AsrConfig(provider=SYSTEM) + Malik 只填了 Gemini 聊天 key,
 * 语音对话被 shouldUseApiRecording() 直接锁死。新策略的 decide() 只读供应商列表,
 * 根本不接收 AsrConfig —— 同样的新装默认状态下,只要 Gemini key 在,就必须 Ready。
 */
class LiveCallKeyResolverTest {

    private fun provider(
        id: String,
        type: ProviderType,
        apiKey: String,
        enabled: Boolean = true,
    ) = ProviderConfig(
        id = id,
        displayName = id,
        type = type,
        apiKey = apiKey,
        enabled = enabled,
    )

    @Test
    fun `fresh install shape - gemini chat key present means the call may start`() {
        // 这正是 Malik 手机上的形状:ASR 还是默认 SYSTEM(旧门会拦),
        // 但供应商里有一个带 key 的 Gemini —— 新策略必须放行。
        val providers = listOf(provider("gemini-1", ProviderType.GEMINI, "KEY-123"))
        val decision = LiveCallKeyResolver.decide(providers, activeProviderId = "gemini-1")
        assertEquals(LiveCallKeyResolver.Decision.Ready("KEY-123"), decision)
    }

    @Test
    fun `active gemini provider wins over other gemini providers`() {
        val providers = listOf(
            provider("gemini-old", ProviderType.GEMINI, "KEY-OLD"),
            provider("gemini-active", ProviderType.GEMINI, "KEY-ACTIVE"),
        )
        val key = LiveCallKeyResolver.resolveGeminiKey(providers, activeProviderId = "gemini-active")
        assertEquals("KEY-ACTIVE", key)
    }

    @Test
    fun `falls back to any enabled gemini provider when the active one is not gemini`() {
        val providers = listOf(
            provider("openai-1", ProviderType.OPENAI, "KEY-OPENAI"),
            provider("gemini-1", ProviderType.GEMINI, "KEY-GEMINI"),
        )
        val key = LiveCallKeyResolver.resolveGeminiKey(providers, activeProviderId = "openai-1")
        assertEquals("KEY-GEMINI", key)
    }

    @Test
    fun `no usable gemini key means NoKey, never a fake start`() {
        val cases = listOf(
            emptyList(),
            listOf(provider("openai-1", ProviderType.OPENAI, "KEY")),
            listOf(provider("gemini-1", ProviderType.GEMINI, "")),
            listOf(provider("gemini-1", ProviderType.GEMINI, "KEY", enabled = false)),
        )
        cases.forEach { providers ->
            assertEquals(
                LiveCallKeyResolver.Decision.NoKey,
                LiveCallKeyResolver.decide(providers, activeProviderId = null),
            )
        }
    }

    @Test
    fun `disabled active gemini is skipped for an enabled one`() {
        val providers = listOf(
            provider("gemini-off", ProviderType.GEMINI, "KEY-OFF", enabled = false),
            provider("gemini-on", ProviderType.GEMINI, "KEY-ON"),
        )
        val key = LiveCallKeyResolver.resolveGeminiKey(providers, activeProviderId = "gemini-off")
        assertEquals("KEY-ON", key)
    }

    @Test
    fun `decision is Ready only with the exact stored key`() {
        val providers = listOf(provider("g", ProviderType.GEMINI, "  KEY-WITH-SPACE  "))
        val decision = LiveCallKeyResolver.decide(providers, activeProviderId = "g")
        assertTrue(decision is LiveCallKeyResolver.Decision.Ready)
    }
}
