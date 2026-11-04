package io.zer0.muse.ui.chat

import android.content.res.Resources
import androidx.compose.ui.graphics.vector.ImageVector
import io.zer0.muse.R
import io.zer0.muse.ui.common.icons.MuseIcons
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.0.80: 工具调用的视觉元数据 — 图标 + 人摘要 + 中文标签。
 *
 * 把原来所有工具共用一个 Build/Check 图标的毛坯状态,换成每种工具自己的语义图标,
 * 并为折叠态生成一句"做了什么"的中文摘要,替代裸的工具名+前40字结果。
 *
 * 图标全部来自 Tabler Icons(项目已依赖);未命中的工具回退到通用扳手图标,不报错。
 */
internal object ToolCallVisuals {

    /** 取工具图标(未命中回退为通用扳手)。 */
    fun iconFor(toolName: String): ImageVector = mapping[toolName] ?: prefixIcon(toolName) ?: MuseIcons.wrench

    /** 折叠态一句话摘要。I18N-01: 传入 res 走资源(zh+en 已入 strings_tools,其余语言分批)。 */
    fun summaryFor(toolName: String, arguments: String, result: String, isSuccess: Boolean, res: Resources? = null): String {
        // v2.0.1: 失败时用「操作名 + 失败」语义 — 不再复用成功动词
        // （用户反馈：审批超时的工具卡仍写着“写入了工作区 xxx”）。
        if (!isSuccess) {
            val failLabel = res?.let { r ->
                labelIds[toolName]?.let { r.getString(it) }
            } ?: labels[toolName] ?: prettify(toolName)
            // res==null 的兜底(非 Android 环境):仅返回标签,不带失败后缀。
            return res?.getString(R.string.tool_summary_failed, failLabel) ?: failLabel
        }
        val verb = res?.let { r ->
            successVerbIds[toolName]?.let { id -> r.getString(id) }
                ?: prefixVerbId(toolName)?.let { id -> r.getString(id) }
                ?: r.getString(R.string.tool_summary_default_success)
        } ?: (successVerb[toolName] ?: prefixVerb(toolName) ?: defaultVerb(true))
        var obj = targetFor(toolName, arguments, result)
        // v2.3.1: find_tools 未带 query 表示「列出全部工具」—— 兜底文案走资源;
        // 在此处(而非 targetFor)取,避免改动 targetFor 签名导致 detekt 基线签名失配
        if (obj.isBlank() && toolName == "find_tools") {
            obj = res?.getString(R.string.tool_target_all_tools).orEmpty()
        }
        return if (obj.isBlank()) verb else "$verb $obj"
    }

    /** 展开态工具标签(原来直接显示英文 toolName)。I18N-01: 同 [summaryFor]。 */
    fun labelFor(toolName: String, res: Resources? = null): String {
        val localized = res?.let { r -> labelIds[toolName]?.let { id -> r.getString(id) } }
        return localized ?: labels[toolName] ?: prettify(toolName)
    }

    /**
     * 终态摘要：超时与中断在折叠态直接可见。
     *
     * 返回 null 表示结果不是编排器合成的终态，调用方应回退到 [summaryFor]。
     */
    fun terminalSummaryFor(result: String, res: Resources? = null): String? {
        val text = result.trimStart()
        return when {
            // I18N: 兜底只在 res==null(预览/纯函数)时触达,生产路径始终走资源
            text.startsWith("[Timeout]") || text.startsWith("[超时]") -> res?.getString(R.string.tool_terminal_timeout) ?: ""
            text.startsWith("[Interrupted]") || text.startsWith("[中断]") -> res?.getString(R.string.tool_terminal_interrupted) ?: ""
            else -> null
        }
    }

    // ── 图标映射 ─────────────────────────────────────────────────────────

