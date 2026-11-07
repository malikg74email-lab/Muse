package io.zer0.muse.automation.appcontrol

import java.net.URLEncoder

/**
 * Shaheen 2 — Deep Links & Shortcuts（Malik 的 phone-side architecture §3 / §5a）。
 *
 * 纯逻辑层（无 Android 类型，可单测）：一个**精选过的** deep-link 注册表，
 * 把"打开某应用里的某个具体页面"解析为一个可验证的目标：
 *  - YouTube：具体视频（id 或任意 YouTube 链接）/ 搜索 / 订阅页；
 *  - YouTube Studio：具体视频的编辑页（见该条目的核实状态说明）；
 *  - WhatsApp：按号码打开聊天（官方 wa.me Click-to-Chat）；
 *  - Facebook：主页 / 页面 / 帖子；
 *  - 通用 https 链接兜底。
 *
 * ## 核实纪律
 *
 * 每条链接都带 [DeepLinkBasis]：
 *  - [DeepLinkBasis.DOCUMENTED] — 平台官方文档明确给出的格式（如 wa.me）；
 *  - [DeepLinkBasis.STANDARD_WEB] — 平台自身的标准网页 URL，对应应用会
 *    拦截打开（YouTube / Facebook 的 https 链接一直如此）；
 *  - [DeepLinkBasis.UNVERIFIED] — 没有查到官方 deep-link 文档，**不编造**
 *    scheme；用的是网页端真实存在的 URL，能否被应用拦截未经证实——
 *    工具层会验证落点，落不到目标应用就如实报告并走 fallback。
 *
 * ## 执行顺序（由 AndroidAppController.openDeepLink 落实）
 *
 * deep link 先行 → 验证落点（前台包名 + 可选的屏幕期望文本）→
 * 失败才 fallback 到 app_launch + 无障碍导航提示。任何一步都不许假报成功。
 */

/** 一条 deep link 的依据等级（见类注释的核实纪律）。 */
enum class DeepLinkBasis {
    /** 平台官方文档明确给出。 */
    DOCUMENTED,

    /** 平台标准网页 URL，应用会拦截。 */
    STANDARD_WEB,

    /** 无官方文档，未证实；只用网页端真实 URL，不编造 scheme。 */
    UNVERIFIED,
}

/** 一个已解析的 deep-link 目标（纯数据）。 */
data class DeepLinkTarget(
    /** 注册表内的应用键：youtube / youtube_studio / whatsapp / facebook / url。 */
    val appKey: String,
    /** 给人看的应用名。 */
    val appLabel: String,
    /** 人读的目标描述（如 "video dQw4w9WgXcQ on YouTube"），用于结果报告。 */
    val targetLabel: String,
    /** 意图应定向到的包名；null 表示不定向（通用链接，落到哪个应用算哪个）。 */
    val packageName: String?,
    /** 要打开的完整 URI。 */
    val uri: String,
    /** 链接依据等级。 */
    val basis: DeepLinkBasis,
    /** 依据说明（UNVERIFIED 时必须写清未证实什么）。 */
    val basisNote: String,
    /** 落地后应在屏幕上出现的文本（可 null 表示无从校验，跳过该项检查）。 */
    val screenHint: String?,
    /** deep link 失败、退回普通启动后，给模型的无障碍导航提示。 */
    val fallbackNavHint: String,
)

/** 解析结果：要么得到可执行目标，要么得到一句人话拒绝原因（绝不猜一个链接出来）。 */
sealed interface DeepLinkResolution {
    data class Resolved(val target: DeepLinkTarget) : DeepLinkResolution
    data class Rejected(val reason: String) : DeepLinkResolution
}

/**
 * 打开类动作的重试记账（architecture §6a：慢启动先有界重试，再如实报告）。
 *
 * 上限对 app_launch 与 deep link 统一：总共 [MAX_ATTEMPTS] 次
 * （首次 + 2 次重试），每次尝试后用 [VERIFY_WAITS_MS] 轮询前台。
 */
object OpenRetryPolicy {
    /** 总尝试上限（含首次）。 */
    const val MAX_ATTEMPTS = 3

    /** 每次尝试后轮询前台包名的等待梯度（毫秒，总计约 2.2 秒，留给慢启动）。 */
    val VERIFY_WAITS_MS: LongArray = longArrayOf(400L, 700L, 1_100L)

