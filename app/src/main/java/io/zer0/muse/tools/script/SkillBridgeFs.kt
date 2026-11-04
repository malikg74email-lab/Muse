package io.zer0.muse.tools.script

import io.zer0.muse.tools.TOOL_OUTPUT_READ_PAGE_CHARS
import io.zer0.muse.tools.ToolTextFilePager
import java.io.File
import java.io.IOException

/**
 * v2.2.1 大沙盒:技能/插件桥接的沙盒文件系统操作 —— 全部路径锚定在调用方给定的沙盒根目录内。
 *
 * 安全:
 *  - 路径解析后做 canonical 包含校验,拒绝绝对路径与 `..` 上跳,符号链接逃逸同样被 canonical
 *    检查拦截;
 *  - 读/写均有体积上限,目录列表有数量上限;
 *  - 纯逻辑(不依赖 Android Framework),便于单元测试。
 */
internal object SkillBridgeFs {

    /** 大文件读取自动进入分页模式的阈值。 */
    const val MAX_READ_BYTES = 1 shl 20

    /** 单次写入上限 1MB。 */
    const val MAX_WRITE_BYTES = 1 shl 20

    /** 目录列表返回条目上限。 */
    const val MAX_LIST_ENTRIES = 200

    /** 沙盒文件操作失败(路径越界/类型不符/超限),信息可直接反馈给脚本调用方。 */
    class BridgeFsException(message: String) : IOException(message)

    /** 沙盒内条目(供 fs_list 序列化)。 */
    data class Entry(val name: String, val dir: Boolean, val size: Long)

    /**
     * 把沙盒内相对路径解析为绝对 [File];任何越界(绝对路径/`..`/符号链接逃逸)都抛异常。
     */
    fun resolve(root: File, path: String): File {
        val cleaned = path.trim().replace('\\', '/')
        if (cleaned.isEmpty()) return root.canonicalFile
        if (cleaned.startsWith("/") || cleaned.split('/').any { it == ".." }) {
            throw BridgeFsException("Illegal path (only sandbox-relative paths are allowed): $path")
        }
        val rootPath = root.canonicalFile
        val target = File(root, cleaned).canonicalFile
        val rootPrefix = rootPath.path + File.separator
        if (target != rootPath && !target.path.startsWith(rootPrefix)) {
            throw BridgeFsException("Path out of bounds: $path")
        }
        return target
    }

    /** 列目录(最多 [MAX_LIST_ENTRIES] 条)。 */
    fun list(root: File, path: String): List<Entry> {
        val dir = resolve(root, path)
        if (!dir.isDirectory) throw BridgeFsException("Not a directory: $path")
        return dir.listFiles().orEmpty()
            .sortedBy { it.name }
            .take(MAX_LIST_ENTRIES)
            .map { Entry(it.name, it.isDirectory, if (it.isFile) it.length() else 0L) }
    }

    /** 读文本文件(UTF-8);大文件按字符游标分页。 */
    fun read(root: File, path: String, offsetChars: Int? = null, lengthChars: Int? = null): String {
        val file = resolve(root, path)
        if (!file.isFile) throw BridgeFsException("Not a file: $path")
        if (offsetChars != null || lengthChars != null || file.length() > MAX_READ_BYTES) {
            return ToolTextFilePager.readPage(
                file = file,
                path = path,
                charset = Charsets.UTF_8,
                requestedOffset = offsetChars ?: 0,
                requestedLength = lengthChars ?: TOOL_OUTPUT_READ_PAGE_CHARS,
                continuationTool = "fs_read",
            )
        }
        return file.readText(Charsets.UTF_8)
    }

    /** 写文本文件(UTF-8,自动建目录,上限 [MAX_WRITE_BYTES]);返回写入字节数。 */
    fun write(root: File, path: String, content: String): Int {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_WRITE_BYTES) {
            throw BridgeFsException("Content exceeds the write limit (${MAX_WRITE_BYTES / 1024}KB)")
        }
        val file = resolve(root, path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return bytes.size
    }

    /** 删除文件或空目录;拒绝删除沙盒根目录。 */
    fun delete(root: File, path: String): Boolean {
        val file = resolve(root, path)
        if (file == root.canonicalFile) throw BridgeFsException("Deleting the sandbox root directory is not allowed")
        if (!file.exists()) throw BridgeFsException("Does not exist: $path")
        if (file.isDirectory && (file.listFiles()?.isNotEmpty() == true)) {
            throw BridgeFsException("Directory is not empty, cannot delete: $path")
        }
        return file.delete()
    }
}