    private val mapping: Map<String, ImageVector> = mapOf(
        // 搜索 / 知识
        "web_search" to MuseIcons.globe,
        "search_memory" to MuseIcons.search,
        "pin_memory" to MuseIcons.star,
        "unpin_memory" to MuseIcons.star,
        "recall_experience" to MuseIcons.book,
        "record_experience" to MuseIcons.book,
        // 文件 / 工作区
        "read_file" to MuseIcons.file,
        "write_file" to MuseIcons.edit,
        "list_files" to MuseIcons.folder,
        "workspace_write" to MuseIcons.edit,
        "workspace_edit" to MuseIcons.edit,
        "workspace_read" to MuseIcons.file,
        // 代码 / 终端
        "execute_code" to MuseIcons.code,
        "execute_javascript" to MuseIcons.braces,
        "execute_node_script" to MuseIcons.terminal,
        "execute_shell" to MuseIcons.terminal,
        "run_command" to MuseIcons.terminal,
        "json_pretty" to MuseIcons.braces,
        // 浏览器 / 网络
        "open_url" to MuseIcons.browser,
        "browser_navigate" to MuseIcons.browser,
        "browser_click" to MuseIcons.browser,
        "browser_type" to MuseIcons.browser,
        "browser_snapshot" to MuseIcons.browser,
        "ping_host" to MuseIcons.activity,
        "dns_lookup" to MuseIcons.activity,
        "get_public_ip" to MuseIcons.globe,
        "download" to MuseIcons.download,
        // 生成媒体
        "generate_image" to MuseIcons.image,
        "generate_video" to MuseIcons.video,
        "cover_generation" to MuseIcons.image,
        // 时间 / 日历 / 提醒
        "get_current_time" to MuseIcons.clock,
        "calendar_today" to MuseIcons.calendar,
        "add_calendar_event" to MuseIcons.calendar,
        "schedule_reminder" to MuseIcons.bell,
        "cancel_reminder" to MuseIcons.bell,
        "list_reminders" to MuseIcons.bell,
        "set_alarm" to MuseIcons.bell,
        "set_timer" to MuseIcons.clock,
        "scheduled_task_create" to MuseIcons.calendar,
        "scheduled_task_list" to MuseIcons.calendar,
        "scheduled_task_update" to MuseIcons.calendar,
        "scheduled_task_delete" to MuseIcons.calendar,
        "scheduled_task_execute" to MuseIcons.calendar,
        "scheduled_task_get_history" to MuseIcons.calendar,
        // 系统 / 设备
        "get_device_info" to MuseIcons.server,
        "get_battery_info" to MuseIcons.server,
        "get_storage_info" to MuseIcons.server,
        "get_memory_info" to MuseIcons.server,
        "get_cpu_info" to MuseIcons.server,
        "get_display_info" to MuseIcons.server,
        "get_network_info" to MuseIcons.server,
        "get_wifi_info" to MuseIcons.wifi,
        "toggle_wifi" to MuseIcons.wifi,
        "toggle_bluetooth" to MuseIcons.rss,
        "get_bluetooth_devices" to MuseIcons.rss,
        "list_installed_apps" to MuseIcons.packageIcon,
        "open_app" to MuseIcons.packageIcon,
        "open_system_setting" to MuseIcons.sliders,
        "get_foreground_app" to MuseIcons.packageIcon,
        "get_sensors_list" to MuseIcons.activity,
        "get_brightness" to MuseIcons.sun,
        "set_brightness" to MuseIcons.sun,
        "get_volume" to MuseIcons.volume,
        "set_volume" to MuseIcons.volume,
        "toggle_flashlight" to MuseIcons.bulb,
        "vibrate" to MuseIcons.phone,
        "screen_time" to MuseIcons.clock,
        // 电话 / 短信 / 联系人
        "make_phone_call" to MuseIcons.phone,
        "send_sms" to MuseIcons.chat,
        "get_contacts_count" to MuseIcons.users,
        "get_contacts_list" to MuseIcons.users,
        "add_contact" to MuseIcons.users,
        "get_location" to MuseIcons.mapPin,
        "open_maps" to MuseIcons.mapPin,
        "share_text" to MuseIcons.send,
        "send_email" to MuseIcons.mail,
        "get_recent_notifications" to MuseIcons.bell,
        // 剪贴板
        "clipboard_read" to MuseIcons.clipboard,
        "clipboard_write" to MuseIcons.clipboard,
        // 便签
        "quick_note_add" to MuseIcons.note,
        "quick_note_list" to MuseIcons.note,
        "quick_note_get" to MuseIcons.note,
        "quick_note_update" to MuseIcons.note,
        "quick_note_delete" to MuseIcons.note,
        "quick_note_pin" to MuseIcons.note,
        // 资源 / 知识库
        "resource_add" to MuseIcons.database,
        "resource_list" to MuseIcons.database,
        "resource_search" to MuseIcons.database,
        "resource_get" to MuseIcons.database,
        "resource_delete" to MuseIcons.database,
        // 多 Agent / 委派
        "delegate_agent" to MuseIcons.hierarchy,
        "subagent_task" to MuseIcons.hierarchy,
        "subagent_run" to MuseIcons.hierarchy,
        "subagent_close" to MuseIcons.hierarchy,
        "channel_pass" to MuseIcons.hierarchy,
        "channel_reply" to MuseIcons.hierarchy,
        "channel_read_context" to MuseIcons.hierarchy,
        // 技能 / 插件 / MCP
        "skill_import" to MuseIcons.puzzle,
        "skill_run" to MuseIcons.puzzle,
        "plugin_market_search" to MuseIcons.search,
        "plugin_market_install" to MuseIcons.puzzle,
        "plugin_market_uninstall" to MuseIcons.trash,
        "plugin_market_set_enabled" to MuseIcons.power,
        "find_tools" to MuseIcons.search,
        "device_shell" to MuseIcons.terminal,
        "terminal_exec" to MuseIcons.terminal,
        "termux_exec" to MuseIcons.terminal,
        "mcp_tool" to MuseIcons.plug,
        // 通知 / 主动消息 / 卡片
        "notify" to MuseIcons.bell,
        "proactive_message_wish" to MuseIcons.wand,
        "show_card" to MuseIcons.wand,
        "current_status" to MuseIcons.info,
        // 杂项
        "calculator" to MuseIcons.calculator,
        "echo" to MuseIcons.chat,
        "translate" to MuseIcons.languages,
        "speak_text" to MuseIcons.microphone,
        "get_weather" to MuseIcons.cloud,
        "todo_write" to MuseIcons.check,
        "url_encode" to MuseIcons.code,
        "url_decode" to MuseIcons.code,
        "base64_encode" to MuseIcons.code,
        "base64_decode" to MuseIcons.code,
        "hash_text" to MuseIcons.shield,
        "generate_uuid" to MuseIcons.code,
        "generate_password" to MuseIcons.shield,
        "random_number" to MuseIcons.calculator,
        "wallet_balance" to MuseIcons.wallet,
        "take_photo" to MuseIcons.camera,
    )