    /** 是否还要再试：已落地永不再试；次数没用完才继续。 */
    fun shouldRetry(attemptsUsed: Int, landed: Boolean): Boolean =
        !landed && attemptsUsed in 1 until MAX_ATTEMPTS

    /** 报告里标注尝试次数（首次成功不啰嗦，超过一次必须说明白）。 */
    fun attemptsSuffix(attempts: Int): String =
        if (attempts > 1) " after $attempts attempts" else ""
}

/** deep link 打开的最终结局（纯数据，工具层据此渲染报告）。 */
sealed interface DeepLinkOutcome {
    /** 链接在哪一跳落地的。 */
    enum class Via { DEEP_LINK, WEB_LINK }

    /** 目标应用已确认在前台（[hintMatched] null = 无期望文本可查）。 */
    data class Landed(
        val via: Via,
        val attempts: Int,
        val hintMatched: Boolean?,
    ) : DeepLinkOutcome

    /** 意图发出成功，但没有任何通道能观测前台——只能报"已打开，未证实"。 */
    data class LandedUnverified(val via: Via, val attempts: Int) : DeepLinkOutcome

    /** 通用链接（无定向包名）已打开，落点是 [foregroundPackage]（可能为 null）。 */
    data class OpenedUnpinned(val foregroundPackage: String?, val attempts: Int) : DeepLinkOutcome

    /**
     * 链接没落进目标应用（[linkLandedIn] 记录它实际落到的前台包，如浏览器），
     * 已退回普通 app_launch，[launch] 是启动的完整结果，[navHint] 是后续导航提示。
     */
    data class FellBackToAppLaunch(
        val attempts: Int,
        val linkLandedIn: String?,
        val launch: AppActionResult,
        val navHint: String,
    ) : DeepLinkOutcome

    /** 什么都没打开成，[reason] 是人话原因。 */
    data class Failed(val attempts: Int, val reason: String) : DeepLinkOutcome
}

/** 把 [DeepLinkOutcome] 渲染成给模型看的英文报告（口径与 renderAppList 一致：纯函数、英文、如实）。 */
object DeepLinkReport {
    fun render(
        target: DeepLinkTarget,
        outcome: DeepLinkOutcome,
        launchDescription: String? = null,
    ): String = when (outcome) {
        is DeepLinkOutcome.Landed -> buildString {
            append("Opened ${target.targetLabel} via ")
            append(if (outcome.via == DeepLinkOutcome.Via.DEEP_LINK) "deep link" else "web link")
            append(" — verified: ${target.packageName} is in the foreground")
            append(OpenRetryPolicy.attemptsSuffix(outcome.attempts))
            append(".")
            when (outcome.hintMatched) {
                true -> append(" The expected text is on screen.")
                false -> append(" Note: the expected text was NOT found on screen — check with screen_read before acting.")
                null -> Unit
            }
            if (target.basis == DeepLinkBasis.UNVERIFIED) {
                append(" (This link pattern is unverified — it landed this time.)")
            }
        }

        is DeepLinkOutcome.LandedUnverified ->
            "Opened ${target.targetLabel} via " +
                (if (outcome.via == DeepLinkOutcome.Via.DEEP_LINK) "deep link" else "web link") +
                "${OpenRetryPolicy.attemptsSuffix(outcome.attempts)}, but the foreground could not be verified " +
                "by any channel — report it as opened, not confirmed; check with screen_read."

        is DeepLinkOutcome.OpenedUnpinned ->
            "Opened the link ${target.uri}" +
                OpenRetryPolicy.attemptsSuffix(outcome.attempts) +
                " — it landed in ${outcome.foregroundPackage ?: "an app that could not be identified"}. " +
                "No specific app was targeted, so there is no in-app landing to verify."

        is DeepLinkOutcome.FellBackToAppLaunch -> buildString {
            append("The deep link to ${target.targetLabel} did not land in ${target.appLabel}")
            append(OpenRetryPolicy.attemptsSuffix(outcome.attempts))
            outcome.linkLandedIn?.let { append(" (the link itself landed in $it)") }
            append(". Fell back to launching the app: ")
            append(launchDescription ?: outcome.launch.toString())
            append(". Next step: ")
            append(outcome.navHint)
        }

        is DeepLinkOutcome.Failed ->
            "Could not open ${target.targetLabel}" +
                OpenRetryPolicy.attemptsSuffix(outcome.attempts) +
                ": ${outcome.reason} Nothing was opened."
    }
}

