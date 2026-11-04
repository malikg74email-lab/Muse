package io.zer0.muse.tools

import java.io.File

/**
 * 通用 Shell 工具(命令是一段任意 shell 字符串,如 terminal_exec)使用的两档命令边界。
 *
 * ## 为什么不是直接套用 [ToolPermissionResolver.isUnsafeCommand]
 * `execute_shell` 是**只读查询**工具:17 条命令白名单、禁管道禁重定向,所以 [isUnsafeCommand]
 * 把 `` ` `` `$` `|` `;` `&` `<` `>` `*` `?` `{}` 整个语法集连同 rm/git/curl/python/chmod
 * 等可执行文件名一律判为不安全 —— 放在只读沙箱上不会误杀。
 * `terminal_exec` 是**通用终端**:管道切日志、重定向存输出、变量传参、子目录递归删除
 * 都是它的正常工作,把那套判据原样搬过来它基本就没法用了。
 * 所以这里按「真实破坏力」而不是「字符串形状」重新分档。
 *
 * ## 两档
 *  - [forbidReason] —— 绝对不许出现的最小集合。terminal_exec 直接拒绝执行,
 *    TRUSTED 模式与「始终允许该工具」都绕不过:它是白名单/审批层出 bug 时的地板。
 *  - [cautionReason] —— 有破坏力但很可能是正常工作(删工作区内的文件、改权限、下载、
 *    `git push --force`、杀进程、设备状态命令)。terminal_exec **不拦**这一档,
 *    交给既有审批链:terminal_exec 本身是 HIGH 风险,ASK/STRICT 模式下逐次弹卡,
 *    用户在卡上能看到命令原文。本层只额外记一条警告日志,便于事后回溯。
 *
 * ## 第一档是最小集,不是完备集
 * 只抓「一条命令就能毁掉一片数据」的形态:设备根与应用私有数据/系统分区上的删除与写入、
 * 格式化与块设备写入、越界的递归改权限、下载即执行(`curl … | sh`)、
 * 提权与设备级命令、杀 init / fork 炸弹。明确**不做**的事:
 *  - 不穷举破坏性命令。`mv` 挪走目录、`tar` 覆盖、`truncate` 清空、`dd` 写满磁盘这些
 *    无数变种不在这里;它们要么落在第二档,要么由审批链承担。
 *  - 不禁「先下载、再执行本地脚本」(`curl … -o i.sh && sh i.sh`)。它与 `curl … | sh`
 *    破坏力同型,但禁止它就等于禁止一切脚本执行(`sh build.sh` 是本工具的正当用途);
 *    这一类只在第二档标出来,由审批卡上的命令原文承担。
 *  - 不解析 shell 语义。命令替换的碎片拼接、base64 解码后再执行,本边界看不见
 *    (它只做分段与参数级匹配)。它拦的是**模型正常生成的破坏性命令**,
 *    不是攻击者精心构造的字符串 —— 后者要靠审批卡上让用户读到的命令原文。
 *  - 不替代工具沙盒。terminal_exec 以 App 自身权限运行,绝大多数系统路径本来就写不进去;
 *    这里拦的是「即使权限层出 bug 也不该发生」的意图。
 *
 * 分段、落点这些读法在 [ShellSegment] / [ShellLocator] / [ShellCommandLex] 里。
 */
internal object ShellCommandBoundary {

    /**
     * 第一档:命中即拒绝执行。
     *
     * @param command 待执行的 shell 命令原文
     * @param workspaceDir 工具的工作目录(terminal_exec 的应用工作区)。相对路径按它解析成
     *   绝对落点,才能区分「工作区内的正常清理」与「越界删除」。传 null 时相对路径判不出
     *   落点,不参与第一档拦截。
     * @return 命中规则时返回可直接讲给用户的原因;未命中返回 null
     */
    fun forbidReason(command: String, workspaceDir: File? = null): String? {
        if (command.isBlank()) return null
        if (command.replace(FORK_BOMB_SPACES, "").contains(FORK_BOMB_SHAPE)) return FORK_BOMB_REASON
        val locator = ShellLocator(workspaceDir)
        for (segment in ShellSegment.split(command)) {
            val hit = deleteReason(segment, locator)
                ?: writeReason(segment, locator)
                ?: permissionReason(segment, locator)
                ?: formatReason(segment)
                ?: deviceReason(segment)
                ?: remoteCodeReason(segment)
                ?: killReason(segment)
            if (hit != null) return hit
        }
        return redirectReason(command, locator)
    }