    private fun prefixIcon(toolName: String): ImageVector? = when {
        toolName.startsWith("browser_") -> MuseIcons.browser
        toolName.startsWith("workspace_") -> MuseIcons.file
        toolName.startsWith("scheduled_task_") -> MuseIcons.calendar
        toolName.startsWith("quick_note_") -> MuseIcons.note
        toolName.startsWith("resource_") -> MuseIcons.database
        toolName.startsWith("subagent_") -> MuseIcons.hierarchy
        toolName.startsWith("channel_") -> MuseIcons.hierarchy
        toolName.startsWith("clipboard_") -> MuseIcons.clipboard
        toolName.startsWith("mcp_") -> MuseIcons.plug
        else -> null
    }

    // ── 动词 ─────────────────────────────────────────────────────────────

    private val successVerb: Map<String, String> = mapOf(
        "web_search" to "Searched the web",
        "search_memory" to "Searched memory",
        "pin_memory" to "Pinned a memory",
        "unpin_memory" to "Unpinned a memory",
        "read_file" to "Read a file",
        "write_file" to "Wrote a file",
        "list_files" to "Listed files",
        "workspace_write" to "Wrote to the workspace",
        "execute_code" to "Executed code",
        "execute_javascript" to "Executed a script",
        "execute_shell" to "Executed a command",
        "run_command" to "Ran a command",
        "open_url" to "Opened a web page",
        "generate_image" to "Generated an image",
        "generate_video" to "Generated a video",
        "get_current_time" to "Checked the time",
        "calendar_today" to "Viewed the calendar",
        "add_calendar_event" to "Added a calendar event",
        "schedule_reminder" to "Set a reminder",
        "cancel_reminder" to "Cancelled a reminder",
        "list_reminders" to "Listed reminders",
        "set_alarm" to "Set an alarm",
        "set_timer" to "Started a timer",
        "get_device_info" to "Read device info",
        "get_battery_info" to "Read battery level",
        "get_storage_info" to "Read storage info",
        "get_memory_info" to "Read memory info",
        "get_cpu_info" to "Read CPU info",
        "get_network_info" to "Read network info",
        "list_installed_apps" to "Listed apps",
        "open_app" to "Opened an app",
        "make_phone_call" to "Made a phone call",
        "send_sms" to "Sent a text message",
        "get_contacts_list" to "Read contacts",
        "add_contact" to "Added a contact",
        "get_location" to "Got the location",
        "open_maps" to "Opened maps",
        "share_text" to "Shared text",
        "send_email" to "Sent an email",
        "get_recent_notifications" to "Read notifications",
        "clipboard_read" to "Read the clipboard",
        "clipboard_write" to "Wrote to the clipboard",
        "quick_note_add" to "Created a note",
        "quick_note_list" to "Listed notes",
        "quick_note_get" to "Read a note",
        "quick_note_update" to "Updated a note",
        "quick_note_delete" to "Deleted a note",
        "quick_note_pin" to "Pinned a note",
        "resource_add" to "Added a resource",
        "resource_list" to "Listed resources",
        "resource_search" to "Searched resources",
        "resource_get" to "Read a resource",
        "resource_delete" to "Deleted a resource",
        "delegate_agent" to "Delegated to a sub-assistant",
        "subagent_task" to "Started a subtask",
        "subagent_run" to "Ran a subtask",
        "subagent_close" to "Closed a subtask",
        "notify" to "Sent a notification",
        "proactive_message_wish" to "Noted a proactive message",
        "show_card" to "Showed a card",
        "current_status" to "Checked the status",
        "calculator" to "Calculated",
        "translate" to "Translated",
        "speak_text" to "Read text aloud",
        "get_weather" to "Checked the weather",
        "todo_write" to "Updated the to-do list",
        "ping_host" to "Pinged a host",
        "dns_lookup" to "Did a DNS lookup",
        "get_public_ip" to "Checked the public IP",
        "download" to "Downloaded a file",
        "json_pretty" to "Formatted JSON",
        "hash_text" to "Computed a hash",
        "generate_password" to "Generated a password",
        "record_experience" to "Recorded an experience",
        "recall_experience" to "Recalled an experience",
        "take_photo" to "Took a photo",
        "cover_generation" to "Generated a cover",
    )