/** 精选 deep-link 注册表：只收录核实过的条目，未证实的明确标注。 */
object DeepLinkRegistry {
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val YOUTUBE_STUDIO_PACKAGE = "com.google.android.apps.youtube.creator"
    const val WHATSAPP_PACKAGE = "com.whatsapp"
    const val FACEBOOK_PACKAGE = "com.facebook.katana"

    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val FACEBOOK_HANDLE = Regex("^[A-Za-z0-9.]{3,64}$")
    private val FACEBOOK_POST = Regex("^[A-Za-z0-9_-]{3,64}$")

    private const val YOUTUBE_NAV_HINT =
        "use screen_read to see the screen, tap Search, search for the video, then tap it in the results."
    private const val STUDIO_NAV_HINT =
        "use screen_read to see the screen, open the Content / Videos list, find the video, then tap its edit (pencil) action."
    private const val WHATSAPP_NAV_HINT =
        "use screen_read to see the screen, use the chat-list search (or the new-chat button) to find the contact, then tap the chat."
    private const val FACEBOOK_NAV_HINT =
        "use screen_read to see the screen, then use the app's search or menu to reach the page or post."
    private const val GENERIC_NAV_HINT = "navigate from here with screen_read / screen_tap_text."

    /**
     * 工具层入口：按应用键 + 参数表解析。
     *
     * @param app youtube | youtube_studio（或 studio）| whatsapp | facebook | url
     * @param params video / query / page / phone / handle / post / url / package（按应用取用）
     */
    fun resolve(app: String, params: Map<String, String>): DeepLinkResolution =
        when (app.trim().lowercase()) {
            "youtube" -> resolveYouTube(params)
            "youtube_studio", "studio", "yt_studio" -> resolveYouTubeStudio(params)
            "whatsapp", "wa" -> resolveWhatsApp(params)
            "facebook", "fb" -> resolveFacebook(params)
            "url", "generic", "link" -> resolveGenericUrl(params)
            else -> DeepLinkResolution.Rejected(
                "unknown app '$app' — supported: youtube, youtube_studio, whatsapp, facebook, url",
            )
        }

    // ── YouTube ──────────────────────────────────────────────────

    private fun resolveYouTube(params: Map<String, String>): DeepLinkResolution {
        val video = params["video"]?.trim().orEmpty()
        val query = params["query"]?.trim().orEmpty()
        val page = params["page"]?.trim()?.lowercase().orEmpty()
        return when {
            video.isNotEmpty() -> {
                val id = extractYouTubeVideoId(video)
                    ?: return DeepLinkResolution.Rejected(
                        "'$video' is not a recognisable YouTube video id or URL",
                    )
                DeepLinkResolution.Resolved(
                    DeepLinkTarget(
                        appKey = "youtube",
                        appLabel = "YouTube",
                        targetLabel = "video $id on YouTube",
                        packageName = YOUTUBE_PACKAGE,
                        uri = "https://www.youtube.com/watch?v=$id",
                        basis = DeepLinkBasis.STANDARD_WEB,
                        basisNote = "Canonical watch URL; the YouTube app intercepts youtube.com links.",
                        screenHint = null,
                        fallbackNavHint = YOUTUBE_NAV_HINT,
                    ),
                )
            }

            query.isNotEmpty() -> DeepLinkResolution.Resolved(
                DeepLinkTarget(
                    appKey = "youtube",
                    appLabel = "YouTube",
                    targetLabel = "YouTube search for \"$query\"",
                    packageName = YOUTUBE_PACKAGE,
                    uri = "https://www.youtube.com/results?search_query=" +
                        URLEncoder.encode(query, Charsets.UTF_8.name()),
                    basis = DeepLinkBasis.STANDARD_WEB,
                    basisNote = "Canonical results URL; the YouTube app intercepts youtube.com links.",
                    screenHint = query,
                    fallbackNavHint = YOUTUBE_NAV_HINT,
                ),
            )

            page == "subscriptions" -> DeepLinkResolution.Resolved(
                DeepLinkTarget(
                    appKey = "youtube",
                    appLabel = "YouTube",
                    targetLabel = "YouTube subscriptions",
                    packageName = YOUTUBE_PACKAGE,
                    uri = "https://www.youtube.com/feed/subscriptions",
                    basis = DeepLinkBasis.STANDARD_WEB,
                    basisNote = "Canonical feed URL; the YouTube app intercepts youtube.com links.",
                    screenHint = null,
                    fallbackNavHint = "use screen_read to see the screen, then tap the Subscriptions tab at the bottom.",
                ),
            )

            else -> DeepLinkResolution.Rejected(
                "for youtube, provide one of: video (id or URL), query (search text), page=subscriptions",
            )
        }
    }