    /**
     * 第二档:有破坏力但很可能就是正常工作 —— **不拦**,由既有审批链(HIGH 风险逐次确认)
     * 负责,调用方最多记一条日志。
     *
     * @return 破坏性的描述;命令没有破坏力时返回 null
     */
    fun cautionReason(command: String): String? =
        ShellSegment.split(command).firstNotNullOfOrNull(::cautionForSegment)

    private fun cautionForSegment(segment: ShellSegment): String? {
        val verb = segment.verb() ?: return null
        if (verb == "find" && segment.tokens.contains("-delete")) return "find -delete batch deletion"
        if (verb == "git" && segment.args().any { it in GIT_DESTRUCTIVE_ARGS }) return "git destructive subcommand (reset/clean/force-push)"
        return CAUTION_NOTES[verb]
    }

    // ── 第一档具体规则 ────────────────────────────────────────────────────

    /** 删除:落点越界(设备根/系统分区/应用私有数据)一律拦;递归删除还得覆盖到工作区外才拦。 */
    private fun deleteReason(segment: ShellSegment, locator: ShellLocator): String? {
        val verb = segment.verb() ?: return null
        if (verb == "find") return findDeleteReason(segment, locator)
        if (verb !in DELETE_VERBS) return null
        val recursive = segment.args().any(ShellCommandLex::isRecursiveFlag)
        return segment.pathArgs().firstNotNullOfOrNull { raw ->
            deleteTargetReason(raw, locator.target(raw), recursive)
        }
    }

    private fun deleteTargetReason(raw: String, target: ShellTarget, recursive: Boolean): String? =
        unacceptable("Delete", target, raw) ?: when {
            !recursive -> null
            target.scope == ShellScope.WORKSPACE_ROOT && target.wholeDirectory -> "Recursively deleting the entire app workspace: $raw"
            target.scope == ShellScope.STORAGE || target.scope == ShellScope.OUTSIDE -> "Recursive delete pointing at a path outside the workspace: $raw"
            else -> null
        }

    /** `find <根> … -delete`:扫描根越出工作区就拦;工作区内按谓词删除属于第二档。 */
    private fun findDeleteReason(segment: ShellSegment, locator: ShellLocator): String? {
        if (!segment.tokens.contains("-delete")) return null
        val root = segment.pathArgs().firstOrNull() ?: return null
        val target = locator.target(root)
        unacceptable("find -delete scan root", target, root)?.let { return it }
        return when (target.scope) {
            ShellScope.STORAGE, ShellScope.OUTSIDE -> "find -delete scan root is outside the workspace: $root"
            else -> null
        }
    }

    /** 显式写入目标:`dd of=`、`tee`、`cp/mv/install/ln` 的落点、`truncate`。 */
    private fun writeReason(segment: ShellSegment, locator: ShellLocator): String? {
        val destinations = when (segment.verb()) {
            "dd" -> segment.args().filter { it.startsWith("of=") }.map { it.removePrefix("of=") }
            "tee" -> segment.args().filterNot { it.startsWith("-") }
            "cp", "mv", "install", "ln", "truncate" -> listOfNotNull(segment.pathArgs().lastOrNull())
            else -> emptyList()
        }
        return destinations.firstNotNullOfOrNull { raw -> unacceptable("Write", locator.target(raw), raw) }
    }

    /**
     * 改权限/属主。设备根与系统分区上的 `chmod`/`chown` 一律拦(`chmod 777 /` 这类);
     * 工作区内的改权限是准备脚本的正常过程,不拦 —— 递归放开工作区外的写权限才拦。
     */
    private fun permissionReason(segment: ShellSegment, locator: ShellLocator): String? {
        val verb = segment.verb() ?: return null
        if (verb !in PERMISSION_VERBS) return null
        val args = segment.args()
        val recursive = args.any(ShellCommandLex::isRecursiveFlag)
        val worldWritable = args.any(ShellCommandLex::isWorldWritableMode)
        return segment.pathArgs().firstNotNullOfOrNull { raw ->
            permissionTargetReason(verb, locator.target(raw).scope, recursive, worldWritable, raw)
        }
    }

