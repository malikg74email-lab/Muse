package io.zer0.muse.terminal

import io.zer0.common.Logger

/**
 * v2.x 终端二期:PTY 桥(native 自编译,见 src/main/jni/pty.c)。
 *
 * 全部 fd 语义走 native 函数(读写均不经 FileDescriptor),规避隐藏 API 限制。
 * [available] 在 native 库未就绪时为 false,调用方应回退管道引擎。
 */
object Pty {

    private const val TAG = "MusePty"

    /** native 库加载失败原因(诊断用;加载成功为 null)。 */
    val loadError: String? = runCatching {
        System.loadLibrary("pty")
        null
    }.getOrElse { t ->
        val message = "libpty.so loading failed: ${t.message}"
        runCatching { Logger.w(TAG, message) }
        message
    }

    /** native 库是否加载成功(NDK 未接入的构建里为 false)。 */
    val available: Boolean = loadError == null

    /**
     * 创建伪终端子进程。
     * @return 主端 fd(>=0);失败 -1。子进程 pid 写入 pidArray[0]。
     * @param extraPath P1-B: 追加到 PATH 首位的目录(运行时 bin);空串=不追加。
     * @param preloadLib P1-B: LD_PRELOAD 的 exec 垫片路径;空串=不设置。
     */
    external fun createSubprocess(execPath: String, cwd: String, pidArray: IntArray, extraPath: String, preloadLib: String): Int

    /** 阻塞等待子进程退出,返回退出码(信号终止 128+signo;失败 -1)。 */
    external fun waitFor(pid: Int): Int

    /** 从主端读取(阻塞);<=0 表示 EOF/错误。 */
    external fun read(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int

    /** 向主端写入;返回写入字节数(<0 出错)。 */
    external fun write(fd: Int, data: ByteArray, offset: Int, length: Int): Int

    /** 主端可读字节数;出错 -1。 */
    external fun available(fd: Int): Int

    /** 同步窗口尺寸(全屏程序排版用)。 */
    external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    /** 关闭主端 fd(用于终止会话)。 */
    external fun closeFd(fd: Int)

    /** 最近一次系统调用 errno(调试诊断用)。 */
    external fun errnoValue(): Int
}
