package io.zer0.muse.automation.appcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deep Links & Shortcuts 注册表测试（纯逻辑，无 Android 依赖）。
 *
 * 锁死三件事：每条链接的解析形状（含畸形参数必须被拒、绝不猜链接）、
 * 重试记账的边界、报告渲染的诚实口径（验证状态、尝试次数、fallback 提示）。
 */
class DeepLinkRegistryTest {

    private fun resolved(app: String, params: Map<String, String>): DeepLinkTarget {
        val r = DeepLinkRegistry.resolve(app, params)
        assertTrue("expected Resolved for $app $params, got $r", r is DeepLinkResolution.Resolved)
        return (r as DeepLinkResolution.Resolved).target
    }

    private fun rejected(app: String, params: Map<String, String>): String {
        val r = DeepLinkRegistry.resolve(app, params)
        assertTrue("expected Rejected for $app $params, got $r", r is DeepLinkResolution.Rejected)
        return (r as DeepLinkResolution.Rejected).reason
    }

    // ── YouTube 视频 id 提取 ─────────────────────────────────────

    @Test
    fun `youtube video id extraction accepts id and all url shapes`() {
        val id = "dQw4w9WgXcQ"
        assertEquals(id, DeepLinkRegistry.extractYouTubeVideoId(id))
        assertEquals(id, DeepLinkRegistry.extractYouTubeVideoId("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, DeepLinkRegistry.extractYouTubeVideoId("https://www.youtube.com/watch?v=$id&t=42s"))
        assertEquals(id, DeepLinkRegistry.extractYouTubeVideoId("https://youtu.be/$id"))
        assertEquals(id, DeepLinkRegistry.extractYouTubeVideoId("https://www.youtube.com/shorts/$id"))
        assertEquals(id, DeepLinkRegistry.extractYouTubeVideoId("https://www.youtube.com/live/$id?si=x"))
    }

    @Test
    fun `youtube video id extraction rejects garbage`() {
        assertNull(DeepLinkRegistry.extractYouTubeVideoId(""))
        assertNull(DeepLinkRegistry.extractYouTubeVideoId("not a video"))
        assertNull(DeepLinkRegistry.extractYouTubeVideoId("https://www.youtube.com/watch?v=tooshort"))
        assertNull(DeepLinkRegistry.extractYouTubeVideoId("https://example.com/watch?v=dQw4w9WgXcQX"))
    }

    // ── YouTube 条目 ────────────────────────────────────────────

    @Test
    fun `youtube video resolves to canonical watch url in the youtube app`() {
        val t = resolved("youtube", mapOf("video" to "https://youtu.be/dQw4w9WgXcQ"))
        assertEquals("com.google.android.youtube", t.packageName)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", t.uri)
        assertEquals(DeepLinkBasis.STANDARD_WEB, t.basis)
    }

    @Test
    fun `youtube search encodes the query and uses it as screen hint`() {
        val t = resolved("youtube", mapOf("query" to "info 6 documentary"))
        assertEquals("https://www.youtube.com/results?search_query=info+6+documentary", t.uri)
        assertEquals("info 6 documentary", t.screenHint)
    }

    @Test
    fun `youtube subscriptions resolves and bare youtube is rejected with guidance`() {
        val t = resolved("youtube", mapOf("page" to "subscriptions"))
        assertEquals("https://www.youtube.com/feed/subscriptions", t.uri)
        assertTrue(rejected("youtube", emptyMap()).contains("video"))
    }

    @Test
    fun `youtube rejects a malformed video param instead of guessing`() {
        assertTrue(rejected("youtube", mapOf("video" to "hello world")).contains("not a recognisable"))
    }

    // ── YouTube Studio 条目 ──────────────────────────────────────

    @Test
    fun `youtube studio video resolves to the web edit url marked unverified`() {
        val t = resolved("youtube_studio", mapOf("video" to "dQw4w9WgXcQ"))
        assertEquals("com.google.android.apps.youtube.creator", t.packageName)
        assertEquals("https://studio.youtube.com/video/dQw4w9WgXcQ/edit", t.uri)
        // 没有官方 deep-link 文档 → 必须标 UNVERIFIED，不许冒充已证实
        assertEquals(DeepLinkBasis.UNVERIFIED, t.basis)
        assertTrue(t.basisNote.contains("no official"))
    }

    // ── WhatsApp 条目 ────────────────────────────────────────────

    @Test
    fun `whatsapp phone normalization accepts international forms`() {
        assertEquals("923001234567", DeepLinkRegistry.normalizeWhatsAppPhone("+92 300 1234567"))
        assertEquals("923001234567", DeepLinkRegistry.normalizeWhatsAppPhone("92-300-1234567"))
        assertEquals("14155552671", DeepLinkRegistry.normalizeWhatsAppPhone("+1 (415) 555-2671"))
        assertEquals("923001234567", DeepLinkRegistry.normalizeWhatsAppPhone("https://wa.me/923001234567"))
        assertEquals(
            "923001234567",
            DeepLinkRegistry.normalizeWhatsAppPhone("https://api.whatsapp.com/send?phone=923001234567"),
        )
    }

    @Test
    fun `whatsapp phone normalization rejects local and short numbers`() {
        // 带前导 0 的本地号缺国家码，wa.me 必然开错 → 必须拒绝并说明
        assertNull(DeepLinkRegistry.normalizeWhatsAppPhone("0300 1234567"))
        assertNull(DeepLinkRegistry.normalizeWhatsAppPhone("12345"))
        assertNull(DeepLinkRegistry.normalizeWhatsAppPhone(""))
    }

    @Test
    fun `whatsapp resolves to the documented wa me url`() {
        val t = resolved("whatsapp", mapOf("phone" to "+92 300 1234567"))
        assertEquals("https://wa.me/923001234567", t.uri)
        assertEquals("com.whatsapp", t.packageName)
        assertEquals(DeepLinkBasis.DOCUMENTED, t.basis)
    }

    // ── Facebook 条目 ────────────────────────────────────────────

    @Test
    fun `facebook handle and post resolve to canonical urls`() {
        val profile = resolved("facebook", mapOf("handle" to "Info6Channel"))
        assertEquals("https://www.facebook.com/Info6Channel", profile.uri)
        assertEquals("com.facebook.katana", profile.packageName)

        val post = resolved("facebook", mapOf("handle" to "Info6Channel", "post" to "123456789012345"))
        assertEquals("https://www.facebook.com/Info6Channel/posts/123456789012345", post.uri)
    }

    @Test
    fun `facebook full url passes through only for facebook hosts`() {
        val url = "https://www.facebook.com/Info6Channel/posts/123456789012345"
        assertEquals(url, resolved("facebook", mapOf("url" to url)).uri)
        rejected("facebook", mapOf("url" to "https://example.com/Info6Channel/posts/1"))
    }

    @Test
    fun `facebook rejects malformed handles`() {
        rejected("facebook", mapOf("handle" to "bad handle!"))
        rejected("facebook", mapOf("handle" to "ab"))
        rejected("facebook", emptyMap())
    }

    // ── 通用链接与分发 ───────────────────────────────────────────

    @Test
    fun `generic url requires https and keeps an optional package target`() {
        val plain = resolved("url", mapOf("url" to "https://example.com/some/page"))
        assertNull(plain.packageName)

        val pinned = resolved(
            "url",
            mapOf("url" to "https://example.com/x", "package" to "com.example.app"),
        )
        assertEquals("com.example.app", pinned.packageName)

        rejected("url", mapOf("url" to "http://example.com/x"))
        rejected("url", mapOf("url" to "not-a-url"))
        rejected("url", mapOf("url" to "https://example.com/x", "package" to "bad;pkg"))
    }

    @Test
    fun `unknown app keys are rejected with the supported list`() {
        assertTrue(rejected("tiktok", emptyMap()).contains("youtube"))
    }

    // ── 重试记账 ────────────────────────────────────────────────

    @Test
    fun `retry policy is bounded at three attempts and stops once landed`() {
        assertEquals(3, OpenRetryPolicy.MAX_ATTEMPTS)
        assertTrue(OpenRetryPolicy.shouldRetry(attemptsUsed = 1, landed = false))
        assertTrue(OpenRetryPolicy.shouldRetry(attemptsUsed = 2, landed = false))
        assertFalse(OpenRetryPolicy.shouldRetry(attemptsUsed = 3, landed = false))
        assertFalse(OpenRetryPolicy.shouldRetry(attemptsUsed = 1, landed = true))
        assertFalse(OpenRetryPolicy.shouldRetry(attemptsUsed = 0, landed = false))
    }

    @Test
    fun `retry policy waits total a little over two seconds per attempt`() {
        assertEquals(2_200L, OpenRetryPolicy.VERIFY_WAITS_MS.sum())
    }

    // ── 报告渲染 ────────────────────────────────────────────────

    private val ytTarget = resolved("youtube", mapOf("video" to "dQw4w9WgXcQ"))

    @Test
    fun `report for a verified landing states verification and attempts`() {
        val text = DeepLinkReport.render(
            ytTarget,
            DeepLinkOutcome.Landed(DeepLinkOutcome.Via.DEEP_LINK, attempts = 2, hintMatched = null),
        )
        assertTrue(text.contains("verified: com.google.android.youtube is in the foreground"))
        assertTrue(text.contains("after 2 attempts"))
    }

    @Test
    fun `report for a landing with a missing hint warns instead of claiming`() {
        val text = DeepLinkReport.render(
            ytTarget,
            DeepLinkOutcome.Landed(DeepLinkOutcome.Via.DEEP_LINK, attempts = 1, hintMatched = false),
        )
        assertTrue(text.contains("NOT found on screen"))
        val ok = DeepLinkReport.render(
            ytTarget,
            DeepLinkOutcome.Landed(DeepLinkOutcome.Via.DEEP_LINK, attempts = 1, hintMatched = true),
        )
        assertTrue(ok.contains("expected text is on screen"))
        assertFalse(ok.contains("after"))
    }

    @Test
    fun `report for unverified landing never claims confirmation`() {
        val text = DeepLinkReport.render(
            ytTarget,
            DeepLinkOutcome.LandedUnverified(DeepLinkOutcome.Via.WEB_LINK, attempts = 1),
        )
        assertTrue(text.contains("could not be verified"))
        assertTrue(text.contains("not confirmed"))
    }

    @Test
    fun `report for fallback carries the launch description and the nav hint`() {
        val outcome = DeepLinkOutcome.FellBackToAppLaunch(
            attempts = 4,
            linkLandedIn = "com.android.chrome",
            launch = AppActionResult.Success(ExecutionChannel.APP, verifiedForeground = true),
            navHint = ytTarget.fallbackNavHint,
        )
        val text = DeepLinkReport.render(ytTarget, outcome, launchDescription = "Done: launch YouTube (via app permission)")
        assertTrue(text.contains("did not land in YouTube"))
        assertTrue(text.contains("com.android.chrome"))
        assertTrue(text.contains("after 4 attempts"))
        assertTrue(text.contains("Done: launch YouTube"))
        assertTrue(text.contains("Next step:"))
        assertTrue(text.contains("screen_read"))
    }

    @Test
    fun `report for failure states the reason and that nothing opened`() {
        val text = DeepLinkReport.render(
            ytTarget,
            DeepLinkOutcome.Failed(attempts = 4, reason = "YouTube (com.google.android.youtube) is not installed on this device."),
        )
        assertTrue(text.contains("Could not open"))
        assertTrue(text.contains("not installed"))
        assertTrue(text.contains("Nothing was opened."))
    }

    @Test
    fun `report for an unpinned open names where it landed`() {
        val generic = resolved("url", mapOf("url" to "https://example.com/x"))
        val text = DeepLinkReport.render(
            generic,
            DeepLinkOutcome.OpenedUnpinned(foregroundPackage = "com.android.chrome", attempts = 1),
        )
        assertTrue(text.contains("com.android.chrome"))
        assertTrue(text.contains("No specific app was targeted"))
    }
}