    private fun permissionTargetReason(
        verb: String,
        scope: ShellScope,
        recursive: Boolean,
        worldWritable: Boolean,
        raw: String,
    ): String? = when {
        scope == ShellScope.DEVICE_ROOT -> "$verb acting on the device root: $raw"
        scope == ShellScope.SYSTEM -> "$verb acting on a system partition: $raw"
        scope == ShellScope.APP_DATA -> "$verb acting on another app's private data under /data outside the workspace: $raw"
        recursive && worldWritable && scope in OUT_OF_SCOPE_STORAGE -> {
            "Recursively opening write permission on a path outside the workspace: $verb $raw"
        }
        else -> null
    }

    /** 格式化与块设备:这些命令的作用对象不可能是工作区里的文件。 */
    private fun formatReason(segment: ShellSegment): String? {
        val verb = segment.verb() ?: return null
        if (verb == "mkfs" || verb.startsWith("mkfs.")) return "Filesystem formatting command: $verb"
        if (verb in BLOCK_DEVICE_VERBS) return "Command directly operating on a partition/block device: $verb"
        return ddDeviceReason(verb, segment)
    }

    private fun ddDeviceReason(verb: String, segment: ShellSegment): String? {
        if (verb != "dd") return null
        val output = segment.args().firstOrNull { it.startsWith("of=") }?.removePrefix("of=").orEmpty()
        return if (output.startsWith("/dev/") && !ShellCommandLex.isHarmlessSink(output)) "dd writing to a device node: $output" else null
    }

    /**
     * 设备级命令:提权、电源、挂载/命名空间,以及会清掉或卸载应用数据的 `pm`/`am`/`appops`
     * 子命令。这些本来就有各自的专用工具(device_shell / app_clear_data / am_start 等),
     * 走的是各自的审批;出现在文件终端里没有正当用途。
     */
    private fun deviceReason(segment: ShellSegment): String? {
        val verb = segment.verb() ?: return null
        DEVICE_ONLY_VERBS[verb]?.let { return "$it:$verb" }
        return destructiveSubcommandReason(verb, segment.args().firstOrNull { !it.startsWith("-") })
    }

    private fun destructiveSubcommandReason(verb: String, subcommand: String?): String? = when {
        verb == "pm" && subcommand in PM_DESTRUCTIVE -> "Package-manager destructive subcommand: pm $subcommand"
        verb == "am" && subcommand == "force-stop" -> "Command force-stopping another app: am force-stop"
        verb == "appops" && subcommand == "set" -> "Command changing another app's permission surface: appops set"
        else -> null
    }

    /**
     * 「把一段动态内容当命令码执行」这一类:管道喂给 shell/解释器的 stdin,
     * 或 `eval "$(curl …)"`。`curl … | sh`、`wget -qO- … | bash` 是它的典型形态。
     *
     * 管道本身不拦(`cat x | grep y` 天天在用),只在内容真会被执行时拦:
     *  - shell 不带 `-c` 时,stdin 就是它的命令来源 → 拦
     *  - 解释器没有脚本参数,或参数是 `-`/`-s`/`--stdin` → stdin 是命令来源 → 拦
     *  - `… | python3 -m json.tool`、`… | python3 fmt.py`、`… | sh -c 'echo hi'`:
     *    管道里是**数据**不是命令码 → 不拦,这是终端的正当用法
     */
    private fun remoteCodeReason(segment: ShellSegment): String? {
        val verb = segment.verb() ?: return null
        val args = segment.args()
        val pipedCode = segment.isPipeInput && verb in INTERPRETERS &&
            ShellCommandLex.stdinIsCommandSource(args, verb in SHELL_INTERPRETERS)
        if (pipedCode) return "Executing piped content as command code:| $verb ${args.joinToString(" ")}".trim()
        if (verb in SCRIPT_SOURCES && args.any(ShellCommandLex::loadsRemoteContent)) {
            return "$verb executing script content from the network"
        }
        return null
    }

    private fun killReason(segment: ShellSegment): String? = when {
        segment.verb() != "kill" -> null
        segment.args().any { it == "1" } -> "kill targeting PID 1 (init)"
        segment.args().any { it == "-1" } -> "kill targeting all processes"
        else -> null
    }