    // I18N-01: 动词资源 ID(生产路径优先；successVerb 为 res==null 兜底的存量文案,新增工具只登记资源;es/ko/ja/pt/ru 翻译分批补齐)。
    private val successVerbIds: Map<String, Int> = mapOf(
        "web_search" to R.string.tool_summary_web_search,
        "search_memory" to R.string.tool_summary_search_memory,
        "pin_memory" to R.string.tool_summary_pin_memory,
        "unpin_memory" to R.string.tool_summary_unpin_memory,
        "read_file" to R.string.tool_summary_read_file,
        "write_file" to R.string.tool_summary_write_file,
        "list_files" to R.string.tool_summary_list_files,
        "workspace_write" to R.string.tool_summary_workspace_write,
        "workspace_edit" to R.string.tool_summary_workspace_edit,
        "execute_code" to R.string.tool_summary_execute_code,
        "execute_javascript" to R.string.tool_summary_execute_javascript,
        "execute_node_script" to R.string.tool_summary_execute_javascript,
        "execute_shell" to R.string.tool_summary_execute_shell,
        "run_command" to R.string.tool_summary_run_command,
        "open_url" to R.string.tool_summary_open_url,
        "generate_image" to R.string.tool_summary_generate_image,
        "generate_video" to R.string.tool_summary_generate_video,
        "get_current_time" to R.string.tool_summary_get_current_time,
        "calendar_today" to R.string.tool_summary_calendar_today,
        "add_calendar_event" to R.string.tool_summary_add_calendar_event,
        "schedule_reminder" to R.string.tool_summary_schedule_reminder,
        "cancel_reminder" to R.string.tool_summary_cancel_reminder,
        "list_reminders" to R.string.tool_summary_list_reminders,
        "set_alarm" to R.string.tool_summary_set_alarm,
        "set_timer" to R.string.tool_summary_set_timer,
        "get_device_info" to R.string.tool_summary_get_device_info,
        "get_battery_info" to R.string.tool_summary_get_battery_info,
        "get_storage_info" to R.string.tool_summary_get_storage_info,
        "get_memory_info" to R.string.tool_summary_get_memory_info,
        "get_cpu_info" to R.string.tool_summary_get_cpu_info,
        "get_network_info" to R.string.tool_summary_get_network_info,
        "list_installed_apps" to R.string.tool_summary_list_installed_apps,
        "open_app" to R.string.tool_summary_open_app,
        "make_phone_call" to R.string.tool_summary_make_phone_call,
        "send_sms" to R.string.tool_summary_send_sms,
        "get_contacts_list" to R.string.tool_summary_get_contacts_list,
        "add_contact" to R.string.tool_summary_add_contact,
        "get_location" to R.string.tool_summary_get_location,
        "open_maps" to R.string.tool_summary_open_maps,
        "share_text" to R.string.tool_summary_share_text,
        "send_email" to R.string.tool_summary_send_email,
        "get_recent_notifications" to R.string.tool_summary_get_recent_notifications,
        "clipboard_read" to R.string.tool_summary_clipboard_read,
        "clipboard_write" to R.string.tool_summary_clipboard_write,
        "quick_note_add" to R.string.tool_summary_quick_note_add,
        "quick_note_list" to R.string.tool_summary_quick_note_list,
        "quick_note_get" to R.string.tool_summary_quick_note_get,
        "quick_note_update" to R.string.tool_summary_quick_note_update,
        "quick_note_delete" to R.string.tool_summary_quick_note_delete,
        "quick_note_pin" to R.string.tool_summary_quick_note_pin,
        "resource_add" to R.string.tool_summary_resource_add,
        "resource_list" to R.string.tool_summary_resource_list,
        "resource_search" to R.string.tool_summary_resource_search,
        "resource_get" to R.string.tool_summary_resource_get,
        "resource_delete" to R.string.tool_summary_resource_delete,
        "delegate_agent" to R.string.tool_summary_delegate_agent,
        "subagent_task" to R.string.tool_summary_subagent_task,
        "subagent_run" to R.string.tool_summary_subagent_run,
        "subagent_close" to R.string.tool_summary_subagent_close,
        "notify" to R.string.tool_summary_notify,
        "proactive_message_wish" to R.string.tool_summary_proactive_message_wish,
        "show_card" to R.string.tool_summary_show_card,
        "current_status" to R.string.tool_summary_current_status,
        "calculator" to R.string.tool_summary_calculator,
        "translate" to R.string.tool_summary_translate,
        "speak_text" to R.string.tool_summary_speak_text,
        "get_weather" to R.string.tool_summary_get_weather,
        "todo_write" to R.string.tool_summary_todo_write,
        "ping_host" to R.string.tool_summary_ping_host,
        "dns_lookup" to R.string.tool_summary_dns_lookup,
        "get_public_ip" to R.string.tool_summary_get_public_ip,
        "download" to R.string.tool_summary_download,
        "json_pretty" to R.string.tool_summary_json_pretty,
        "hash_text" to R.string.tool_summary_hash_text,
        "generate_password" to R.string.tool_summary_generate_password,
        "record_experience" to R.string.tool_summary_record_experience,
        "recall_experience" to R.string.tool_summary_recall_experience,
        "take_photo" to R.string.tool_summary_take_photo,
        "cover_generation" to R.string.tool_summary_cover_generation,
        "plugin_market_search" to R.string.tool_summary_plugin_market_search,
        "plugin_market_install" to R.string.tool_summary_plugin_market_install,
        "plugin_market_uninstall" to R.string.tool_summary_plugin_market_uninstall,
        "plugin_market_set_enabled" to R.string.tool_summary_plugin_market_set_enabled,
        "find_tools" to R.string.tool_summary_find_tools,
        "device_shell" to R.string.tool_summary_device_shell,
        "terminal_exec" to R.string.tool_summary_terminal_exec,
        "termux_exec" to R.string.tool_summary_termux_exec,
    )