    /** 从裸 id / watch URL / youtu.be / shorts / live / embed 链接里提取 11 位视频 id。 */
    fun extractYouTubeVideoId(input: String): String? {
        val s = input.trim()
        if (VIDEO_ID.matches(s)) return s
        // 负向先行断言：id 后面不能再跟 id 字符，否则 12 位的畸形值会被截成 11 位误收
        val bounded = "([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])"
        Regex("youtu\\.be/$bounded").find(s)?.let { return it.groupValues[1] }
        Regex("(?:shorts|live|embed)/$bounded").find(s)?.let { return it.groupValues[1] }
        Regex("[?&]v=$bounded").find(s)?.let { return it.groupValues[1] }
        return null
    }

    // ── YouTube Studio ───────────────────────────────────────────

    private fun resolveYouTubeStudio(params: Map<String, String>): DeepLinkResolution {
        val video = params["video"]?.trim().orEmpty()
        if (video.isEmpty()) {
            return DeepLinkResolution.Rejected("for youtube_studio, provide video (id or URL)")
        }
        val id = extractYouTubeVideoId(video)
            ?: return DeepLinkResolution.Rejected("'$video' is not a recognisable YouTube video id or URL")
        return DeepLinkResolution.Resolved(
            DeepLinkTarget(
                appKey = "youtube_studio",
                appLabel = "YouTube Studio",
                targetLabel = "the edit page of video $id in YouTube Studio",
                packageName = YOUTUBE_STUDIO_PACKAGE,
                uri = "https://studio.youtube.com/video/$id/edit",
                basis = DeepLinkBasis.UNVERIFIED,
                basisNote = "The studio.youtube.com/video/<id>/edit URL is real on the web, but no official " +
                    "documentation confirms the Studio app intercepts it (checked 2026-11-08). The open is " +
                    "verified live: if it lands in a browser instead of the Studio app, that is reported " +
                    "honestly and the flow falls back to launching the Studio app.",
                screenHint = null,
                fallbackNavHint = STUDIO_NAV_HINT,
            ),
        )
    }

    // ── WhatsApp ─────────────────────────────────────────────────

    private fun resolveWhatsApp(params: Map<String, String>): DeepLinkResolution {
        val raw = params["phone"]?.trim().orEmpty().ifEmpty { params["url"]?.trim().orEmpty() }
        if (raw.isEmpty()) {
            return DeepLinkResolution.Rejected("for whatsapp, provide phone (with country code) or a wa.me link")
        }
        val digits = normalizeWhatsAppPhone(raw)
            ?: return DeepLinkResolution.Rejected(
                "'$raw' is not a usable WhatsApp number — give the full international number " +
                    "(country code + number, no leading 0), or a wa.me link",
            )
        return DeepLinkResolution.Resolved(
            DeepLinkTarget(
                appKey = "whatsapp",
                appLabel = "WhatsApp",
                targetLabel = "a WhatsApp chat with +$digits",
                packageName = WHATSAPP_PACKAGE,
                uri = "https://wa.me/$digits",
                basis = DeepLinkBasis.DOCUMENTED,
                basisNote = "wa.me is WhatsApp's official Click-to-Chat format.",
                screenHint = null,
                fallbackNavHint = WHATSAPP_NAV_HINT,
            ),
        )
    }

    /** 把号码 / wa.me 链接 / api.whatsapp.com 链接规范化为纯国际格式数字。 */
    fun normalizeWhatsAppPhone(input: String): String? {
        var s = input.trim()
        val phoneParam = Regex("[?&]phone=([0-9+]+)").find(s)
        s = when {
            phoneParam != null -> phoneParam.groupValues[1]
            s.contains("wa.me/") -> s.substringAfter("wa.me/")
            else -> s
        }
        s = s.substringBefore('?').substringBefore('/').substringBefore('&')
        val digits = s.filter { it.isDigit() }
        if (digits.length !in 7..15) return null
        // wa.me 要求国际格式且不带前导 0；带 0 的多半是本地号，缺国家码，打开必然错号
        if (digits.startsWith("0")) return null
        return digits
    }