    /** 重定向落盘的目标:`> /system/...`、`>> /data/data/...`。`2>&1` 这类不是落盘。 */
    private fun redirectReason(command: String, locator: ShellLocator): String? =
        REDIRECT.findAll(command)
            .map { ShellCommandLex.stripQuotes(it.groupValues[1]) }
            .filter { it.isNotEmpty() && !it.startsWith("&") }
            .firstNotNullOfOrNull { raw -> unacceptable("Redirect write", locator.target(raw), raw) }

    /** 目标落在「绝对不可接受」的区域时的原因;其余区域由调用方按档位判断。 */
    private fun unacceptable(label: String, target: ShellTarget, raw: String): String? = when (target.scope) {
        ShellScope.DEVICE_ROOT -> "${label} hits the device root ($raw)"
        ShellScope.SYSTEM -> "${label} hits a system partition ($raw)"
        ShellScope.APP_DATA -> "${label} hits another app's private data under /data outside the workspace ($raw)"
        else -> null
    }

    // ── 判据表 ────────────────────────────────────────────────────────────

    private val FORK_BOMB_SPACES = Regex("\\s+")
    private const val FORK_BOMB_SHAPE = ":(){:"
    private const val FORK_BOMB_REASON = "Fork bomb self-replication pattern"

    private val REDIRECT = Regex("""\d?>{1,2}\s*("?[^"\s;|&]+)""")

    private val DELETE_VERBS = setOf("rm", "rmdir", "shred", "unlink")
    private val PERMISSION_VERBS = setOf("chmod", "chown", "chgrp")
    private val BLOCK_DEVICE_VERBS = setOf("fdisk", "sfdisk", "parted", "sgdisk", "wipefs", "losetup", "mkswap")

    /** 设备级命令:在 App 沙盒终端里没有正当用途,各自有专用工具与审批。 */
    private val DEVICE_ONLY_VERBS = mapOf(
        "su" to "Privilege-escalation command",
        "sudo" to "Privilege-escalation command",
        "doas" to "Privilege-escalation command",
        "reboot" to "Device power command",
        "poweroff" to "Device power command",
        "shutdown" to "Device power command",
        "halt" to "Device power command",
        "mount" to "Mount and system-runtime command",
        "umount" to "Mount and system-runtime command",
        "setprop" to "Mount and system-runtime command",
        "restorecon" to "Mount and system-runtime command",
        "chroot" to "Mount and system-runtime command",
        "switch_root" to "Mount and system-runtime command",
    )

    private val PM_DESTRUCTIVE = setOf("clear", "uninstall", "disable", "delete")

    /** 递归放开这些区域的写权限才算第一档;工作区内改权限属于正常准备脚本的过程。 */
    private val OUT_OF_SCOPE_STORAGE = setOf(ShellScope.STORAGE, ShellScope.OUTSIDE)

    private val SHELL_INTERPRETERS = setOf("sh", "bash", "dash", "ash", "ksh", "zsh", "csh", "tcsh")
    private val INTERPRETERS = SHELL_INTERPRETERS + setOf(
        "python", "python2", "python3", "perl", "ruby", "node", "php", "lua",
    )

    /** 显式声明「stdin 就是我的命令来源」的参数。 */
    private val SCRIPT_SOURCES = setOf("eval", "source")

    private val GIT_DESTRUCTIVE_ARGS = setOf("--force", "reset", "clean", "push", "checkout")

    /** 第二档:有破坏力但属于正常工作,只记日志,不拦。 */
    private val CAUTION_NOTES = mapOf(
        "rm" to "Delete file",
        "rmdir" to "Delete directory",
        "unlink" to "Delete file",
        "shred" to "Destroy file contents",
        "mv" to "Move/overwrite file",
        "truncate" to "Truncate file",
        "dd" to "Block-level read/write",
        "chmod" to "Change file permissions",
        "chown" to "Change file owner",
        "chgrp" to "Change file group",
        "kill" to "Terminate process",
        "pkill" to "Kill processes by name in bulk",
        "killall" to "Kill processes by name in bulk",
        "curl" to "Network download",
        "wget" to "Network download",
        "pm" to "Package management command",
        "am" to "Activity manager command",
        "settings" to "System settings read/write",
        "input" to "Inject input events",
        "sh" to "Execute script file",
        "bash" to "Execute script file",
        "python" to "Execute script file",
        "python3" to "Execute script file",
        "node" to "Execute script file",
        "perl" to "Execute script file",
        "ruby" to "Execute script file",
    )
}