    private fun prefixVerb(toolName: String): String? = when {
        toolName.startsWith("browser_") -> "Used the browser"
        toolName.startsWith("workspace_") -> "Used the workspace"
        toolName.startsWith("scheduled_task_") -> "Managed scheduled tasks"
        toolName.startsWith("quick_note_") -> "Managed notes"
        toolName.startsWith("resource_") -> "Managed resources"
        toolName.startsWith("subagent_") -> "Managed subtasks"
        toolName.startsWith("channel_") -> "Managed channels"
        toolName.startsWith("clipboard_") -> "Used the clipboard"
        toolName.startsWith("mcp_") -> "Called an MCP tool"
        else -> null
    }

    // I18N-01: 前缀动词资源 ID(与 prefixVerb 一一对应)。
    private fun prefixVerbId(toolName: String): Int? = when {
        toolName.startsWith("browser_") -> R.string.tool_summary_prefix_browser
        toolName.startsWith("workspace_") -> R.string.tool_summary_prefix_workspace
        toolName.startsWith("scheduled_task_") -> R.string.tool_summary_prefix_scheduled_task
        toolName.startsWith("quick_note_") -> R.string.tool_summary_prefix_quick_note
        toolName.startsWith("resource_") -> R.string.tool_summary_prefix_resource
        toolName.startsWith("subagent_") -> R.string.tool_summary_prefix_subagent
        toolName.startsWith("channel_") -> R.string.tool_summary_prefix_channel
        toolName.startsWith("clipboard_") -> R.string.tool_summary_prefix_clipboard
        toolName.startsWith("mcp_") -> R.string.tool_summary_prefix_mcp
        else -> null
    }

