package io.zer0.muse.tools

/**
 * P1-3b 拆域：手机端工具注册器。
 *
 * 注册 set_alarm / set_timer / open_app / share_text / get_location /
 * get_device_info / get_contacts_count / get_contacts_list / send_sms /
 * add_contact / make_phone_call / open_maps。
 * 实现位于 [PhoneToolsImpl.kt]（原样迁移的顶层函数）。
 */
class PhoneToolsRegistrar(
    private val context: android.content.Context,
    private val toolRegistry: ToolRegistry,
) {
    private val impl = PhoneToolsImpl(context)

    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "set_alarm",
                description = "Set a system alarm (via AlarmClock.ACTION_SET_ALARM, handled by the system clock app).",
                parameters = mapOf(
                    "hour" to "Required, alarm hour (0-23)",
                    "minute" to "Required, alarm minute (0-59)",
                    "label" to "Optional, alarm label, default 'Shaheen 2 alarm'",
                    "days_of_week" to "Optional, weekly repeat, e.g. MON,TUE,WED,THU,FRI",
                    "weekdays" to "Optional, quick workday repeat (Monday to Friday); passing true expands to MON-FRI, taking priority over days_of_week",
                    "weekends" to "Optional, quick weekend repeat (Saturday and Sunday); passing true expands to SAT,SUN, taking priority over days_of_week",
                ),
                required = setOf("hour", "minute"),
                category = "built-in",
                // P0-3: 与 ToolPermissionResolver 显式表一致(HIGH)— 系统闹钟变更不可逆
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execSetAlarm(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "set_timer",
                description = "Set a system countdown timer (via AlarmClock.ACTION_SET_TIMER, handled by the system clock app).",
                parameters = mapOf(
                    "seconds" to "Required, countdown seconds",
                    "label" to "Optional, countdown label, default 'Shaheen 2 timer'",
                ),
                required = setOf("seconds"),
                category = "built-in",
                // P0-3: 与 ToolPermissionResolver 显式表一致(HIGH)— 系统倒计时变更不可逆
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execSetTimer(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "open_app",
                description = "Open the specified app (launch its main Activity by package name), or jump via a Deep Link/custom action.",
                parameters = mapOf(
                    "packageName" to "Optional, app package name, e.g. com.tencent.mm (WeChat)/com.tencent.mobileqq (QQ)",
                    "action" to "Optional, custom action (e.g. com.example.MY_ACTION)",
                    "data_uri" to "Optional, Deep Link URI, e.g. myapp://page/123",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execOpenApp(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "share_text",
                description = "Share text through the system share panel (pops up the chooser).",
                parameters = mapOf(
                    "text" to "Required, the text to share",
                    "title" to "Optional, share panel title",
                ),
                required = setOf("text"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execShareText(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_location",
                description = "Get the device's current location (latitude/longitude). Requires location permission.",
                parameters = mapOf(
                    "provider" to "Optional, gps/network/passive, default automatic",
                    "timeout" to "Optional, timeout in milliseconds, default 5000",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execGetLocation(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_device_info",
                description = "Get device info: brand/model/Android version/screen resolution/battery.",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetDeviceInfo(emptyMap()) }

        toolRegistry.registerOutcome(
            ToolRegistry.ToolDef(
                name = "get_device_info",
                description = "Get device info (structured version).",
                parameters = emptyMap(),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { impl.execGetDeviceInfoOutcome(emptyMap()) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_contacts_count",
                description = "Get the number of contacts. Requires the READ_CONTACTS permission.",
                parameters = mapOf("filter" to "Optional, filter by name"),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execGetContactsCount(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "get_contacts_list",
                description = "Get the contact list. Requires the READ_CONTACTS permission.",
                parameters = mapOf(
                    "limit" to "Optional, number to return, default 20",
                    "filter" to "Optional, filter by name",
                ),
                required = emptySet(),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args -> impl.execGetContactsList(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "send_sms",
                description = "Send an SMS. Requires the SEND_SMS permission.",
                parameters = mapOf(
                    "phone" to "Required, target phone number",
                    "message" to "Required, SMS content",
                ),
                required = setOf("phone", "message"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execSendSms(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "add_contact",
                description = "Add a contact. Requires the WRITE_CONTACTS permission.",
                parameters = mapOf(
                    "name" to "Required, contact name",
                    "phone" to "Optional, phone number",
                    "email" to "Optional, email",
                ),
                required = setOf("name"),
                category = "built-in",
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execAddContact(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "make_phone_call",
                description = "Open the system dialer with the phone number prefilled (does not dial directly).",
                parameters = mapOf("phone" to "Required, target phone number"),
                required = setOf("phone"),
                category = "built-in",
                // 电话副作用标记为 HIGH,让 ASK / STRICT 模式要求审批；
                // TRUSTED 模式依用户明确选择的全工具免确认设置执行。
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execMakePhoneCall(args) }

        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "open_maps",
                description = "Open a maps app to search for a place or navigate to the specified latitude/longitude.",
                parameters = mapOf(
                    "query" to "Optional, search keyword, e.g. Forbidden City Museum",
                    "lat" to "Optional, latitude",
                    "lng" to "Optional, longitude",
                ),
                required = emptySet(),
                category = "built-in",
                // P0-3: 与 ToolPermissionResolver 显式表一致(HIGH)— 外链地图隐私风险
                riskLevel = ToolRiskLevel.HIGH,
            ),
        ) { args -> impl.execOpenMaps(args) }
    }
}
