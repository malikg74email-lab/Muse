package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.util.readZipEntryWithLimit
import kotlinx.serialization.decodeFromString
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * B6-01: 外部插件包加载器。
 *
 * 支持 `.muse-plugin` / ZIP 插件包：
 * ```
 * plugin.muse-plugin (ZIP)
 * ├── manifest.json   (必需，PluginManifest)
 * ├── main.js         (kind=tool/provider 时必需，JS 入口)
 * └── assets/         (可选，插件资源)
 * ```
 *
 * Phase 4 追加 `kind == "ui-skin"` 的声明式皮肤包：
 * ```
 * skin.muse-plugin (ZIP)
 * ├── manifest.json   (必需，kind="ui-skin"，capabilities 必须含 ui.skin，tools 必须为空)
 * └── skin.json       (必需，BubbleSkin JSON；包内不得出现任何 .js)
 * ```
 * 皮肤包只分发 JSON，不携带也不执行 JS；解析出的 [LoadedPluginPackage.skin] 由宿主
 * 校验/渲染，校验失败时 BubbleSkinResolver 回退内置 default。
 *
 * 安全限制：ZIP 炸弹防护、路径遍历拦截、入口文件必须存在。
 */
object PluginPackageLoader {

    private const val TAG = "PluginPackageLoader"

    private const val MAX_ENTRY_SIZE = 2L * 1024 * 1024
    private const val MAX_TOTAL_SIZE = 10L * 1024 * 1024
    private const val MANIFEST_ENTRY = "manifest.json"

    /** 声明式皮肤包中的皮肤资源路径(包根目录)。 */
    const val UI_SKIN_ENTRY = "skin.json"

    sealed class Result {
        data class Ok(val package_: LoadedPluginPackage) : Result()
        data class Err(val reason: String) : Result()
    }

    data class LoadedPluginPackage(
        val manifest: PluginManifest,
        val entryCode: String,
        val extraFiles: Map<String, String> = emptyMap(),
        /**
         * `kind == "ui-skin"` 包解析出的声明式皮肤；其他种类为 null。
         *
         * 这里只做 JSON 结构解析，schema/取值范围/对比度由宿主
         * [io.zer0.muse.ui.theme.BubbleSkinValidator] 与 resolver 在渲染前判定，
         * 判定失败时用户看到的仍是内置 default 气泡，消息正文不受影响。
         */
        val skin: io.zer0.muse.ui.theme.BubbleSkin? = null,
    )

    fun loadFromZip(zipBytes: ByteArray): Result {
        var totalSize = 0L
        var manifestJson: String? = null
        val allFiles = mutableMapOf<String, String>()

        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!isSafeRelativePath(name)) {
                        return Result.Err("Illegal path: $name")
                    }
                    if (entry.isDirectory) {
                        entry = zis.nextEntry
                        continue
                    }
                    // 接受的文件类型。.py 是 v2.7 起支持的插件运行时（内置 CPython 子进程）；
                    // 这里曾经只收 .js/.json/.md，于是 python 插件的入口文件在解包阶段
                    // 就被静默丢掉，后续报错却是「缺少入口文件」—— 根本原因在这里。
                    val lowerName = name.lowercase()
                    if (ACCEPTED_FILE_EXTENSIONS.none { lowerName.endsWith(it) }) {
                        entry = zis.nextEntry
                        continue
                    }

                    val bytes = readZipEntryWithLimit(zis, MAX_ENTRY_SIZE, name)
                    totalSize += bytes.size
                    if (totalSize > MAX_TOTAL_SIZE) {
                        return Result.Err("Total extracted size exceeds the limit (${MAX_TOTAL_SIZE / 1024 / 1024}MB)")
                    }

                    val content = bytes.toString(Charsets.UTF_8)
                    if (name == MANIFEST_ENTRY) {
                        manifestJson = content
                    } else {
                        allFiles[name] = content
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: IOException) {
            return Result.Err("ZIP read failed: ${e.message}")
        }

        val json = manifestJson ?: return Result.Err("Missing manifest.json")
        val manifest = runCatching {
            AppJson.decodeFromString<PluginManifest>(json)
        }.getOrElse { e ->
            return Result.Err("manifest.json parse failed: ${e.message}")
        }

        if (manifest.id.isBlank() || manifest.name.isBlank()) {
            return Result.Err("manifest.json is missing id/name")
        }
        if (!PLUGIN_ID_REGEX.matches(manifest.id)) {
            return Result.Err("Plugin id may only contain lowercase letters, digits, underscores and hyphens: ${manifest.id}")
        }
        if (manifest.tools.isEmpty() && manifest.kind != PluginSecurityGate.UI_SKIN_KIND) {
            return Result.Err("manifest.json declares no tools")
        }
        // 外部包没有作者签名和内置信任根，不能通过清单自报 full-access。
        if (manifest.trust != "sandboxed") {
            return Result.Err("External plugins must use the sandboxed trust level")
        }

