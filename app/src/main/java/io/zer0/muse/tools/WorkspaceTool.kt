package io.zer0.muse.tools

import io.zer0.muse.workspace.WorkspaceManager
import io.zer0.muse.workspace.WorkspaceMount
import kotlinx.coroutines.runBlocking

/**
 * 工作区文件管理工具集（AI 可调用）。
 *
 * 注册 10 个工具到 [ToolRegistry]：
 *  1. workspace_list   — 列出目录下的子项
 *  2. workspace_read   — 读取文本文件，大文件按字符游标分页
 *  3. workspace_write  — 写入文本文件（覆盖或追加）
 *  4. workspace_edit   — 把文件里的某段原文替换成另一段，回执带行级 diff
 *  5. workspace_delete — 删除文件或目录
 *  6. workspace_mkdir  — 创建目录
 *  7. workspace_move   — 移动或重命名
 *  8. workspace_copy   — 复制
 *  9. workspace_grep   — 按文本或正则搜索内容
 * 10. workspace_mounts — 列出可用挂载及其能力
 *
 * 路径写法：`挂载标识:/相对路径`。不写前缀时落到默认挂载，因此老调用照常可用。
 * 前缀只认已注册的挂载标识，普通文件名里出现冒号不会被误判。
 *
 * 安全：路径由 [WorkspaceManager] 解析，能力位（能否读/写）与挂载边界都在那里校验；
 * 工具层不自行拼路径，也不提供绕过校验的入口。
 */
@Suppress("TooManyFunctions")
object WorkspaceTool {

    /** workspace_list 工具名。 */
    const val NAME_LIST = "workspace_list"

    /** workspace_read 工具名。 */
    const val NAME_READ = "workspace_read"

    /** workspace_write 工具名。 */
    const val NAME_WRITE = "workspace_write"

    /** workspace_edit 工具名。 */
    const val NAME_EDIT = "workspace_edit"

    /** workspace_delete 工具名。 */
    const val NAME_DELETE = "workspace_delete"

    /** workspace_mkdir 工具名。 */
    const val NAME_MKDIR = "workspace_mkdir"

    /** workspace_move 工具名。 */
    const val NAME_MOVE = "workspace_move"

    /** workspace_copy 工具名。 */
    const val NAME_COPY = "workspace_copy"

    /** workspace_grep 工具名。 */
    const val NAME_GREP = "workspace_grep"

    /** workspace_mounts 工具名。 */
    const val NAME_MOUNTS = "workspace_mounts"

    /** 全部工具名（便于同步到 [ToolRegistry.BUILT_IN_TOOL_IDS]）。 */
    val ALL_TOOL_NAMES: List<String> = listOf(
        NAME_LIST,
        NAME_READ,
        NAME_WRITE,
        NAME_EDIT,
        NAME_DELETE,
        NAME_MKDIR,
        NAME_MOVE,
        NAME_COPY,
        NAME_GREP,
        NAME_MOUNTS,
    )

    /** 全部工具定义（注册到 ToolRegistry）。 */
    fun toolDefs(): List<ToolRegistry.ToolDef> = listOf(
        listToolDef(),
        readToolDef(),
        writeToolDef(),
        editToolDef(),
        deleteToolDef(),
        mkdirToolDef(),
        moveToolDef(),
        copyToolDef(),
        grepToolDef(),
        mountsToolDef(),
    )

    /** 路径参数说明，多个工具共用同一句话。 */
    private const val PATH_HINT = "Writable path: mountId:/relative/path; without a prefix it lands in the default workspace. Use workspace_mounts first to check the available mount ids."

    /** diff 回执里改动块前后各带几行上下文。 */
    private const val DIFF_CONTEXT_LINES = 3

