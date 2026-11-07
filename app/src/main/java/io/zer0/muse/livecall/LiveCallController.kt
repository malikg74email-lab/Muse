package io.zer0.muse.livecall

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shaheen 2 Fix Phase 2: 通话的总控(Koin 单例,界面 / 前台服务 / 引擎三方的汇合点)。
 *
 * 职责:
 *  1. 启动决策只走 [LiveCallKeyResolver] —— 只看供应商列表里的 Gemini key,
 *     完全不碰 ASR 配置(旧版 SYSTEM-ASR 门在这里构造上不存在);
 *  2. 组装通话工具面:从共享 [ToolRegistry] 取定义、经 [LiveCallToolBridge]
 *     筛子集,执行也回注册表 executeFromJson —— 与聊天同一条链;
 *  3. 持有 [GeminiLiveSession] 并把它的状态折成一份 [UiState] 给通话界面;
 *  4. 通话开始拉起 [LiveCallService](前台保活 + End Call 通知),结束时停掉。
 */
class LiveCallController(
    context: Context,
    private val settings: SettingsRepository,
    private val toolRegistry: ToolRegistry,
) {

    /** 通话界面消费的一份完整状态。 */
    data class UiState(
        val phase: LiveCallPhase = LiveCallPhase.IDLE,
        val transcript: List<LiveTranscriptLine> = emptyList(),
        val muted: Boolean = false,
        val modelSpeaking: Boolean = false,
        val audioLevel: Float = 0f,
        val errorMessage: String? = null,
        /** true 时界面应展示「去设置 key」的入口(不是错误横幅,是明确的下一步)。 */
        val needsKeySetup: Boolean = false,
        val connectedAtMillis: Long? = null,
    )

    companion object {
        private const val TAG = "LiveCallController"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    @Volatile
    private var session: GeminiLiveSession? = null
    private var sessionJobs: List<Job> = emptyList()

    /**
     * 发起通话。界面点圆环 / 错误屏点 Retry 都走这里;通话中重复调用无副作用。
     * 没有 key 时不启动任何音频/网络,只把「需要设置 key」的状态摆到界面上。
     */
    fun startCall() {
        val phase = _uiState.value.phase
        if (phase == LiveCallPhase.CONNECTING || phase == LiveCallPhase.LIVE) return
        scope.launch {
            val providers = runCatching { settings.providersFlow.first() }.getOrElse {
                Logger.w(TAG, "Reading providers failed: ${it.message}")
                emptyList()
            }
            val activeId = runCatching { settings.activeProviderIdFlow.first() }.getOrNull()
            when (val decision = LiveCallKeyResolver.decide(providers, activeId)) {
                LiveCallKeyResolver.Decision.NoKey -> {
                    _uiState.value = UiState(
                        phase = LiveCallPhase.IDLE,
                        errorMessage = appContext.getString(R.string.live_call_no_key_message),
                        needsKeySetup = true,
                    )
                    Logger.i(TAG, "Live call not started: no Gemini key configured")
                }
                is LiveCallKeyResolver.Decision.Ready -> beginSession(decision.apiKey)
            }
        }
    }

    private fun beginSession(apiKey: String) {
        endSessionSilently()
        val declarations = LiveCallToolBridge.buildVoiceDeclarations(
            toolRegistry.listToolsAsToolDefinitions(),
        )
        val executor = LiveToolExecutor { name, argsJson ->
            toolRegistry.executeFromJson(name, argsJson)
        }
        val newSession = GeminiLiveSession(
            context = appContext,
            apiKey = apiKey,
            toolDeclarations = declarations,
            toolExecutor = executor,
        )
        session = newSession
        sessionJobs = listOf(
            scope.launch {
                newSession.phase.collect { phase ->
                    _uiState.value = _uiState.value.copy(
                        phase = phase,
                        connectedAtMillis = newSession.connectedAtMillis,
                    )
                    if (phase == LiveCallPhase.ENDED || phase == LiveCallPhase.ERROR) {
                        LiveCallService.stop(appContext)
                    }
                }
            },
            scope.launch {
                newSession.transcript.collect { lines ->
                    _uiState.value = _uiState.value.copy(transcript = lines)
                }
            },
            scope.launch {
                newSession.errorMessage.collect { message ->
                    if (message != null) {
                        _uiState.value = _uiState.value.copy(errorMessage = message)
                    }
                }
            },
            scope.launch {
                newSession.modelSpeaking.collect { speaking ->
                    _uiState.value = _uiState.value.copy(modelSpeaking = speaking)
                }
            },
            scope.launch {
                newSession.audioLevel.collect { level ->
                    _uiState.value = _uiState.value.copy(audioLevel = level)
                }
            },
            scope.launch {
                newSession.muted.collect { muted ->
                    _uiState.value = _uiState.value.copy(muted = muted)
                }
            },
        )
        _uiState.value = UiState(phase = LiveCallPhase.CONNECTING)
        LiveCallService.start(appContext)
        newSession.start()
        Logger.i(TAG, "Live call session started (${declarations.size} voice tools)")
    }

    /** 结束通话(界面挂断与通知 End Call 共用)。 */
    fun endCall() {
        session?.end()
        LiveCallService.stop(appContext)
        if (_uiState.value.phase != LiveCallPhase.ENDED) {
            _uiState.value = _uiState.value.copy(phase = LiveCallPhase.ENDED)
        }
    }

    /** 错误屏的 Retry:真正重拨(重新解析 key + 新建 socket),不是重画界面。 */
    fun retry() {
        endSessionSilently()
        _uiState.value = UiState()
        startCall()
    }

    fun toggleMute() {
        val current = session ?: return
        current.setMuted(!_uiState.value.muted)
    }

    /** 界面关闭后清掉终局状态(ENDED / ERROR / 待设置 key),下次打开是干净的 IDLE。 */
    fun dismissFinished() {
        val phase = _uiState.value.phase
        if (phase == LiveCallPhase.ENDED || phase == LiveCallPhase.ERROR || _uiState.value.needsKeySetup) {
            endSessionSilently()
            LiveCallService.stop(appContext)
            _uiState.value = UiState()
        }
    }

    private fun endSessionSilently() {
        sessionJobs.forEach { it.cancel() }
        sessionJobs = emptyList()
        // 旧会话一并真正收尾(end() 内部有 CAS 幂等,重复调用无副作用),
        // 避免快速重拨时旧 socket 泄漏成「幽灵通话」。
        session?.end()
        session = null
    }
}
