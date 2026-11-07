package io.zer0.muse.runtime

/**
 * 运行时数据包的版本标记派生（纯逻辑，可单测）。
 *
 * ## 背景
 *
 * npm 版本自 2026-11-07 起在打包时从 Termux 索引动态解析（见
 * tools/runtime-pack/build-runtime-pack.sh），包内根部的 `VERSION.txt`
 * 由打包脚本从**真实的** `npm/package.json` 写出，内容形如 `npm 12.2.2`。
 *
 * 已装设备的重解压标记不能再写死某个版本：npm 浮动后写死值对不上，
 * 升级安装就不会重新解压，设备会残留旧 npm 数据。因此标记一律由
 * [markerFromVersionTxt] 从包内 VERSION.txt 派生；只有 VERSION.txt
 * 读不到时，[MuseRuntime] 才退回兜底常量。
 */
object RuntimeDataVersion {
    /** `npm <semver>`（允许 prerelease / build 后缀）。 */
    private val VERSION_TXT_FORMAT = Regex("""^npm\s+(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?)$""")

    /** assets zip 根部的版本文件名（打包脚本写出）。 */
    const val VERSION_TXT_ENTRY = "VERSION.txt"

    /**
     * 把 VERSION.txt 内容规范化为重解压标记：`npm 12.2.2` → `npm-12.2.2`
     * （与历史写死标记同格式，npm 未变的升级不会触发无谓重解压）。
     * 解析不了（null / 空 / 格式不符）返回 null，调用方据此走兜底。
     */
    fun markerFromVersionTxt(text: String?): String? {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val match = VERSION_TXT_FORMAT.matchEntire(trimmed) ?: return null
        return "npm-" + match.groupValues[1]
    }
}