    // ── Facebook ─────────────────────────────────────────────────

    private fun resolveFacebook(params: Map<String, String>): DeepLinkResolution {
        val url = params["url"]?.trim().orEmpty()
        val handle = params["handle"]?.trim().orEmpty()
        val post = params["post"]?.trim().orEmpty()
        return when {
            url.isNotEmpty() -> {
                if (!isFacebookUrl(url)) {
                    DeepLinkResolution.Rejected("'$url' is not a facebook.com / fb.watch URL")
                } else {
                    DeepLinkResolution.Resolved(
                        DeepLinkTarget(
                            appKey = "facebook",
                            appLabel = "Facebook",
                            targetLabel = "a Facebook page ($url)",
                            packageName = FACEBOOK_PACKAGE,
                            uri = url,
                            basis = DeepLinkBasis.STANDARD_WEB,
                            basisNote = "Canonical facebook.com URL; the Facebook app intercepts its own links.",
                            screenHint = null,
                            fallbackNavHint = FACEBOOK_NAV_HINT,
                        ),
                    )
                }
            }

            handle.isNotEmpty() && post.isNotEmpty() -> {
                if (!FACEBOOK_HANDLE.matches(handle) || !FACEBOOK_POST.matches(post)) {
                    DeepLinkResolution.Rejected("facebook handle or post id has unexpected characters")
                } else {
                    DeepLinkResolution.Resolved(
                        DeepLinkTarget(
                            appKey = "facebook",
                            appLabel = "Facebook",
                            targetLabel = "a post by $handle on Facebook",
                            packageName = FACEBOOK_PACKAGE,
                            uri = "https://www.facebook.com/$handle/posts/$post",
                            basis = DeepLinkBasis.STANDARD_WEB,
                            basisNote = "Canonical post URL shape; the Facebook app intercepts its own links.",
                            screenHint = null,
                            fallbackNavHint = FACEBOOK_NAV_HINT,
                        ),
                    )
                }
            }

            handle.isNotEmpty() -> {
                if (!FACEBOOK_HANDLE.matches(handle)) {
                    DeepLinkResolution.Rejected("'$handle' is not a valid Facebook username or id")
                } else {
                    DeepLinkResolution.Resolved(
                        DeepLinkTarget(
                            appKey = "facebook",
                            appLabel = "Facebook",
                            targetLabel = "the Facebook profile/page '$handle'",
                            packageName = FACEBOOK_PACKAGE,
                            uri = "https://www.facebook.com/$handle",
                            basis = DeepLinkBasis.STANDARD_WEB,
                            basisNote = "Canonical profile/page URL; the Facebook app intercepts its own links.",
                            screenHint = null,
                            fallbackNavHint = FACEBOOK_NAV_HINT,
                        ),
                    )
                }
            }

            else -> DeepLinkResolution.Rejected(
                "for facebook, provide handle (profile/page), handle + post, or a full facebook url",
            )
        }
    }

    private fun isFacebookUrl(url: String): Boolean =
        Regex("^https://([a-z0-9-]+\\.)?(facebook\\.com|fb\\.watch)(/|$)").containsMatchIn(url)

    // ── 通用 https ───────────────────────────────────────────────

    private fun resolveGenericUrl(params: Map<String, String>): DeepLinkResolution {
        val url = params["url"]?.trim().orEmpty()
        if (url.isEmpty()) return DeepLinkResolution.Rejected("for app=url, provide a full https link in 'url'")
        if (!url.startsWith("https://") || !url.removePrefix("https://").contains('.')) {
            return DeepLinkResolution.Rejected("'$url' is not a valid https URL (only https links are opened)")
        }
        val pkg = params["package"]?.trim().orEmpty()
        if (pkg.isNotEmpty() && !AppControlCore.isValidPackageName(pkg)) {
            return DeepLinkResolution.Rejected("'$pkg' is not a valid package name")
        }
        return DeepLinkResolution.Resolved(
            DeepLinkTarget(
                appKey = "url",
                appLabel = pkg.ifEmpty { "the default handler" },
                targetLabel = "the link $url",
                packageName = pkg.ifEmpty { null },
                uri = url,
                basis = DeepLinkBasis.STANDARD_WEB,
                basisNote = "Plain https link; whichever app claims it (or the browser) opens it.",
                screenHint = null,
                fallbackNavHint = GENERIC_NAV_HINT,
            ),
        )
    }
}
