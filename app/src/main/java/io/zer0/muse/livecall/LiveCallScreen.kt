@file:Suppress("FunctionNaming", "LongMethod")

package io.zer0.muse.livecall

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/** 与首页 LiveCallRing 相同的红色细边色(v5 设计语言)。 */
private val LiveCallRed = Color(0xFFE5484D)

/**
 * Shaheen 2 Fix Phase 2: 真通话界面 —— 取代库存的 VoiceConversationMode 成为
 * 首页圆环与聊天语音入口的目的地。
 *
 * 视觉与首页圆环同一种语言:午夜藏青底、径向辉光盘、细红边、主题色亮环、中央麦克风。
 * 通话中辉光幅度跟随实时音量电平;字幕区逐行流式生长(服务端转写增量驱动)。
 *
 * 状态呈现原则(对应 Malik 的「不许假成功」标准):
 *  - 没 key → 明说原因 + 「Set up API key」直达应用内设置,绝不给截断的错误横幅;
 *  - 连接失败 → 全文原因 + Retry(Retry 是真的重拨,走控制器重新解析 key 建新 socket);
 *  - 麦克风被拒 → 明说原因 + 关闭,不假装在听。
 */
@Composable
fun LiveCallScreen(
    onClose: () -> Unit,
    onOpenKeySettings: () -> Unit,
    controller: LiveCallController = koinInject(),
) {
    val context = LocalContext.current
    val uiState by controller.uiState.collectAsStateWithLifecycle()
    var micDenied by remember { mutableStateOf(false) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            controller.startCall()
        } else {
            micDenied = true
        }
    }

    // 进入界面:已有进行中的通话就直接呈现;否则先过麦克风权限再发起通话。
    LaunchedEffect(Unit) {
        val phase = controller.uiState.value.phase
        if (phase == LiveCallPhase.LIVE || phase == LiveCallPhase.CONNECTING) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            controller.startCall()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 通话被别处结束(如通知上的 End Call)→ 本屏短暂显示「Call ended」后自动关闭。
    LaunchedEffect(uiState.phase) {
        if (uiState.phase == LiveCallPhase.ENDED) {
            delay(900)
            onClose()
        }
    }

    // 离开界面时只清终局状态;进行中的通话不因翻页被误杀(通知里仍可挂断)。
    DisposableEffect(Unit) {
        onDispose { controller.dismissFinished() }
    }

    val handleClose: () -> Unit = {
        when (uiState.phase) {
            LiveCallPhase.LIVE, LiveCallPhase.CONNECTING -> {
                controller.endCall()
                onClose()
            }
            else -> {
                controller.dismissFinished()
                onClose()
            }
        }
    }

    val showErrorPanel = micDenied || uiState.needsKeySetup || uiState.phase == LiveCallPhase.ERROR

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 顶栏:标题 + 关闭 ─────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.live_call_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                MuseTactileButton(
                    icon = MuseIcons.x,
                    onClick = handleClose,
                    contentDescription = stringResource(R.string.live_call_close),
                    tint = MaterialTheme.colorScheme.onBackground,
                    size = MuseIconSizes.touchTarget,
                    iconSize = MuseIconSizes.icon,
                )
            }

            // ── 圆环(与首页同款视觉,幅度随音量) ─────────────────────
            LiveCallOrb(
                phase = uiState.phase,
                audioLevel = uiState.audioLevel,
                muted = uiState.muted,
                modifier = Modifier.padding(top = MusePaddings.sectionGap),
            )

            // ── 状态行:连接中 / 聆听中 / 回答中 / 计时 ───────────────
            CallStatusLine(uiState = uiState)

            if (showErrorPanel) {
                // ── 错误/待设置面板:全文原因 + 真正的下一步 ─────────
                LiveCallErrorPanel(
                    message = when {
                        micDenied -> stringResource(R.string.live_call_mic_denied_message)
                        else -> uiState.errorMessage.orEmpty()
                    },
                    needsKeySetup = uiState.needsKeySetup,
                    onRetry = {
                        // 验收审计 D3:麦克风被拒时的 Retry 必须重新拉起系统权限请求,
                        // 而不是直接重拨 —— 权限没批时重拨只会在采集处再次失败,
                        // 界面就成了「点 Retry → 同样失败」的死循环。权限批下后
                        // 启动器回调自己会发起通话;连接类失败才走控制器真重拨。
                        if (micDenied) {
                            micDenied = false
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            controller.retry()
                        }
                    },
                    onOpenKeySettings = onOpenKeySettings,
                    onClose = handleClose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = MusePaddings.screen),
                )
            } else {
                // ── 字幕:逐行流式 ───────────────────────────────────
                LiveCallTranscript(
                    lines = uiState.transcript,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = MusePaddings.screen),
                )
            }

            // ── 底部控制:静音 / 挂断 ────────────────────────────────
            if (!showErrorPanel && uiState.phase != LiveCallPhase.ENDED) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = MusePaddings.sectionGap),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuteButton(
                        muted = uiState.muted,
                        onClick = { controller.toggleMute() },
                    )
                    Spacer(Modifier.width(36.dp))
                    EndCallButton(onClick = {
                        controller.endCall()
                        onClose()
                    })
                }
            }
        }
    }
}

