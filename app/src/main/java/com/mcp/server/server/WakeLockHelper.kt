package com.mcp.server.server

import android.content.Context
import android.os.PowerManager

/**
 * WakeLock 管理。
 * - [withRequestWakeLock]：处理中继转发来的单个请求时持有，最长 10 秒，处理完立即释放（默认行为）。
 *   熄屏时网络包会短暂唤醒 CPU，这把锁保证请求能处理完再休眠。
 * - [acquire]：可选的常驻 WakeLock（设置中默认关闭，耗电明显，只建议在充电或特殊场景使用）。
 */
object WakeLockHelper {

    fun <T> withRequestWakeLock(context: Context, timeoutMs: Long = 10_000L, block: () -> T): T {
        val wl = try {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "McpServer:Request")
                .apply { setReferenceCounted(false); acquire(timeoutMs) }
        } catch (_: Exception) {
            null
        }
        try {
            return block()
        } finally {
            try { if (wl?.isHeld == true) wl.release() } catch (_: Exception) {}
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    @Synchronized
    fun acquire(context: Context) {
        release()
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "McpServer:KeepRunning"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
            wakeLock = null
        }
    }

    @Synchronized
    fun release() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    @Synchronized
    fun isHeld(): Boolean = try {
        wakeLock?.isHeld == true
    } catch (_: Exception) {
        false
    }
}
