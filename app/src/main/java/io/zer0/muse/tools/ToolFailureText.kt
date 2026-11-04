package io.zer0.muse.tools

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * v2.2.1: 工具失败反馈统一措辞。
 *
 * 动机:此前网络类工具的失败信息只带一句 `e.message`,模型经常看到"连接失败: timeout"
 * 这类无法自我纠正的模糊描述。统一助手把**请求地址**与**失败阶段**(超时/域名解析/TLS)
 * 一并写进结果,让模型(以及日志)能直接定位问题。
 *
 * 使用方:SkillBridge(http_get/http_post)、SkillSearchToolsImpl(HTTP GET/POST 技能)。
 * MCP 调用的失败反馈在 McpRegistry 内联(需附带 serverId,措辞不同)。
 */
internal object ToolFailureText {

    /**
     * 组合 HTTP 类失败的可见文案。
     *
     * @param prefix 动作名(如 "HTTP GET"、"http_post")
     * @param url 目标地址(原样透出,便于模型/用户核对)
     * @param cause 原始异常;null 视作未知网络异常
     */
    fun httpFailure(prefix: String, url: String, cause: Throwable?): String = when (cause) {
        is SocketTimeoutException, is InterruptedIOException ->
            "$prefix timed out (connect or read): $url"
        is UnknownHostException ->
            "$prefix domain resolution failed: $url"
        is SSLException ->
            "$prefix TLS handshake failed: $url"
        null ->
            "$prefix request failed: $url → unknown network exception"
        else ->
            "$prefix request failed: $url → ${cause.message ?: cause.javaClass.simpleName}"
    }
}
