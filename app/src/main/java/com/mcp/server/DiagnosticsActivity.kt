package com.mcp.server

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcp.server.server.Diagnostics
import com.mcp.server.server.LogStore
import com.mcp.server.server.McpServerService
import com.mcp.server.server.ServiceStatus
import com.mcp.server.server.Settings
import com.mcp.server.ui.theme.McpTheme
import kotlinx.coroutines.delay

class DiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { McpTheme { DiagnosticsScreen(onBack = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    // 每秒重新读取一次
    val status = remember(tick) { ServiceStatus.evaluate(context) }
    val relay = remember(tick) { McpServerService.relayState }
    val req = remember(tick) { Diagnostics.lastRequest }
    val call = remember(tick) { Diagnostics.lastToolCall }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("诊断", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Section("本地服务") {
                InfoLine("状态", status.title)
                InfoLine("已运行", if (McpServerService.running) formatDuration(McpServerService.uptimeSeconds()) else "—")
                InfoLine("本地端点", if (McpServerService.localRunning) "127.0.0.1:${Settings.port(context)}（仅本机）" else "未运行")
                InfoLine("上次启动失败", Diagnostics.lastStartFailureAt(context).let {
                    if (it > 0) "${formatTime(it)} · ${Diagnostics.lastStartFailureReason(context)}" else "无"
                })
                for (p in status.problems) Text("· $p", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                for (h in status.hints) Text("· $h", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("中继连接") {
                InfoLine("开关", if (Settings.relayEnabled(context)) "已开启" else "已关闭")
                InfoLine("当前状态", relay?.let { "${phaseName(it.phase)} · ${it.message}" } ?: "未运行")
                if (relay != null && relay.connectedSince > 0) InfoLine("本次连接开始", formatTime(relay.connectedSince))
                if (relay != null && relay.nextRetryAt > 0) InfoLine("下次重连", "${secondsUntil(relay.nextRetryAt)} 秒后")
                InfoLine("累计重连", "${relay?.reconnects ?: 0} 次")
                InfoLine("最近一次成功连接", formatTime(Diagnostics.lastRelaySuccessAt(context)))
                InfoLine("最近一次失败", Diagnostics.lastRelayFailureAt(context).let {
                    if (it > 0) formatTime(it) else "无"
                })
                val reason = Diagnostics.lastRelayFailureReason(context)
                if (reason.isNotEmpty()) InfoLine("失败原因", reason)
            }

            Section("最近一次请求") {
                if (req == null) {
                    InfoLine("请求", "暂无")
                } else {
                    InfoLine("时间", formatTime(req.at))
                    InfoLine("结果", "${req.path} · HTTP ${req.status}")
                    InfoLine("手机内处理", "%.1f 毫秒".format(req.handleMs))
                    InfoLine("其中工具执行", "%.1f 毫秒".format(req.toolMs))
                }
                if (call != null) {
                    InfoLine("最近一次工具", "${call.name} · ${"%.1f".format(call.ms)} 毫秒 · ${if (call.ok) "成功" else "出错"}")
                }
                Text("中继转发和网络往返的耗时记录在 VPS 中继日志和响应头 Server-Timing 中。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Section("日志导出") {
                Text("导出内容只包含应用版本、Android 版本和运行日志；不含访问令牌、设备密钥、设备型号和应用使用记录。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        copyText(context, LogStore.exportText())
                        toast(context, "已复制日志")
                    }) { Text("复制日志") }
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, LogStore.exportText())
                        context.startActivity(Intent.createChooser(send, "分享诊断日志"))
                    }) { Text("分享…") }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

private fun phaseName(p: com.mcp.server.core.RelayClient.Phase): String = when (p) {
    com.mcp.server.core.RelayClient.Phase.STOPPED -> "已停止"
    com.mcp.server.core.RelayClient.Phase.CONNECTING -> "连接中"
    com.mcp.server.core.RelayClient.Phase.CONNECTED -> "已连接"
    com.mcp.server.core.RelayClient.Phase.WAITING_RETRY -> "等待重连"
    com.mcp.server.core.RelayClient.Phase.WAITING_NETWORK -> "等待网络"
    com.mcp.server.core.RelayClient.Phase.REJECTED -> "被拒绝"
    com.mcp.server.core.RelayClient.Phase.REPLACED -> "被其他设备顶替"
}

private fun formatDuration(sec: Long): String = when {
    sec < 60 -> "$sec 秒"
    sec < 3600 -> "${sec / 60} 分钟"
    else -> "${sec / 3600} 小时 ${(sec % 3600) / 60} 分钟"
}