    private fun listToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_LIST,
        description = "List the files and subdirectories under a workspace directory (directories first, then files)." +
            "Returns one entry per line: name | type(dir/file) | size | lastModified." + PATH_HINT,
        parameters = mapOf(
            "path" to "Optional, directory path; an empty string or \"/\" means the default workspace root",
        ),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    private fun readToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_READ,
        description = "Read the content of a text file in the workspace (UTF-8). Large files are paged automatically; continue reading with offset_chars/length_chars." +
            PATH_HINT,
        parameters = mapOf(
            "path" to "Required, file path",
            "offset_chars" to "Optional, character start cursor; when continuing, use the offset_chars returned last time.",
            "length_chars" to "Optional, characters per page, at most ${io.zer0.muse.tools.TOOL_OUTPUT_READ_PAGE_CHARS}.",
        ),
        required = setOf("path"),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    private fun writeToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_WRITE,
        description = "Write a text file into the workspace (UTF-8). Overwrites by default; appends when append=true. Single-write content limit 10MB." +
            "Parent directories are created automatically when missing. For long files write in batches: write the first chunk, then append chunk by chunk with append=true." +
            "Read-only mounts are rejected." + PATH_HINT,
        parameters = mapOf(
            "path" to "Required, file path",
            "content" to "Required, the text content to write",
            "append" to "Optional, whether to append (default false = overwrite). For long files, appending in batches is recommended",
        ),
        required = setOf("path", "content"),
        category = "built-in",
        // 必须声明类型，否则发给模型的 schema 会把 append 说成 string，
        // 而执行侧用 String.toBoolean() 解释，"1"/"yes"/"是" 会静默回落成覆盖写。
        parameterTypes = mapOf("append" to "boolean"),
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun editToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_EDIT,
        description =
        "Locally edit a file (modify text in the workspace): replace one passage of the original text with another (UTF-8)." +
            "old_string must match the file content character for character and occur exactly once in the whole file — 0 or multiple occurrences are both rejected;" +
            "this tool will not guess which occurrence you mean. To change several places, call it multiple times with enough context. An empty new_string deletes the passage." +
            "On success it returns a line-level diff (first changed line + @@ hunks) so you can check exactly which lines changed." +
            "Before editing, locate the original text with workspace_read or workspace_grep; do not rewrite the whole file. Read-only mounts are rejected." + PATH_HINT,
        parameters =
        mapOf(
            "path" to "Required, file path",
            "old_string" to "Required, the original passage to be replaced; must match character for character and be unique in the whole file",
            "new_string" to "Required, the new passage after replacement; an empty string deletes the passage",
        ),
        required = setOf("path", "old_string", "new_string"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun deleteToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_DELETE,
        description = "Delete a file or directory in the workspace. Directories are deleted recursively (including all children) and cannot be recovered. Read-only mounts are rejected." + PATH_HINT,
        parameters = mapOf(
            "path" to "Required, the path to delete",
        ),
        required = setOf("path"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun mkdirToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_MKDIR,
        description = "Create a directory in the workspace (multi-level supported). Already existing counts as success. Read-only mounts are rejected." + PATH_HINT,
        parameters = mapOf(
            "path" to "Required, directory path",
        ),
        required = setOf("path"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun moveToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_MOVE,
        description = "Move or rename a file/directory in the workspace. Fails if the target already exists; cannot move across mounts — use workspace_copy across mounts." +
            PATH_HINT,
        parameters = mapOf(
            "from" to "Required, source path",
            "to" to "Required, target path",
        ),
        required = setOf("from", "to"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun copyToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_COPY,
        description = "Copy a file or directory in the workspace. Fails if the target already exists." +
            "Directories can be copied directly within the same mount; across mounts only single files can be copied. The target mount needs write permission." + PATH_HINT,
        parameters = mapOf(
            "from" to "Required, source path (read permission needed)",
            "to" to "Required, target path (write permission needed)",
        ),
        required = setOf("from", "to"),
        category = "built-in",
        riskLevel = ToolRiskLevel.HIGH,
    )

    private fun grepToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_GREP,
        description =
        "Search content in workspace files; returns path:line:content. Use it to locate text before editing instead of reading whole files into context." +
            "Recursive search; binary files and files over 2MB are skipped automatically. With is_regex=true the pattern is interpreted as a regular expression." +
            "Stops at 100 hits and marks truncated. When path is empty, all available mounts are searched.",
        parameters =
        mapOf(
            "pattern" to "Required, the text to find; a regular expression when is_regex=true",
            "path" to "Optional, the path to start searching from; default is all available mounts",
            "is_regex" to "Optional, whether to interpret pattern as a regex (default false)",
            "max_results" to "Optional, hit limit, default 100",
        ),
        required = setOf("pattern"),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    private fun mountsToolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME_MOUNTS,
        description =
        "List the currently available workspace mounts: id | name | capabilities | source." +
            "Path prefixes use the ids here, e.g. `album:/DCIM/a.jpg`;" +
            "The mount with id main is the default mount and its prefix may be omitted. Read-only mounts are marked.",
        parameters = emptyMap(),
        required = emptySet(),
        category = "built-in",
        riskLevel = ToolRiskLevel.SAFE,
    )

    /**
     * 执行工具。
     *
     * @param name 工具名（必须为 [ALL_TOOL_NAMES] 之一）
     * @param args 参数 map
     * @param manager 注入的 [WorkspaceManager] 实例
     * @param sessionId 当前会话 id，用于取会话可见的挂载范围；为空表示不限制
     * @return 执行结果字符串（失败返回 "Error: ..." 前缀的消息）
     */
    fun execute(name: String, args: Map<String, String>, manager: WorkspaceManager, sessionId: String? = null): String = runBlocking {
        val scope = manager.sessionMountIds(sessionId)
        when (name) {
            NAME_MOUNTS -> renderMounts(manager.mounts())
            NAME_LIST -> executeList(args, manager, scope)
            NAME_READ -> executeRead(args, manager, scope)
            NAME_WRITE -> executeWrite(args, manager, scope)
            NAME_EDIT -> executeEdit(args, manager, scope)
            NAME_DELETE -> executeDelete(args, manager, scope)
            NAME_MKDIR -> executeMkdir(args, manager, scope)
            NAME_MOVE -> executeMove(args, manager, scope)
            NAME_COPY -> executeCopy(args, manager, scope)
            NAME_GREP -> executeGrep(args, manager, scope)
            else -> "Error: unknown tool name $name"
        }
    }

    /** 把挂载表渲染成给模型看的一行一条。 */
    private fun renderMounts(mounts: List<WorkspaceMount>): String {
        if (mounts.isEmpty()) return "(no mounts at all)"
        return mounts.joinToString("\n") { mount ->
            val caps = buildList {
                if (mount.canList) add("list")
                if (mount.canRead) add("read")
                if (mount.canWrite) add("write")
            }.joinToString("+").ifEmpty { "none" }
            val source = when (mount.provider) {
                io.zer0.muse.workspace.MountProvider.LOCAL -> "Local directory"
                io.zer0.muse.workspace.MountProvider.SAF -> "System-authorized directory"
            }
            "${mount.id} | ${mount.label} | $caps | $source"
        }
    }

    /** workspace_list 的执行体。 */
    private suspend fun executeList(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val path = args["path"]?.trim() ?: ""
        return when (val r = manager.listDir(path, scope)) {
            is WorkspaceManager.ListResult.Success -> {
                if (r.entries.isEmpty()) {
                    "(empty)"
                } else {
                    r.entries.joinToString("\n") { e ->
                        val type = if (e.isDirectory) "dir" else "file"
                        "${e.relativePath} | $type | ${e.size} | ${e.lastModified}"
                    }
                }
            }
            is WorkspaceManager.ListResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_read 的执行体。 */
    private suspend fun executeRead(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: parameter path is required"
        val offsetChars = args["offset_chars"]?.toIntOrNull()
        val lengthChars = args["length_chars"]?.toIntOrNull()
        return when (val r = manager.readFilePage(path, offsetChars, lengthChars, scope)) {
            is WorkspaceManager.ReadResult.Success -> r.content
            is WorkspaceManager.ReadResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_write 的执行体。 */
    private suspend fun executeWrite(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: parameter path is required"
        val content = args["content"] ?: return "Error: parameter content is required"
        val append = args["append"]?.toBoolean() ?: false
        val r = if (append) {
            manager.appendFile(path, content, scope)
        } else {
            manager.writeFile(path, content, scope)
        }
        return when (r) {
            is WorkspaceManager.OpResult.Success ->
                "OK: ${if (append) "appended to" else "written to"} $path (${content.length} characters)"
            is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_edit 的执行体：在全文快照上判唯一片段 → 替换 → 落盘 → 回行级 diff。 */
    private suspend fun executeEdit(
        args: Map<String, String>,
        manager: WorkspaceManager,
        scope: Set<String>?,
    ): String {
        val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
        val oldString = args["old_string"]
        val newString = args["new_string"]
        if (path == null || oldString == null || newString == null) {
            val missing = listOfNotNull(
                "path".takeIf { path == null },
                "old_string".takeIf { oldString == null },
                "new_string".takeIf { newString == null },
            )
            return "Error: parameter ${missing.joinToString("/")} is required"
        }
        // 必须整份读：片段匹配要跑在对全文的一份快照上，分页读到的一半内容匹配不上。
        // 读与写被包在同一把路径锁里完成（[WorkspaceManager.editFile]）：
        // 只锁写等于没锁——两个并发调用会各自读到旧内容再各自写回，丢一次更新。
        return when (val r = manager.editFile(path, scope) { original ->
            when (val plan = planEdit(original, oldString, newString, path)) {
                is EditPlan.Rejected -> WorkspaceManager.EditDecision.Abort(plan.message)
                is EditPlan.Applied -> WorkspaceManager.EditDecision.Commit(plan.updated)
            }
        }) {
            is WorkspaceManager.EditResult.Applied -> renderEdited(path, r)
            is WorkspaceManager.EditResult.Rejected -> "Error: ${r.message}"
            is WorkspaceManager.EditResult.Failed -> "Error: ${r.message}"
        }
    }

    /** 已写回的改动回执：首个改动行 + 行级 hunk。 */
    private fun renderEdited(path: String, applied: WorkspaceManager.EditResult.Applied): String {
        val diff = diffLines(applied.original, applied.updated)
        return buildString {
            appendLine("OK: workspace_edit updated $path")
            appendLine(
                "Change: first changed line ${diff.firstChangedLine};" +
                    "original ${diff.oldLineCount} lines → new content ${diff.newLineCount} lines",
            )
            append(diff.hunk)
        }
    }

    /**
     * 在完整原文上做一次唯一的片段替换。
     *
     * 三类前置拒绝全部在这里判完：片段找不到、片段不唯一、新旧内容相同。
     * 判定阶段不产生写入动作，所以拒绝时文件必然保持原样。
     *
     * @param original 文件完整内容
     * @param oldString 待替换片段
     * @param newString 替换后片段
     * @param path 展示用路径（只用于错误文案）
     */
    internal fun planEdit(original: String, oldString: String, newString: String, path: String): EditPlan {
        val occurrences = countOccurrences(original, oldString)
        val rejection = when {
            oldString.isEmpty() -> "old_string must not be empty: an empty passage matches at any position, so the place to change cannot be determined"
            oldString == newString -> "Old and new content are identical; no change needed: new_string matches old_string character for character"
            occurrences > 1 -> "old_string occurs $occurrences times in $path; provide more context to make it unique" +
                "(this tool does not just replace the first occurrence)"
            occurrences == 0 -> editMissingMessage(original, oldString, path)
            else -> null
        }
        if (rejection != null) return EditPlan.Rejected("$rejection (file unchanged)")
        val at = original.indexOf(oldString)
        val updated = original.replaceRange(at, at + oldString.length, newString)
        return EditPlan.Applied(updated, diffLines(original, updated))
    }

    /** [needle] 在 [haystack] 里的出现次数；重叠出现也各算一次（宁可多报重复，不少报唯一）。 */
    internal fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = haystack.indexOf(needle)
        while (from >= 0) {
            count++
            from = haystack.indexOf(needle, from + 1)
        }
        return count
    }

    /** 片段找不到时的提示；文件是 CRLF 换行而 old_string 用 LF 时点明原因。 */
    private fun editMissingMessage(original: String, oldString: String, path: String): String {
        val crlfHint = if (original.contains("\r\n") && original.replace("\r\n", "\n").contains(oldString)) {
            "; the file uses CRLF line endings, so the line breaks in old_string must also be written as CRLF to match character for character"
        } else {
            ""
        }
        return "Passage not found: old_string does not exist in $path; check the original text first with workspace_read or workspace_grep" +
            "(indentation and line breaks must match character for character)$crlfHint"
    }

    /**
     * 行级 diff。
     *
     * 单点替换保证改动是一段连续块，所以用公共前后缀定出这块即可，不需要通用 LCS；
     * 再按 [DIFF_CONTEXT_LINES] 行上下文渲染成 @@ hunk。
     *
     * @param oldText 替换前全文
     * @param newText 替换后全文
     */
    internal fun diffLines(oldText: String, newText: String): EditDiff {
        val oldLines = oldText.split('\n')
        val newLines = newText.split('\n')
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
            prefix++
        }
        val shared = minOf(oldLines.size - prefix, newLines.size - prefix)
        var suffix = 0
        while (suffix < shared && oldLines[oldLines.size - 1 - suffix] == newLines[newLines.size - 1 - suffix]) {
            suffix++
        }
        val removed = oldLines.subList(prefix, oldLines.size - suffix)
        val added = newLines.subList(prefix, newLines.size - suffix)
        val before = minOf(DIFF_CONTEXT_LINES, prefix)
        val after = minOf(DIFF_CONTEXT_LINES, suffix)
        val hunkStart = prefix - before + 1
        val body = buildString {
            appendLine("@@ -$hunkStart,${before + removed.size + after} +$hunkStart,${before + added.size + after} @@")
            for (i in prefix - before until prefix) appendLine(" ${oldLines[i].trimEnd('\r')}")
            removed.forEach { appendLine("-${it.trimEnd('\r')}") }
            added.forEach { appendLine("+${it.trimEnd('\r')}") }
            for (i in 0 until after) appendLine(" ${oldLines[oldLines.size - suffix + i].trimEnd('\r')}")
        }.trimEnd()
        return EditDiff(
            firstChangedLine = prefix + 1,
            oldLineCount = removed.size,
            newLineCount = added.size,
            hunk = body,
        )
    }

    /** workspace_delete 的执行体。 */
    private suspend fun executeDelete(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: parameter path is required"
        return when (val r = manager.delete(path, scope)) {
            is WorkspaceManager.OpResult.Success -> "OK: deleted $path"
            is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_mkdir 的执行体。 */
    private suspend fun executeMkdir(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val path = args["path"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: parameter path is required"
        return when (val r = manager.mkdir(path, scope)) {
            is WorkspaceManager.OpResult.Success -> "OK: directory created $path"
            is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_move 的执行体。 */
    private suspend fun executeMove(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val from = args["from"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: parameter from is required"
        val to = args["to"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: parameter to is required"
        return when (val r = manager.move(from, to, scope)) {
            is WorkspaceManager.OpResult.Success -> "OK: moved $from -> $to"
            is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_copy 的执行体。 */
    private suspend fun executeCopy(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val from = args["from"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: parameter from is required"
        val to = args["to"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return "Error: parameter to is required"
        return when (val r = manager.copy(from, to, scope)) {
            is WorkspaceManager.OpResult.Success -> "OK: copied $from -> $to"
            is WorkspaceManager.OpResult.Error -> "Error: ${r.message}"
        }
    }

    /** workspace_grep 的执行体。 */
    private suspend fun executeGrep(args: Map<String, String>, manager: WorkspaceManager, scope: Set<String>?): String {
        val pattern = args["pattern"]?.takeIf { it.isNotEmpty() }
            ?: return "Error: parameter pattern is required"
        val path = args["path"]?.trim() ?: ""
        val isRegex = args["is_regex"]?.toBoolean() ?: false
        val maxResults = args["max_results"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        return when (val r = manager.grep(pattern, path, isRegex, maxResults, scope)) {
            is WorkspaceManager.GrepResult.Success -> renderGrepMatches(r)
            is WorkspaceManager.GrepResult.Error -> "Error: ${r.message}"
        }
    }

    /** 把命中渲染成 路径:行号:内容。 */
    private fun renderGrepMatches(result: WorkspaceManager.GrepResult.Success): String {
        if (result.matches.isEmpty()) return "No hits (${result.scannedFiles} files scanned)"
        return buildString {
            result.matches.forEach { m ->
                appendLine("${m.path}:${m.lineNumber}:${m.line}")
            }
            if (result.truncated) {
                append("... hit limit reached, results truncated; narrow the path or make the pattern more precise")
            }
        }.trimEnd()
    }
}

/**
 * 工作区工具注册器。
 *
 * 用带上下文的注册方式：会话可见的挂载范围要靠 [ToolExecutionContext.sessionId] 取，
 * 普通注册拿不到会话信息。
 *
 * @param toolRegistry 工具注册表
 * @param workspaceManager 工作区管理器
 */
class WorkspaceToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val workspaceManager: WorkspaceManager,
) {
    init {
        registerAll()
    }

    /** 注册全部工作区工具。 */
    fun registerAll() {
        WorkspaceTool.toolDefs().forEach { def ->
            toolRegistry.registerWithContext(def) { args, context ->
                WorkspaceTool.execute(def.name, args, workspaceManager, context.sessionId)
            }
        }
    }
}

/** 一次片段替换的行级 diff。 */
internal data class EditDiff(
    /** 首个改动行的行号（1 起）。 */
    val firstChangedLine: Int,
    /** 被替换掉的原文行数。 */
    val oldLineCount: Int,
    /** 替换后的新内容行数。 */
    val newLineCount: Int,
    /** 带上下文的 @@ hunk 文本。 */
    val hunk: String,
)

/** 片段替换的判定结果。 */
internal sealed class EditPlan {
    /** 拒绝：带原因，文件未改动。 */
    data class Rejected(val message: String) : EditPlan()

    /** 可落盘：换好的全文 + 回执要用的 diff。 */
    data class Applied(val updated: String, val diff: EditDiff) : EditPlan()
}
