package io.zer0.muse.livecall

import io.zer0.ai.core.ToolDefinition
import io.zer0.common.AppJson
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shaheen 2 Fix Phase 2: 通话工具面测试 —— 通话里能「开口让 Shaheen 开 YouTube」
 * 靠的是这组声明进了 setup;破坏性工具被刻意排除在外也在这里钉死。
 */
class LiveCallToolBridgeTest {

    private fun def(name: String) = ToolDefinition(
        name = name,
        description = "desc of $name",
        parametersJsonSchema = """{"type":"object","properties":{"app":{"type":"string"}}}""",
    )

    private val registryLikeDefs = listOf(
        def("app_launch"),
        def("app_list"),
        def("open_app"),
        def("list_installed_apps"),
        def("screen_read"),
        def("screen_tap_text"),
        def("screen_input"),
        def("screen_swipe"),
        def("screen_back"),
        def("screen_home"),
        // 不该进通话面的:破坏性 / 无关工具
        def("app_uninstall"),
        def("app_force_stop"),
        def("execute_shell"),
        def("send_sms"),
        def("get_weather"),
    )

    @Test
    fun `voice subset contains launch and screen tools only`() {
        val selected = LiveCallToolBridge.selectVoiceTools(registryLikeDefs).map { it.name }
        assertEquals(
            listOf(
                "app_launch", "app_list", "open_app", "list_installed_apps",
                "screen_read", "screen_tap_text", "screen_input", "screen_swipe",
                "screen_back", "screen_home",
            ),
            selected,
        )
    }

    @Test
    fun `destructive and unrelated tools are excluded from the voice surface`() {
        assertFalse(LiveCallToolBridge.isVoiceTool("app_uninstall"))
        assertFalse(LiveCallToolBridge.isVoiceTool("app_force_stop"))
        assertFalse(LiveCallToolBridge.isVoiceTool("execute_shell"))
        assertFalse(LiveCallToolBridge.isVoiceTool("send_sms"))
        assertTrue(LiveCallToolBridge.isVoiceTool("screen_launch_app"))
    }

    @Test
    fun `declarations mirror the chat conversion - name description and parsed schema`() {
        val declarations = LiveCallToolBridge.buildVoiceDeclarations(
            listOf(def("app_launch")),
        )
        val decl = declarations.single()
        assertEquals("app_launch", decl.getValue("name").jsonPrimitive.contentOrNull)
        assertEquals("desc of app_launch", decl.getValue("description").jsonPrimitive.contentOrNull)
        val params = decl.getValue("parameters").jsonObject
        assertEquals("object", params.getValue("type").jsonPrimitive.contentOrNull)
        assertTrue(
            params.getValue("properties").jsonObject.containsKey("app"),
        )
    }

    @Test
    fun `declarations survive a broken schema by omitting parameters`() {
        val broken = ToolDefinition(
            name = "screen_read",
            description = "read screen",
            parametersJsonSchema = "{not json",
        )
        val decl = LiveCallToolBridge.toFunctionDeclarations(listOf(broken)).single()
        assertEquals("screen_read", decl.getValue("name").jsonPrimitive.contentOrNull)
        assertFalse(decl.containsKey("parameters"))
    }

    @Test
    fun `setup message embeds the voice tool declarations`() {
        val declarations = LiveCallToolBridge.buildVoiceDeclarations(registryLikeDefs)
        val setup = AppJson.parseToJsonElement(
            LiveCallConfig.buildSetupMessage(toolDeclarations = declarations),
        ).jsonObject.getValue("setup").jsonObject
        val tools = setup.getValue("tools").jsonArray
        val decls = tools.single().jsonObject.getValue("functionDeclarations").jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.contentOrNull }
        assertTrue("app_launch" in decls)
        assertTrue("screen_tap_text" in decls)
        assertFalse("app_uninstall" in decls)
    }
}