    private fun defaultVerb(isSuccess: Boolean) = if (isSuccess) "Called a tool" else "Tool call failed"

    // ── 目标对象(从参数或结果里抠出关键词) ───────────────────────────────

    private fun targetFor(toolName: String, arguments: String, result: String): String {
        val args = parseArgs(arguments)
        val raw: String? = when (toolName) {
            "web_search" -> argString(args, "query")
            "search_memory" -> argString(args, "query")
            "read_file", "write_file", "workspace_write", "workspace_edit", "workspace_read" ->
                argString(args, "path") ?: argString(args, "file_path")
            "list_files" -> argString(args, "path") ?: argString(args, "directory")
            "open_url" -> argString(args, "url")
            "generate_image" -> argString(args, "prompt")
            "generate_video" -> argString(args, "prompt")
            "execute_shell", "run_command" -> argString(args, "command")
            "execute_code" -> argString(args, "language")
            "translate" -> argString(args, "text")
            "get_weather" -> argString(args, "city") ?: argString(args, "location")
            "open_app" -> argString(args, "package_name") ?: argString(args, "app")
            "make_phone_call" -> argString(args, "phone_number") ?: argString(args, "number")
            "send_sms" -> argString(args, "phone_number") ?: argString(args, "to")
            "send_email" -> argString(args, "to")
            "add_calendar_event", "scheduled_task_create" -> argString(args, "title")
            "schedule_reminder" -> argString(args, "content") ?: argString(args, "text")
            "set_alarm" -> argString(args, "label")
            "share_text" -> argString(args, "text")
            "notify" -> argString(args, "title")
            "quick_note_add", "quick_note_update" -> argString(args, "content") ?: argString(args, "title")
            "delegate_agent", "subagent_task" -> argString(args, "task") ?: argString(args, "prompt")
            "calculator" -> argString(args, "expression")
            "speak_text" -> argString(args, "text")
            "plugin_market_search" -> argString(args, "query")
            "plugin_market_install" -> argString(args, "plugin_id")
            "ping_host" -> argString(args, "host")
            "dns_lookup" -> argString(args, "domain")
            "download" -> argString(args, "url")
            "take_photo" -> ""
            "pin_memory", "unpin_memory" -> argString(args, "query") ?: result.take(30)
            "save_memory" -> argString(args, "content") ?: result.take(30)
            "delete_memory" -> argString(args, "match") ?: argString(args, "id") ?: result.take(30)
            "find_tools" -> argString(args, "query")
            "device_shell" -> argString(args, "command")
            "terminal_exec" -> argString(args, "command")
            "termux_exec" -> argString(args, "command")
            else -> genericTarget(args)
        }
        return raw?.let { clean(it) }.orEmpty()
    }

