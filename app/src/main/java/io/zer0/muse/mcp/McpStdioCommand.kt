package io.zer0.muse.mcp

import android.content.Context
import io.zer0.muse.runtime.MuseRuntime
import java.io.File

/**
 * P1-A: stdio MCP server 命令解析器。
 *
 * 把用户配置的命令行（如 `npx -y @modelcontextprotocol/server-filesystem /sdcard`）
 * 转换为可直接执行的 argv：
 *  - `node` / `npm` / `npx` → 应用内置运行时（[MuseRuntime]）
 *  - 绝对路径（仅限应用私有目录：nativeLibraryDir / 运行时目录）→ 原样透传
 *  - 其他命令一律拒绝（沙盒内只允许运行内置运行时承载的 Node 系程序）
 */
object McpStdioCommand {

    sealed class Result {
        /** 可直接交给 ProcessBuilder 的 argv（argv[0] 为绝对路径）。 */
        data class Ok(val argv: List<String>) : Result()

        /** 解析失败（命令为空 / 不支持 / 越界路径）。 */
        data class Error(val message: String) : Result()
    }

    /** 空白分词，支持双引号包裹（保留引号内空格）。 */
    fun tokenize(commandLine: String): List<String> {
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuote = false
        for (ch in commandLine.trim()) {
            when {
                ch == '"' -> inQuote = !inQuote
                ch.isWhitespace() && !inQuote -> {
                    if (sb.isNotEmpty()) {
                        tokens.add(sb.toString())
                        sb.clear()
                    }
                }
                else -> sb.append(ch)
            }
        }
        if (sb.isNotEmpty()) tokens.add(sb.toString())
        return tokens
    }

    /** 解析命令行 → 可执行 argv。 */
    fun resolve(commandLine: String, context: Context): Result {
        val tokens = tokenize(commandLine)
        if (tokens.isEmpty()) return Result.Error("Command is empty")
        val head = tokens[0]
        val rest = tokens.drop(1)
        val node = MuseRuntime.nodeBinary(context).absolutePath
        return when {
            head == "node" -> Result.Ok(listOf(node) + rest)
            head == "npm" -> Result.Ok(
                listOf(node, MuseRuntime.npmCli(context).absolutePath) + rest,
            )
            head == "npx" -> Result.Ok(
                listOf(node, File(MuseRuntime.npmDir(context), "bin/npx-cli.js").absolutePath) + rest,
            )
            head.startsWith("/") -> {
                // 绝对路径：仅允许应用私有目录内的程序（防越权执行任意系统/用户文件）
                val f = File(head)
                val allowed = runCatching {
                    f.canonicalPath.startsWith(MuseRuntime.nativeLibDir(context).canonicalPath) ||
                        f.canonicalPath.startsWith(MuseRuntime.runtimeDir(context).canonicalPath)
                }.getOrDefault(false)
                if (allowed && f.isFile) {
                    Result.Ok(tokens)
                } else {
                    Result.Error("Only program paths inside the app runtime are allowed")
                }
            }
            else -> Result.Error(
                "Unsupported command '$head' (currently supports node / npm / npx or an absolute path inside the app runtime)",
            )
        }
    }
}
