package io.zer0.memory.observe

import io.zer0.common.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * D3-P1: 记忆管线运行日志(JSONL)。
 *
 * 轻量、文件形式、不动 DB schema:
 *  - 每步运行追加一行 JSON 到 [file](默认 `filesDir/memory/pipeline_log.jsonl`)
 *  - 文件超过 [maxBytes] 时滚动到 `.1`(只保留一份历史,总量有界)
 *  - 所有 IO 失败只记日志、绝不上抛 —— 观测不能拖垮管线
 *
 * 线程安全:写入路径用 [lock] 串行化(append 与 rotate 是读-改-写整体)。
 */
class PipelineLog(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()

    init {
        // 父目录不存在时补齐,失败不致命
        runCatching { file.parentFile?.mkdirs() }
    }

    /** 追加一条运行记录。永不抛异常。 */
    fun append(record: PipelineLogRecord) {
        val line = runCatching { json.encodeToString(PipelineLogRecord.serializer(), record) }.getOrNull() ?: return
        synchronized(lock) {
            runCatching {
                rotateIfNeeded()
                file.appendText(line + "\n")
            }.onFailure {
                // 观测失败不影响管线
                Logger.w(TAG, "pipeline_log write failed: ${it.message}")
            }
        }
    }

    /** 从 [MemoryStepRunner.StepResult] 构造并追加记录(便捷入口)。 */
    fun appendStep(
        stage: MemoryStage,
        step: String,
        trigger: String,
        ok: Boolean,
        durationMs: Long,
        failureKind: FailureKind?,
        error: Throwable?,
        scope: String? = null,
        spaceId: String? = null,
        detail: String? = null,
        counters: StepCounters? = null,
    ) {
        append(
            PipelineLogRecord(
                ts = clock(),
                stage = stage.name,
                step = step,
                trigger = trigger,
                ok = ok,
                durationMs = durationMs,
                failureKind = failureKind?.name,
                errorType = error?.let { it::class.java.simpleName },
                errorMsg = error?.message?.let { truncate(it) },
                scope = scope,
                spaceId = spaceId,
                detail = detail,
                total = counters?.total,
                failures = counters?.failures,
            ),
        )
    }

    /** 当前日志文件路径(测试/诊断用)。 */
    val path: String get() = file.absolutePath

    private fun rotateIfNeeded() {
        if (!file.exists() || file.length() <= maxBytes) return
        val rotated = File(file.parentFile, file.name + ROTATED_SUFFIX)
        runCatching { if (rotated.exists()) rotated.delete() }
        if (!file.renameTo(rotated)) {
            // rename 失败:截断当前文件保底,避免无限增长
            Logger.w(TAG, "pipeline_log roll failed; truncating the current file")
            file.writeText("")
        }
    }

    private fun truncate(msg: String): String = if (msg.length <= MAX_ERROR_MSG_CHARS) msg else msg.take(MAX_ERROR_MSG_CHARS) + "…"

    companion object {
        private const val TAG = "PipelineLog"
        private const val ROTATED_SUFFIX = ".1"
        private const val MAX_ERROR_MSG_CHARS = 300

        /** 默认上限 512KB,超出滚动为 `.1`,总量约 1MB 有界。 */
        const val DEFAULT_MAX_BYTES: Long = 512L * 1024

        /**
         * 在给定目录下创建日志(自动补 `memory/` 子目录)。
         * @param memoryDir 记忆目录(如 `filesDir/memory`)
         */
        fun create(memoryDir: File, maxBytes: Long = DEFAULT_MAX_BYTES): PipelineLog =
            PipelineLog(File(memoryDir, "pipeline_log.jsonl"), maxBytes)
    }
}

/**
 * 一条管线运行记录。字段尽量扁平,便于后续用脚本聚合(按 stage/step/failureKind)。
 */
@Serializable
data class PipelineLogRecord(
    /** 毫秒时间戳。 */
    val ts: Long,
    /** [MemoryStage] 名称。 */
    val stage: String,
    /** 步骤键(与 MemoryTicker.STEP_KEYS 对齐,如 compileFacts)。 */
    val step: String,
    /** 触发来源:threshold / session_end / manual / backfill / daily / force / turn。 */
    val trigger: String,
    /** 是否成功。 */
    val ok: Boolean,
    /** 耗时(毫秒)。 */
    val durationMs: Long,
    /** [FailureKind] 名称;仅失败时非空。 */
    val failureKind: String? = null,
    /** 异常简单类名。 */
    val errorType: String? = null,
    /** 异常消息(截断到 300 字)。 */
    val errorMsg: String? = null,
    /** 记忆作用域。 */
    val scope: String? = null,
    /** 记忆空间。 */
    val spaceId: String? = null,
    /** 步骤附加说明(如 rollingSummary 的 changed/unchanged)。 */
    val detail: String? = null,
    /** 该步骤累计运行次数(含本次)。 */
    val total: Int? = null,
    /** 该步骤累计失败次数(含本次)。 */
    val failures: Int? = null,
)