    private fun genericTarget(args: JSONObject?): String? {
        if (args == null) return null
        val keys = listOf(
            "query", "path", "url", "title", "name", "text", "content",
            "prompt", "command", "keyword", "id",
        )
        for (k in keys) {
            if (args.has(k)) {
                val v = args.opt(k)
                if (v is String && v.isNotBlank()) return v
            }
        }
        return null
    }

    private fun argString(args: JSONObject?, key: String): String? {
        if (args == null || !args.has(key)) return null
        return when (val v = args.opt(key)) {
            is String -> v
            is Number, is Boolean -> v.toString()
            is JSONArray -> {
                val sb = StringBuilder("[")
                val n = minOf(v.length(), 3)
                for (i in 0 until n) {
                    if (i > 0) sb.append(", ")
                    sb.append(v.opt(i))
                }
                if (v.length() > 3) sb.append("…")
                sb.append("]").toString()
            }
            is JSONObject -> v.toString()
            null, JSONObject.NULL -> null
            else -> v.toString()
        }
    }

    private fun parseArgs(arguments: String): JSONObject? {
        val t = arguments.trim()
        if (t.isEmpty() || !t.startsWith("{")) return null
        return runCatching { JSONObject(t) }.getOrNull()
    }

    private fun clean(s: String): String {
        val one = s.replace('\n', ' ').trim()
        return if (one.length > 40) one.take(40) + "…" else one
    }

    // ── 中文标签 ─────────────────────────────────────────────────────────

    private val labels: Map<String, String> = mapOf(
        "web_search" to "Web search",
        "search_memory" to "Memory search",
        "read_file" to "Read file",
        "write_file" to "Write file",
        "list_files" to "List files",
        "execute_code" to "Code execution",
        "execute_javascript" to "Script execution",
        "execute_shell" to "Shell command",
        "open_url" to "Open web page",
        "generate_image" to "Image generation",
        "generate_video" to "Video generation",
        "get_current_time" to "Current time",
        "calendar_today" to "View calendar",
        "schedule_reminder" to "Reminder",
        "set_alarm" to "Alarm",
        "set_timer" to "Timer",
        "get_device_info" to "Device info",
        "list_installed_apps" to "App list",
        "open_app" to "Open app",
        "make_phone_call" to "Phone call",
        "send_sms" to "Send text message",
        "get_location" to "Get location",
        "clipboard_read" to "Read clipboard",
        "clipboard_write" to "Write clipboard",
        "quick_note_add" to "New note",
        "quick_note_list" to "Note list",
        "delegate_agent" to "Delegate to sub-assistant",
        "subagent_task" to "Subtask",
        "notify" to "Send notification",
        "calculator" to "Calculator",
        "translate" to "Translation",
        "speak_text" to "Text-to-speech",
        "get_weather" to "Weather lookup",
        "ping_host" to "Ping",
        "dns_lookup" to "DNS lookup",
        "mcp_tool" to "MCP tool",
        "show_card" to "Show card",
    )