        // ── Phase 4: 声明式 ui-skin 包(无 tools、无 JS,只分发皮肤 JSON) ──
        if (manifest.kind == PluginSecurityGate.UI_SKIN_KIND) {
            if (manifest.tools.isNotEmpty()) {
                return Result.Err("ui-skin plugins must not declare executable tools")
            }
            if (PluginSecurityGate.UI_SKIN_CAPABILITY !in manifest.capabilities) {
                return Result.Err("ui-skin plugins must declare the ${PluginSecurityGate.UI_SKIN_CAPABILITY} capability")
            }
            if (allFiles.keys.any { key -> FORBIDDEN_SKIN_EXTENSIONS.any { key.lowercase().endsWith(it) } }) {
                return Result.Err("ui-skin plugins must not contain JS files")
            }
            val skinJson = allFiles[UI_SKIN_ENTRY]
                ?: return Result.Err("ui-skin plugin is missing its skin resource: $UI_SKIN_ENTRY")
            val skin = runCatching {
                AppJson.decodeFromString<io.zer0.muse.ui.theme.BubbleSkin>(skinJson)
            }.getOrElse { e ->
                return Result.Err("$UI_SKIN_ENTRY parse failed: ${e.message}")
            }
            Logger.i(TAG, "Loaded ui-skin plugin package: ${manifest.id} v${manifest.version} (skin ${skin.id})")
            return Result.Ok(
                LoadedPluginPackage(
                    manifest = manifest,
                    // 声明式包没有 JS 入口;空串保证任何执行路径都拿不到可运行代码。
                    entryCode = "",
                    extraFiles = allFiles.toMap(),
                    skin = skin,
                ),
            )
        }

        // 入口扩展名按 manifest.runtime 判定：python 运行时要求 .py，其余（默认 js）要求 .js。
        // 两处必须一致 —— 解包白名单、入口校验、PluginSecurityGate.rejectionReason
        // 三处对「这个插件能不能跑」给出同一个答案，否则又会出现「能装上但跑不了」。
        val expectedExt = expectedEntryExtension(manifest)
        if (!isSafeRelativePath(manifest.entry) || !manifest.entry.lowercase().endsWith(expectedExt)) {
            return Result.Err(
                "Illegal entry file path in manifest.json (runtime=${manifest.runtime} requires a $expectedExt entry): ${manifest.entry}",
            )
        }

        val entryCode = allFiles.remove(manifest.entry)
            ?: return Result.Err("Missing entry file: ${manifest.entry}")

        Logger.i(TAG, "Loaded plugin package: ${manifest.id} v${manifest.version} (${manifest.tools.size} tools)")
        return Result.Ok(
            LoadedPluginPackage(
                manifest = manifest,
                entryCode = entryCode,
                extraFiles = allFiles.toMap(),
            ),
        )
    }

    /**
     * 从已安装目录重建的包中取出声明式皮肤(非 ZIP 路径)。
     *
     * 与 [loadFromZip] 的 ui-skin 分支保持同一套约束:只接受 `kind == "ui-skin"`、
     * 声明 [PluginSecurityGate.UI_SKIN_CAPABILITY]、无工具且不含任何 `.js` 的包。
     * 任一条件不满足或 `skin.json` 解析失败返回 null,调用方据此回退内置 default,
     * 不做任何降级解析,也不接触插件目录以外的文件。
     */
    fun extractUiSkin(pluginPackage: LoadedPluginPackage): io.zer0.muse.ui.theme.BubbleSkin? {
        val manifest = pluginPackage.manifest
        if (manifest.kind != PluginSecurityGate.UI_SKIN_KIND) return null
        if (manifest.tools.isNotEmpty()) return null
        if (PluginSecurityGate.UI_SKIN_CAPABILITY !in manifest.capabilities) return null
        if (pluginPackage.extraFiles.keys.any { key -> FORBIDDEN_SKIN_EXTENSIONS.any { key.lowercase().endsWith(it) } }) return null
        val skinJson = pluginPackage.extraFiles[UI_SKIN_ENTRY] ?: return null
        return runCatching {
            AppJson.decodeFromString<io.zer0.muse.ui.theme.BubbleSkin>(skinJson)
        }.getOrNull()
    }

    private fun isSafeRelativePath(path: String): Boolean {
        val normalized = path.trimEnd('/')
        return normalized.isNotBlank() &&
            !normalized.startsWith("/") &&
            !normalized.startsWith("\\") &&
            !normalized.contains("..") &&
            !normalized.contains('\\') &&
            normalized.split('/').none { it.isBlank() || it == "." }
    }

    private val PLUGIN_ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")

    /** 包内允许的文件类型。 */
    private val ACCEPTED_FILE_EXTENSIONS = listOf(".js", ".json", ".md", ".py")

    /** ui-skin 是声明式包，不得携带任何可执行代码。 */
    private val FORBIDDEN_SKIN_EXTENSIONS = listOf(".js", ".py")

    /** 按 manifest.runtime 得出入口文件应有的扩展名。 */
    private fun expectedEntryExtension(manifest: PluginManifest): String =
        if (manifest.runtime.equals("python", ignoreCase = true)) ".py" else ".js"
}
