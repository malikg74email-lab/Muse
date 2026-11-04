package io.zer0.muse.tools

/**
 * P1-3b 拆域：系统/设备工具注册器。
 *
 * 注册 screen_time / open_system_setting / toggle_wifi / toggle_bluetooth /
 * send_email / get_battery_info / get_recent_notifications / open_url /
 * list_installed_apps / get_network_info / get_storage_info / get_memory_info /
 * get_display_info / get_cpu_info / get_sensors_list / get_brightness /
 * set_brightness / get_volume / set_volume / toggle_flashlight / vibrate /
 * get_foreground_app / get_wifi_info / get_bluetooth_devices。
 * 实现位于 [SystemToolsImpl.kt]。
 */
class SystemToolsRegistrar(
    private val context: android.content.Context,
    private val toolRegistry: ToolRegistry,
) {
    private val impl = SystemToolsImpl(context)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "screen_time",
                description = "Get today's per-app screen usage time statistics (top 10). Requires the PACKAGE_USAGE_STATS permission.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { impl.execScreenTime(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "open_system_setting",
                description = "Open a system settings page, with category jumps supported.",
                parameters = mapOf("category" to "Optional, settings category: wifi/bluetooth/display/sound/app_settings etc., default settings"),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execOpenSystemSetting(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "toggle_wifi",
                // A-02: 与实现对齐 — enabled 直接指定目标状态;不传则只读查询当前状态
                // (Android 10+ 无法程序化翻转 WiFi,删除"翻转"承诺)
                description = "Toggle WiFi. Pass enabled=true to turn on / false to turn off; omit it to query the current state. Turning on/off requires jumping to the system settings page for the user to confirm.",
                parameters = mapOf("enabled" to "Optional, true=on / false=off; omit to query the current state (compatible parameter action: on/off/status)"),
                required = emptySet(),
                category = "built-in",
                // P0-3: 与 ToolPermissionResolver 显式表一致(HIGH)— 不可逆系统状态变更
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execToggleWifi(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "toggle_bluetooth",
                // A-02: 与实现对齐,删除"翻转"承诺
                description = "Toggle Bluetooth. Pass enabled=true to turn on / false to turn off; omit to query the current state. On Android 10+ it requires jumping to the system settings page for the user to confirm.",
                parameters = mapOf("enabled" to "Optional, true=on / false=off; omit to query the current state (compatible parameter action: on/off/status)"),
                required = emptySet(),
                category = "built-in",
                // P0-3: 与 ToolPermissionResolver 显式表一致(HIGH)— 不可逆系统状态变更
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execToggleBluetooth(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "send_email",
                description = "Send an email through the system email app.",
                parameters = mapOf(
                    "to" to "Required, recipient email(s), multiple separated by commas",
                    "subject" to "Optional, subject",
                    "body" to "Optional, body",
                ),
                required = setOf("to"),
                category = "built-in",
                // P0-3: 与 ToolPermissionResolver 显式表一致(HIGH)— 跨设备通讯副作用
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execSendEmail(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_battery_info",
                description = "Get the battery level and charging state.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetBatteryInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_recent_notifications",
                description = "Get recently received app notifications. Requires notification access.",
                parameters = mapOf(
                    "limit" to "Optional, number to return, default 20",
                    "package_name" to "Optional, filter by package name",
                    "query" to "Optional, search by app package name, app name, title, channel or body",
                    "unread_only" to "Optional, true/false, only return notifications not yet viewed in Shaheen 2",
                    "active_only" to "Optional, true/false, only return notifications still in the system notification bar",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execGetRecentNotifications(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "open_url",
                description = "Open a URL in the system browser.",
                parameters = mapOf("url" to "Required, http/https link"),
                required = setOf("url"),
                category = "built-in",
                // P0-3: 与 ToolPermissionResolver 显式表一致(HIGH)— 外链隐私风险
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execOpenUrl(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "list_installed_apps",
                description = "List installed apps, with filtering and count limits supported.",
                parameters = mapOf(
                    "filter" to "Optional, name filter keyword",
                    "limit" to "Optional, number to return, default 20",
                    "include_system" to "Optional, whether to include system apps",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execListInstalledApps(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_network_info",
                description = "Get the current network connection info.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetNetworkInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_storage_info",
                description = "Get storage space usage.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetStorageInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_memory_info",
                description = "Get memory usage.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetMemoryInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_display_info",
                description = "Get screen resolution, refresh rate and similar info.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetDisplayInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_cpu_info",
                description = "Get CPU model, core count and similar info.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetCpuInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_sensors_list",
                description = "List the device's available sensors.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execGetSensorsList(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_brightness",
                description = "Get the current screen brightness.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetBrightness(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "set_brightness",
                // A-03: 实现已支持 auto 分支;required 去掉 value(自动模式不需要)
                description = "Set the screen brightness. Requires the system-settings write permission. auto=true enables automatic brightness (value is then ignored); otherwise pass value (0-255) to set manual brightness.",
                parameters = mapOf(
                    "value" to "Optional (ignored in auto mode), manual brightness 0-255",
                    "auto" to "Optional, pass true to enable automatic brightness (value is then ignored)",
                ),
                required = emptySet(),
                category = "built-in",
                // P0-3: 与 ToolPermissionResolver 显式表一致(HIGH)— 不可逆系统状态变更
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execSetBrightness(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_volume",
                description = "Get the volume of the specified audio stream.",
                parameters = mapOf("stream" to "Optional, music/ring/alarm/notification/call/system"),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args -> impl.execGetVolume(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "set_volume",
                description = "Set the volume of the specified audio stream.",
                parameters = mapOf(
                    "value" to "Required, volume 0-100",
                    "stream" to "Optional, music/ring/alarm/notification/call/system",
                ),
                required = setOf("value"),
                category = "built-in",
                // P0-3: 与 ToolPermissionResolver 显式表一致(HIGH)— 不可逆系统状态变更
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execSetVolume(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "toggle_flashlight",
                // A-02: 与实现对齐 — enabled 直接指定目标状态;不传则查询本会话最后已知状态
                description = "Toggle the flashlight (requires camera permission). Pass enabled=true to turn on / false to turn off; omit to query the current state.",
                parameters = mapOf("enabled" to "Optional, true=on / false=off; omit to query the state (compatible parameter action: on/off/status)"),
                required = emptySet(),
                category = "built-in",
                // P0-3: 与 ToolPermissionResolver 显式表一致(HIGH)— 不可逆系统状态变更
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execToggleFlashlight(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "vibrate",
                description = "Trigger device vibration.",
                parameters = mapOf(
                    "duration_ms" to "Optional, vibration duration, default 500",
                    "repeat" to "Optional, whether to repeat",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execVibrate(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_foreground_app",
                description = "Get the current foreground app package name. Requires the usage-stats permission.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { impl.execGetForegroundApp(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_wifi_info",
                description = "Get the current WiFi connection info.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetWifiInfo(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_bluetooth_devices",
                description = "Get the list of paired Bluetooth devices.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetBluetoothDevices(emptyMap()) }
    }
}
