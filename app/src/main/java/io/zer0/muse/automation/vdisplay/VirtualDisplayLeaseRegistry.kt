package io.zer0.muse.automation.vdisplay

/**
 * 虚拟屏兼容 display 的进程内 lease 台账。
 *
 * 多个可恢复 workflow 可以引用同一个 `ensureDisplay` 屏幕，但任一 workflow
 * 释放时不能直接销毁其他 workflow 仍在使用的 display。只有最后一个 owner
 * 释放时，调用方才应执行远端 destroy。
 */
class VirtualDisplayLeaseRegistry {

    private val ownersByDisplay = mutableMapOf<Int, MutableSet<String>>()
    private val displayByRun = mutableMapOf<String, Int>()
    private val previousDisplaysByRun = mutableMapOf<String, LinkedHashSet<Int>>()

    @Synchronized
    fun acquire(runId: String, displayId: Int): Boolean {
        require(runId.isNotBlank()) { "runId must not be empty" }
        require(displayId >= 0) { "displayId is invalid" }

        val previous = displayByRun[runId]
        if (previous == displayId) return true
        if (previous != null) {
            val aliases = previousDisplaysByRun.getOrPut(runId) { linkedSetOf() }
            aliases += previous
            while (aliases.size > MAX_PREVIOUS_DISPLAY_ALIASES) aliases.remove(aliases.first())
            ownersByDisplay[previous]?.remove(runId)
            ownersByDisplay[previous]?.takeIf { it.isEmpty() }?.let { ownersByDisplay.remove(previous) }
        }
        previousDisplaysByRun[runId]?.remove(displayId)
        displayByRun[runId] = displayId
        ownersByDisplay.getOrPut(displayId) { linkedSetOf() }.add(runId)
        return true
    }

    /**
     * 释放一个 run 对 display 的引用。
     *
     * @return true 表示该 display 已无其他 owner，调用方可以销毁远端 display。
     */
    @Synchronized
    fun release(runId: String, displayId: Int): Boolean {
        if (displayByRun[runId] != displayId) return false
        displayByRun.remove(runId)
        previousDisplaysByRun.remove(runId)
        val owners = ownersByDisplay[displayId] ?: return true
        owners.remove(runId)
        if (owners.isEmpty()) {
            ownersByDisplay.remove(displayId)
            return true
        }
        return false
    }

    @Synchronized
    fun displayFor(runId: String): Int? = displayByRun[runId]

    /** A stale ID is accepted only when it was previously leased by the same workflow run. */
    @Synchronized
    fun isKnownDisplayId(runId: String, displayId: Int): Boolean =
        displayByRun[runId] == displayId || displayId in previousDisplaysByRun[runId].orEmpty()

    private companion object {
        const val MAX_PREVIOUS_DISPLAY_ALIASES = 4
    }
}
