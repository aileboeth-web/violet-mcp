package com.mcp.server

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.mcp.server.server.AppUpdater
import com.mcp.server.server.LogStore
import com.mcp.server.server.McpServerService
import com.mcp.server.ui.theme.McpTheme

class MainActivity : ComponentActivity() {

    /** 通知权限只影响前台服务通知是否可见：用户点“启动”时才询问，不论结果都启动服务 */
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { McpServerService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            McpTheme {
                MainScreen(
                    onStartServer = { startServer() },
                    onStopServer = { McpServerService.stop(this) },
                    onClearLogs = { LogStore.clear() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 1.4.0：上次检查的结果；到时间了（且不是手动模式）就再检查一次
        AppUpdater.restore(this)
        AppUpdater.checkIfDue(this)
    }

    private fun startServer() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            McpServerService.start(this)
        }
    }
}