    // I18N-01: 标签资源 ID(生产路径优先;labels 为 res==null 兜底的存量文案,新增工具只登记资源)。
    private val labelIds: Map<String, Int> = mapOf(
        "web_search" to R.string.tool_label_web_search,
        "search_memory" to R.string.tool_label_search_memory,
        "read_file" to R.string.tool_label_read_file,
        "write_file" to R.string.tool_label_write_file,
        "workspace_write" to R.string.tool_label_workspace_write,
        "workspace_edit" to R.string.tool_label_workspace_edit,
        "list_files" to R.string.tool_label_list_files,
        "execute_code" to R.string.tool_label_execute_code,
        "execute_javascript" to R.string.tool_label_execute_javascript,
        "execute_node_script" to R.string.tool_label_execute_javascript,
        "execute_shell" to R.string.tool_label_execute_shell,
        "open_url" to R.string.tool_label_open_url,
        "generate_image" to R.string.tool_label_generate_image,
        "generate_video" to R.string.tool_label_generate_video,
        "get_current_time" to R.string.tool_label_get_current_time,
        "calendar_today" to R.string.tool_label_calendar_today,
        "schedule_reminder" to R.string.tool_label_schedule_reminder,
        "set_alarm" to R.string.tool_label_set_alarm,
        "set_timer" to R.string.tool_label_set_timer,
        "get_device_info" to R.string.tool_label_get_device_info,
        "list_installed_apps" to R.string.tool_label_list_installed_apps,
        "open_app" to R.string.tool_label_open_app,
        "make_phone_call" to R.string.tool_label_make_phone_call,
        "send_sms" to R.string.tool_label_send_sms,
        "get_location" to R.string.tool_label_get_location,
        "clipboard_read" to R.string.tool_label_clipboard_read,
        "clipboard_write" to R.string.tool_label_clipboard_write,
        "quick_note_add" to R.string.tool_label_quick_note_add,
        "quick_note_list" to R.string.tool_label_quick_note_list,
        "delegate_agent" to R.string.tool_label_delegate_agent,
        "subagent_task" to R.string.tool_label_subagent_task,
        "notify" to R.string.tool_label_notify,
        "calculator" to R.string.tool_label_calculator,
        "translate" to R.string.tool_label_translate,
        "speak_text" to R.string.tool_label_speak_text,
        "get_weather" to R.string.tool_label_get_weather,
        "ping_host" to R.string.tool_label_ping_host,
        "dns_lookup" to R.string.tool_label_dns_lookup,
        "mcp_tool" to R.string.tool_label_mcp_tool,
        "show_card" to R.string.tool_label_show_card,
        "plugin_market_search" to R.string.tool_label_plugin_market_search,
        "plugin_market_install" to R.string.tool_label_plugin_market_install,
        "plugin_market_uninstall" to R.string.tool_label_plugin_market_uninstall,
        "plugin_market_set_enabled" to R.string.tool_label_plugin_market_set_enabled,
        "find_tools" to R.string.tool_label_find_tools,
        "device_shell" to R.string.tool_label_device_shell,
        "terminal_exec" to R.string.tool_label_terminal_exec,
        "termux_exec" to R.string.tool_label_termux_exec,
    )

    private fun prettify(name: String): String = name.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
}
