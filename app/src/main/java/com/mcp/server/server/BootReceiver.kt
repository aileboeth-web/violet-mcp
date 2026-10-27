package com.mcp.server.server

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.mcp.server.MainActivity

/**
 * 开机（首次解锁后）和应用更新后恢复服务。
 * 只有“开机自启”开启、且用户上次没有手动停止服务时才恢复。
 * specialUse 前台服务允许从 BOOT_COMPLETED / MY_PACKAGE_REPLACED 启动；
 * 但部分厂商系统仍可能拦截，失败时记录原因并发一条普通通知提醒用户手动打开。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        if (!Settings.autoStart(context) || !Settings.desiredRunning(context)) return
        try {
            context.startForegroundService(
                Intent(context, McpServerService::class.java).setAction(McpServerService.ACTION_RESTORE))
        } catch (e: Exception) {
            val reason = "开机自启被系统拦截（${e.javaClass.simpleName}）"
            Diagnostics.recordStartFailure(context, reason)
            LogStore.error(reason)
            notifyFailure(context)
        }
    }

    private fun notifyFailure(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel("mcp_alerts", "MCP 服务提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, Notification.Builder(context, "mcp_alerts")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("只读 MCP 服务未能自动启动")
            .setContentText("系统阻止了开机自启，点此打开应用后手动启动")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }
}
