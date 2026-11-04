package io.zer0.muse.tools

/**
 * P2-23: 媒体生成工具注册器(图片 / 视频 / 二维码)。
 *
 * 从 ChatViewModel.registerMediaTools() 原样迁出 —— ToolDef(name / description /
 * parameters / required / riskLevel / parameterTypes)一字未改,风险等级保持
 * generate_image / generate_video = HIGH、generate_qr_code = SAFE。
 *
 * 改为在 App 启动时由 [ToolRegistrarBootstrapper] 强制实例化注册,因此
 * 定时任务 / 群聊 / 子代理等无 UI 链路也能在 [ToolRegistry] 中查到并执行这些工具,
 * 不再依赖"用户打开过聊天页"。实现见 [MediaGenToolsImpl]。
 *
 * [impl] 由 Koin 注入(与 ChatViewModel 安装投递宿主的是同一实例)。
 */
class MediaGenToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val impl: MediaGenToolsImpl,
) {

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "generate_image",
                description = "Generate an image from the user's description. Call only when the user explicitly asks for drawing, design, an avatar, a poster, etc. Consumes drawing API quota.",
                parameters = mapOf(
                    "prompt" to "Required. Describe the subject/style/composition clearly; English works better, e.g. 'a cute cat on a sofa, watercolor style'",
                    "model" to "Optional, drawing model ID (e.g. dall-e-3 / gpt-image-1); default uses the provider's default model",
                    "size" to "Optional, size, e.g. 1024x1024 / 1792x1024 / 1024x1792; Agnes supports ratios 1:1 / 16:9 / 3:2",
                    "quality" to "Optional, e.g. standard / hd",
                    "style" to "Optional, e.g. vivid / natural",
                    "n" to "Optional, number to generate, default 1",
                    "reference_image" to "Optional, reference image URL or base64 (image-to-image/editing). Local reference images are injected after the user selects them on the approval card; the LLM does not need to fill this in",
                ),
                required = setOf("prompt"),
                riskLevel = ToolRiskLevel.HIGH,
                parameterTypes = mapOf("n" to "integer"),
            ),
        ) { args -> impl.execGenerateImage(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "generate_video",
                // v1.0.75 fix (工具审查 02): 补 prompt 示例与返回说明
                description = "Generate a short video from the user's description. Call only when the user explicitly asks for a video/animation. Automatically selects a provider/model that supports video output and returns the video URL.",
                parameters = mapOf(
                    "prompt" to "Required, video content description; English or Chinese are both fine",
                    "model" to "Optional, video model ID; when unspecified, automatically selects the first model that supports video output",
                    "provider_id" to "Optional, provider ID; when unspecified, automatically selects the first provider that supports video output",
                    "duration" to "Optional, video duration (seconds), only 5 or 10 supported, default 5",
                    "resolution" to "Optional, resolution, e.g. 720p / 1080p, default 720p",
                ),
                required = setOf("prompt"),
                riskLevel = ToolRiskLevel.HIGH,
                parameterTypes = mapOf("duration" to "integer"),
            ),
        ) { args -> impl.execGenerateVideo(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "generate_qr_code",
                description = "Convert arbitrary text (e.g. a link, a WiFi password, contact details) into a QR code image and display it in the conversation.",
                parameters = mapOf(
                    "content" to "Required, the text to encode into the QR code",
                    "size" to "Optional, QR code side length in pixels, default 400, range 128-1024",
                ),
                required = setOf("content"),
                riskLevel = ToolRiskLevel.SAFE,
                parameterTypes = mapOf("size" to "integer"),
            ),
        ) { args -> impl.execGenerateQrCode(args) }

        // 语音消息：本地合成 → 存成本地音频 → 挂到助手自己的消息上。
        // 风险 NORMAL 而不是 HIGH：不外发、不写用户目录，给 HIGH 会让每次发语音都弹审批卡。
        // 用带上下文的注册方式：本回合语音条数上限要按「回合」计，回合标识（助手消息 id）
        // 只能从宿主注入的 ToolExecutionContext 拿，模型不可伪造。
        toolRegistry.registerWithContext(
            ToolRegistry.ToolDef(
                name = "send_voice",
                // 描述里的「发语音 / 语音 / 朗读 / voice」都是 find_tools 的搜索词，别随便删
                description =
                "Send voice: synthesize a passage of text into a voice message (send voice / read aloud to the user), sent directly in the conversation." +
                    "Put only one passage in each voice message; one reply may call it multiple times, sent segment by segment in order — do not cram a whole article into one." +
                    "Only put the lines that need to be read aloud; do not put narration, action descriptions, English original text or Markdown." +
                    "Suited to tone, emotion and companionship content; descriptions, code and long texts should still be answered in text." +
                    "Consumes TTS quota; passages without Chinese will not generate voice; at most 5 per reply.",
                parameters = mapOf(
                    "text" to "Required, the plain text to read aloud (one passage, colloquial, no Markdown / code blocks / links)",
                    "emotion" to "Optional, emotional tone, e.g. happy / sad / calm / excited; leave empty to use the default emotion from settings",
                ),
                required = setOf("text"),
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args, executionContext ->
            impl.execSendVoice(args, executionContext.messageId)
        }
    }
}
