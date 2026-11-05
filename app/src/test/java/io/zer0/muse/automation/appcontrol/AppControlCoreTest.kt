package io.zer0.muse.automation.appcontrol

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.tools.system.ShizukuAuthorizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 应用控制的核心决策测试。
 *
 * 这一层是"Agent 能不能被信任"的关键：模型必须能从结果里**准确知道**
 * 「做成了 / 做不到以及缺哪一项能力 / 尝试了但失败」，而不是收到含糊的失败或假成功。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppControlCoreTest {

    private val noChannel = ChannelAvailability(shell = false, root = false)
    private val shellOnly = ChannelAvailability(shell = true, root = false)
    private val rootOnly = ChannelAvailability(shell = false, root = true)

    private fun app(
        pkg: String = "com.example.app",
        label: String = "示例",
        system: Boolean = false,
        self: Boolean = false,
    ): InstalledAppInfo {
        return InstalledAppInfo(packageName = pkg, label = label, versionName = "1.0", system = system, self = self)
    }

    // ── 包名校验 / 命令注入防护 ──────────────────────────────

    @Test
    fun `valid package names pass`() {
        assertTrue(AppControlCore.isValidPackageName("com.tencent.mm"))
        assertTrue(AppControlCore.isValidPackageName("a.b"))
        assertTrue(AppControlCore.isValidPackageName("com.example.app_2"))
    }

    @Test
    fun `shell metacharacters in package name are rejected`() {
        // 包名最终会拼进 `am force-stop <pkg>` 这类命令，任何可注入字符都必须被挡住
        listOf(
            "com.foo; rm -rf /",
            "com.foo && reboot",
            "com.foo|id",
            "com.foo\$(id)",
            "com.foo`id`",
            "com.foo\"x",
            "com.foo x",
            "com.foo\nid",
            "com",
        ).forEach { bad ->
            assertFalse("应拒绝: $bad", AppControlCore.isValidPackageName(bad))
        }
    }

    @Test
    fun `command builder refuses to be reached with invalid names`() {
        // buildCommand 只接受已过白名单的名字；这里锁定它拼出的命令形态不含额外参数
        assertEquals("am force-stop com.example.app", AppControlCore.buildCommand(AppControlAction.FORCE_STOP, "com.example.app"))
        assertEquals("pm clear com.example.app", AppControlCore.buildCommand(AppControlAction.CLEAR_DATA, "com.example.app"))
        assertEquals("pm uninstall com.example.app", AppControlCore.buildCommand(AppControlAction.UNINSTALL, "com.example.app"))
        // 应用层动作没有设备命令
        assertNull(AppControlCore.buildCommand(AppControlAction.LAUNCH, "com.example.app"))
        assertNull(AppControlCore.buildCommand(AppControlAction.OPEN_APP_SETTINGS, "com.example.app"))
    }

    // ── 通道降级决策 ────────────────────────────────────────

    @Test
    fun `invalid name is unavailable regardless of channels`() {
        val result = AppControlCore.evaluate(AppControlAction.LAUNCH, "bad name;", shellOnly)
        assertEquals(AppActionResult.Unavailable(UnavailableReason.INVALID_PACKAGE_NAME), result)
    }

    @Test
    fun `launch and settings need no elevated channel`() {
        assertEquals(
            AppActionResult.Success(ExecutionChannel.APP),
            AppControlCore.evaluate(AppControlAction.LAUNCH, "com.example.app", noChannel),
        )
        assertEquals(
            AppActionResult.Success(ExecutionChannel.APP),
            AppControlCore.evaluate(AppControlAction.OPEN_APP_SETTINGS, "com.example.app", noChannel),
        )
    }

    @Test
    fun `destructive actions without shell or root report the missing capability`() {
        listOf(
            AppControlAction.FORCE_STOP,
            AppControlAction.CLEAR_DATA,
            AppControlAction.UNINSTALL,
        ).forEach { action ->
            val result = AppControlCore.evaluate(action, "com.example.app", noChannel)
            assertEquals(
                "缺通道时必须明确回报缺少能力，而不是含糊失败: $action",
                AppActionResult.Unavailable(UnavailableReason.NEED_SHELL_OR_ROOT),
                result,
            )
        }
    }

    @Test
    fun `destructive actions prefer shell then root`() {
        assertEquals(
            AppActionResult.Success(ExecutionChannel.SHELL),
            AppControlCore.evaluate(AppControlAction.FORCE_STOP, "com.example.app", shellOnly),
        )
        assertEquals(
            AppActionResult.Success(ExecutionChannel.ROOT),
            AppControlCore.evaluate(AppControlAction.FORCE_STOP, "com.example.app", rootOnly),
        )
    }

    @Test
    fun `system apps can be force stopped but not cleared or uninstalled`() {
        val systemApp = app(system = true)
        assertEquals(
            AppActionResult.Success(ExecutionChannel.SHELL),
            AppControlCore.evaluate(AppControlAction.FORCE_STOP, "com.example.app", shellOnly, systemApp),
        )
        assertEquals(
            AppActionResult.Unavailable(UnavailableReason.BLOCKED_SYSTEM_APP),
            AppControlCore.evaluate(AppControlAction.CLEAR_DATA, "com.example.app", shellOnly, systemApp),
        )
        assertEquals(
            AppActionResult.Unavailable(UnavailableReason.BLOCKED_SYSTEM_APP),
            AppControlCore.evaluate(AppControlAction.UNINSTALL, "com.example.app", shellOnly, systemApp),
        )
    }

    @Test
    fun `muse itself can never be cleared or uninstalled even with root`() {
        val self = app(pkg = "io.zer0.muse", self = true)
        assertEquals(
            AppActionResult.Unavailable(UnavailableReason.BLOCKED_SELF),
            AppControlCore.evaluate(AppControlAction.CLEAR_DATA, "io.zer0.muse", ChannelAvailability(true, true), self),
        )
        assertEquals(
            AppActionResult.Unavailable(UnavailableReason.BLOCKED_SELF),
            AppControlCore.evaluate(AppControlAction.UNINSTALL, "io.zer0.muse", ChannelAvailability(true, true), self),
        )
    }

    // ── 名称匹配 ────────────────────────────────────────────

    @Test
    fun `match prefers exact package then label then partial label`() {
        val apps = listOf(
            app(pkg = "com.tencent.mm", label = "微信"),
            app(pkg = "com.tencent.mobileqq", label = "QQ"),
            app(pkg = "com.example.wechathelper", label = "微信助手"),
        )
        assertEquals("com.tencent.mm", AppControlCore.matchApp(apps, "com.tencent.mm")?.packageName)
        assertEquals("com.tencent.mm", AppControlCore.matchApp(apps, "微信")?.packageName)
        assertEquals("com.tencent.mobileqq", AppControlCore.matchApp(apps, "qq")?.packageName)
        // 精确名称优先于包含匹配（"微信" 不该命中 "微信助手"）
        assertEquals("com.tencent.mm", AppControlCore.matchApp(apps, "微信")?.packageName)
        assertEquals("com.example.wechathelper", AppControlCore.matchApp(apps, "助手")?.packageName)
        assertNull(AppControlCore.matchApp(apps, ""))
        assertNull(AppControlCore.matchApp(apps, "不存在的应用"))
    }

    // ── 列表渲染 ────────────────────────────────────────────

    @Test
    fun `render app list marks system and self and handles empty`() {
        val text = AppControlCore.renderAppList(
            listOf(
                app(pkg = "com.tencent.mm", label = "微信"),
                app(pkg = "android", label = "系统界面", system = true),
                app(pkg = "io.zer0.muse", label = "Muse", self = true),
            ),
        )
        assertTrue(text.contains("共 3 个可启动应用"))
        assertTrue(text.contains("微信 | com.tencent.mm"))
        assertTrue(text.contains("系统应用"))
        assertTrue(text.contains("本应用"))

        assertTrue(AppControlCore.renderAppList(emptyList()).contains("未找到"))
    }

    @Test
    fun `render app list respects the displayed limit but reports the real total`() {
        val apps = (1..5).map { app(pkg = "com.example.app$it", label = "App$it") }
        val text = AppControlCore.renderAppList(apps, limit = 2)
        assertTrue(text.contains("共 5 个可启动应用"))
        assertTrue(text.contains("仅列出前 2 个"))
        assertFalse(text.contains("com.example.app5"))
    }

    // ── 控制器：通道探测与非法输入 ───────────────────────────

    private fun controllerWith(shell: Boolean, root: Boolean): AndroidAppController {
        val state = AutomationManager.PermissionState(
            accessibilityEnabled = false,
            shellEnabled = shell,
            rootEnabled = root,
            shizukuState = if (shell) {
                ShizukuAuthorizer.ShizukuState.READY
            } else {
                ShizukuAuthorizer.ShizukuState.NOT_INSTALLED
            },
            shizukuMessage = "test",
        )
        val manager = mockk<AutomationManager>().apply {
            every { permissionState } returns MutableStateFlow(state)
            coEvery { refreshPermissions() } returns state
        }
        val context: Context = ApplicationProvider.getApplicationContext()
        return AndroidAppController(context, manager)
    }

    @Test
    fun `channels reflects probed availability`() = runTest {
        assertFalse(controllerWith(shell = false, root = false).channels().anyElevated)
        assertTrue(controllerWith(shell = true, root = false).channels().shell)
        assertTrue(controllerWith(shell = false, root = true).channels().root)
    }

    @Test
    fun `perform rejects injected package names before touching the system`() = runTest {
        val result = controllerWith(shell = true, root = false)
            .perform(AppControlAction.FORCE_STOP, "com.foo; reboot")
        // 既不是合法包名、也不是任何应用名 → 未找到（关键是不执行任何命令）
        assertTrue(result is AppActionResult.Unavailable)
    }

    @Test
    fun `perform reports missing channel for destructive action on unknown name`() = runTest {
        val result = controllerWith(shell = false, root = false)
            .perform(AppControlAction.CLEAR_DATA, "完全不存在的应用")
        assertEquals(
            AppActionResult.Unavailable(UnavailableReason.PACKAGE_NOT_FOUND),
            result,
        )
    }

    // ── Shaheen 2 Fix Phase 1 (§5 Fix 1.6): foreground verification ──────────

    @Test
    fun `foregroundVerdict verifies only when the target is observed`() {
        assertEquals(
            true,
            AppControlCore.foregroundVerdict("com.google.android.youtube", "com.google.android.youtube"),
        )
    }

    @Test
    fun `foregroundVerdict is unverified when nothing can be observed`() {
        assertEquals(false, AppControlCore.foregroundVerdict("com.google.android.youtube", null))
    }

    @Test
    fun `foregroundVerdict rejects a different foreground package`() {
        // Null ka matlab: launch kamyabi nahi — caller ko Failed report karna hai,
        // Success kabhi nahi (yehi "jhoot bolta hai" wali shikayat ki jad thi).
        assertNull(AppControlCore.foregroundVerdict("com.google.android.youtube", "io.zer0.muse"))
    }

    @Test
    fun `success defaults to no foreground check applied`() {
        assertNull(AppActionResult.Success(ExecutionChannel.APP).verifiedForeground)
    }
}