@Composable
private fun LiveCallOrb(
    phase: LiveCallPhase,
    audioLevel: Float,
    muted: Boolean,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val reducedMotion = MuseMotion.isReducedMotion()
    val breatheTransition = rememberInfiniteTransition(label = "liveCallOrbBreathe")
    val breatheAlpha by breatheTransition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.60f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveCallOrbBreatheAlpha",
    )
    // 通话中辉光跟随音量;连接中用呼吸动画;其余状态保持安静的呼吸。
    val glowAlpha = when {
        reducedMotion -> 0.45f
        phase == LiveCallPhase.LIVE -> 0.25f + audioLevel.coerceIn(0f, 1f) * 0.55f
        else -> breatheAlpha
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(224.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            primary.copy(alpha = glowAlpha),
                            primary.copy(alpha = 0f),
                        ),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .size(190.dp)
                .clip(CircleShape)
                .border(1.5.dp, LiveCallRed, CircleShape),
        )
        Box(
            modifier = Modifier
                .size(178.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(3.dp, primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MuseIcons.microphone,
                contentDescription = null,
                tint = if (muted) MaterialTheme.colorScheme.error else primary,
                modifier = Modifier.size(54.dp),
            )
        }
    }
}

@Composable
private fun CallStatusLine(uiState: LiveCallController.UiState) {
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val connectedAt = uiState.connectedAtMillis
    LaunchedEffect(connectedAt) {
        while (connectedAt != null) {
            nowMillis = System.currentTimeMillis()
            delay(500)
        }
    }
    val statusText = when {
        uiState.phase == LiveCallPhase.ENDED -> stringResource(R.string.live_call_ended)
        uiState.phase == LiveCallPhase.CONNECTING -> stringResource(R.string.live_call_connecting)
        uiState.phase == LiveCallPhase.LIVE && uiState.modelSpeaking ->
            stringResource(R.string.live_call_speaking)
        uiState.phase == LiveCallPhase.LIVE -> stringResource(R.string.live_call_listening)
        else -> ""
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (statusText.isNotEmpty()) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = MusePaddings.contentGap),
            )
        }
        if (connectedAt != null && uiState.phase == LiveCallPhase.LIVE) {
            val elapsedSec = ((nowMillis - connectedAt) / 1000).coerceAtLeast(0)
            Text(
                text = "%d:%02d".format(elapsedSec / 60, elapsedSec % 60),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (uiState.muted && uiState.phase == LiveCallPhase.LIVE) {
            Text(
                text = stringResource(R.string.live_call_muted_label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun LiveCallTranscript(
    lines: List<LiveTranscriptLine>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(lines.lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        items(lines) { line ->
            val isUser = line.speaker == LiveSpeaker.USER
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(
                        if (isUser) R.string.live_call_speaker_you else R.string.live_call_speaker_shaheen,
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (isUser) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}

@Composable
private fun LiveCallErrorPanel(
    message: String,
    needsKeySetup: Boolean,
    onRetry: () -> Unit,
    onOpenKeySettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 全文展示,不截断 —— 旧版横幅只给两行是 Malik 骂过的点之一。
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(MusePaddings.sectionGap))
        Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
            if (needsKeySetup) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MuseShapes.pill,
                    modifier = Modifier.clickable { onOpenKeySettings() },
                ) {
                    Text(
                        text = stringResource(R.string.live_call_setup_key),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    )
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = MuseShapes.pill,
                    modifier = Modifier.clickable { onRetry() },
                ) {
                    Text(
                        text = stringResource(R.string.live_call_retry),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    )
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MuseShapes.pill,
                modifier = Modifier.clickable { onClose() },
            ) {
                Text(
                    text = stringResource(R.string.live_call_close),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun MuteButton(muted: Boolean, onClick: () -> Unit) {
    val cd = stringResource(
        if (muted) R.string.live_call_unmute_cd else R.string.live_call_mute_cd,
    )
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(
                if (muted) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceContainer,
            )
            .clickable { onClick() }
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MuseIcons.microphone,
            contentDescription = null,
            tint = if (muted) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(26.dp),
        )
        if (muted) {
            // 静音斜杠(图标库没有 mic-off,用一条旋转线表达)。
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(2.5.dp)
                    .rotate(45f)
                    .background(MaterialTheme.colorScheme.error),
            )
        }
    }
}

@Composable
private fun EndCallButton(onClick: () -> Unit) {
    val cd = stringResource(R.string.live_call_end_cd)
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(LiveCallRed)
            .clickable { onClick() }
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MuseIcons.phone,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .size(30.dp)
                .rotate(135f),
        )
    }
}
