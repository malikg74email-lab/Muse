package io.zer0.muse.automation.vdisplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.zer0.common.Logger
import io.zer0.muse.vdproto.VdContract

/**
 * v2.2.1 虚拟屏:服务端 Binder 手递手接收器(exported=true)。
 *
 * 服务端进程以 shell uid 运行,经 AMS 广播(定向到本应用包)送出 binder。
 * 只有 action 与宿主包名同时匹配才接受,防止第三方伪造。
 */
class VirtualDisplayBinderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != VdContract.ACTION_BINDER) return
        val host = intent.getStringExtra(VdContract.EXTRA_HOST_PACKAGE)
        if (host != context.packageName) {
            Logger.w(TAG, "Ignoring a virtual-screen binder broadcast with a mismatched source: host=$host")
            return
        }
        val binder = intent.extras?.getBinder(VdContract.EXTRA_BINDER) ?: return
        val handoffToken = intent.getStringExtra(VdContract.EXTRA_HANDOFF_TOKEN)
        if (!VirtualDisplayBinderRegistry.accept(binder, handoffToken)) {
            Logger.w(TAG, "Ignoring a virtual-screen binder broadcast with a mismatched token")
            return
        }
        Logger.i(TAG, "Virtual screen server binder in place")
    }

    companion object {
        private const val TAG = "VdBinderReceiver"
    }
}
