package io.zer0.muse.tools.system

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.zer0.common.Logger
import java.io.File

/**
 * P3-3: 无障碍服务安装器(引导启用)。
 *
 * 现状(v2.2.1):
 *  - 应用内服务已编译进主 APK,「安装」实质是引导用户到系统设置启用;
 *  - 另有无障碍独立 Provider APK(io.zer0.muse.a11y),安装后其授权项独立于主应用生命周期。
 *
 * 职责:
 *  1. [isInstalled]: 应用内服务就绪(library 模块,恒为 true)
 *  2. [isProviderInstalled]: 独立 Provider APK 是否已安装(未装时仍可用应用内服务)
 *  3. [isEnabled]: 服务是否已在系统设置启用(委托 [AccessibilityClient.isEnabled],涵盖两份授权记录)
 *  4. [openSettings]: 跳转系统无障碍设置页
 *  5. [ensureEnabled]: 检查并引导启用,返回当前状态供 UI 展示
 */
class AccessibilityProviderInstaller(private val context: Context) {
    private val client = AccessibilityClient(context)

    companion object {
        private const val TAG = "A11yInstaller"

        /** 内置 Provider APK 在 assets 中的路径(app 构建时由 syncA11yProviderApk 拷贝)。 */
        private const val PROVIDER_ASSET_PATH = "a11y/accessibility-provider.apk"
    }

    /** 服务模块已编译进 APK,无需单独安装。 */
    fun isInstalled(): Boolean = true

    /** v2.2.1: 独立 Provider APK 是否已安装。 */
    fun isProviderInstalled(): Boolean = client.isProviderInstalled()

    /** v2.2.1: 独立 Provider APK 是否随包附带(assets 中存在)。 */
    fun isProviderApkBundled(): Boolean = runCatching {
        context.assets.open(PROVIDER_ASSET_PATH).close()
        true
    }.getOrDefault(false)

    /**
     * v2.2.1: 解出内置 Provider APK 并拉起系统安装器。
     *
     * 安装器由系统呈现,用户确认后完成安装(需 manifest 声明 REQUEST_INSTALL_PACKAGES)。
     * @return true 表示已成功拉起安装器;false 表示 APK 缺失或系统拒绝
     */
    fun installProvider(): Boolean {
        return try {
            val apkFile = File(context.cacheDir, "a11y/accessibility-provider.apk")
            apkFile.parentFile?.mkdirs()
            context.assets.open(PROVIDER_ASSET_PATH).use { input ->
                apkFile.outputStream().use { output -> input.copyTo(output) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // 必要容错:assets 缺失/FileProvider 未配置/系统拒绝拉起,均需反馈给 UI
            Logger.e(TAG, "Failed to launch the Provider installer: ${e.message}", e)
            false
        }
    }

    /** 无障碍服务是否已启用。 */
    fun isEnabled(): Boolean = client.isEnabled()

    /**
     * 引导用户启用无障碍服务(打开系统设置页)。
     * @return true 表示已跳转;false 表示无障碍设置页不可用
     */
    fun openSettings(): Boolean {
        return try {
            client.openAccessibilitySettings()
            true
        } catch (e: Exception) {
            // 必要容错:部分定制 ROM 可能没有标准无障碍设置页
            Logger.e(TAG, "Could not open the accessibility settings: ${e.message}", e)
            false
        }
    }

    /**
     * 确保无障碍服务已启用;未启用时返回引导提示。
     * @return [EnsureResult] 包含状态与提示信息
     */
    fun ensureEnabled(): EnsureResult {
        return if (isEnabled()) {
            EnsureResult.Enabled
        } else {
            EnsureResult.NeedsEnable
        }
    }

    sealed class EnsureResult {
        /** 服务已启用,可直接使用。 */
        object Enabled : EnsureResult()

        /** 服务未启用,需引导用户前往系统设置。 */
        object NeedsEnable : EnsureResult()
    }
}
