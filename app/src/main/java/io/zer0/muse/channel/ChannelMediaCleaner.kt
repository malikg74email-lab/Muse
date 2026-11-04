package io.zer0.muse.channel

import io.zer0.common.Logger
import java.io.File

/**
 * v2.x 遗留收尾:filesDir/channel_media/ 清理器。
 *
 * 背景:微信入站视频/文件落盘到 filesDir/channel_media/ 后,此目录此前无任何清理机制,
 * 长期使用会持续增长。消息对媒体的引用只存在于 [ChannelInbox] / [ChannelConversationStore]
 * (滚动保留、可能被清空),无法可靠判定"某文件是否仍被引用",因此采用保守策略:
 *  - TTL 兜底:最后修改早于 [DEFAULT_TTL_MS](30 天)的文件一律删除,足够长的冷却窗口防误删;
 *  - 体积上限:剩余文件总大小超过 [DEFAULT_MAX_TOTAL_BYTES] 时,按 LRU(最久未修改)淘汰到上限内。
 *
 * 两者取并集(任一命中即删)。启动时调用即可(见 MuseApp.onCreate)。
 *
 * [selectForDeletion] 为纯函数(便于单测),文件 IO 包装在 [cleanup]。
 */
object ChannelMediaCleaner {

    private const val TAG = "ChannelMediaCleaner"

    /**
     * TTL 兜底:30 天。
     *
     * 选 30 天而非更短,是因为无法判定消息引用关系 —— 足够长的 TTL 保证正常回看的旧媒体
     * 不被误删,只在明显"陈旧"时才回收。
     */
    const val DEFAULT_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** 目录总大小上限:512MB(约可容纳数十个大视频;超出后按 LRU 淘汰)。 */
    const val DEFAULT_MAX_TOTAL_BYTES: Long = 512L * 1024 * 1024

    /** 单文件元信息(清理判定输入)。 */
    data class FileMeta(val path: String, val lastModified: Long, val size: Long)

    /**
     * 纯函数:按策略选出待删除文件路径(不触碰文件系统,便于单测)。
     *
     * 1. TTL 轮:`now - lastModified > ttlMs` 的文件全部选中(剩余候选转入第 2 轮)。
     * 2. 体积轮:剩余候选按 `lastModified` 升序(最久未修改优先)累加,超 [maxTotalBytes] 的删除。
     *
     * @param files 当前目录下的文件元信息
     * @param now 当前时间戳(毫秒)
     * @param ttlMs TTL 阈值(毫秒)
     * @param maxTotalBytes 剩余文件总字节上限
     * @return 待删除文件路径列表(去重)
     */
    fun selectForDeletion(
        files: List<FileMeta>,
        now: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        protectedPaths: Set<String> = emptySet(),
    ): List<String> {
        if (files.isEmpty()) return emptyList()
        val toDelete = LinkedHashSet<String>()
        val remaining = ArrayList<FileMeta>(files.size)
        for (file in files) {
            if (file.path in protectedPaths) {
                continue
            }
            if (now - file.lastModified > ttlMs) {
                toDelete.add(file.path)
            } else {
                remaining.add(file)
            }
        }
        var total = 0L
        for (file in remaining) total += file.size
        if (total > maxTotalBytes) {
            for (file in remaining.sortedBy { it.lastModified }) {
                if (total <= maxTotalBytes) break
                toDelete.add(file.path)
                total -= file.size
            }
        }
        return toDelete.toList()
    }

    /**
     * 扫描 [dir] 下的文件,按 [selectForDeletion] 策略删除,返回删除数量。
     *
     * 目录不存在或读取失败时静默返回 0(不抛异常,避免影响启动流程)。
     */
    fun cleanup(
        dir: File,
        now: Long = System.currentTimeMillis(),
        ttlMs: Long = DEFAULT_TTL_MS,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        protectedPaths: Set<String> = emptySet(),
    ): Int {
        if (!dir.isDirectory) return 0
        val metas = dir.listFiles { f -> f.isFile }?.map {
            FileMeta(it.absolutePath, it.lastModified(), it.length())
        }.orEmpty()
        val victims = selectForDeletion(metas, now, ttlMs, maxTotalBytes, protectedPaths)
        var deleted = 0
        for (path in victims) {
            runCatching { if (File(path).delete()) deleted++ }
        }
        if (deleted > 0) Logger.i(TAG, "channel_media cleanup: deleted $deleted files")
        return deleted
    }
}
