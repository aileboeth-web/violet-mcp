package com.mcp.server

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcp.server.core.RelayClient
import com.mcp.server.server.AppUpdater
import com.mcp.server.server.Diagnostics
import com.mcp.server.server.LogEntry
import com.mcp.server.server.LogStore
import com.mcp.server.server.McpServerService
import com.mcp.server.server.ServiceStatus
import com.mcp.server.server.Settings
import com.mcp.server.tools.ToolKit
import com.mcp.server.tools.UsageTools
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class StatusInfo(
    val service: ServiceStatus.Info? = null,
    val relayEnabled: Boolean = false,
    val relayHost: String = "",
    val relayState: RelayClient.RelayState? = null,
    val lastRelaySuccessAt: Long = 0L,
    val lastToolCall: Diagnostics.ToolCall? = null,
    val enabledTools: Int = 0,
    val totalTools: Int = 0,
    val usageAccess: Boolean = true,
    val tokenProblem: String? = null,
    val storage: Settings.CredentialStorage = Settings.CredentialStorage.ENCRYPTED,
    val update: AppUpdater.State = AppUpdater.State(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onClearLogs: () -> Unit = {},
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0=主页 1=日志
    var status by remember { mutableStateOf(StatusInfo()) }
    var logs by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var showLanNotice by remember { mutableStateOf(Settings.legacyLanAccessWasOn(context)) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // 每秒刷新状态和日志；凭据存储状态需要解密，每 5 秒检查一次
    LaunchedEffect(Unit) {
        var round = 0
        var storage = Settings.credentialStorage(context)
        while (true) {
            if (round++ % 5 == 0) storage = Settings.credentialStorage(context)
            status = StatusInfo(
                service = ServiceStatus.evaluate(context),
                relayEnabled = Settings.relayEnabled(context),
                relayHost = try { java.net.URI(Settings.relayUrl(context)).host ?: "" } catch (_: Exception) { "" },
                relayState = McpServerService.relayState,
                lastRelaySuccessAt = Diagnostics.lastRelaySuccessAt(context),
                lastToolCall = Diagnostics.lastToolCall,
                enabledTools = ToolKit.enabledCount(),
                totalTools = ToolKit.totalCount(),
                usageAccess = UsageTools.hasUsageAccess(context),
                tokenProblem = Settings.tokenProblem(context),
                storage = storage,
                update = AppUpdater.state,
            )
            logs = LogStore.snapshot()
            delay(1000)
        }
    }

    fun open(cls: Class<*>) {
        scope.launch { drawerState.close() }
        context.startActivity(Intent(context, cls))
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 28.dp),
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = "应用图标",
                        modifier = Modifier
                            .size(64.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                            .padding(10.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("只读 MCP 服务器", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "版本 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                DrawerItem("工具与权限", Icons.Outlined.Tune) { open(ToolsActivity::class.java) }
                DrawerItem("设置", Icons.Outlined.Settings) { open(SettingsActivity::class.java) }
                DrawerItem("安全", Icons.Outlined.Security) { open(SecurityActivity::class.java) }
                DrawerItem("诊断", Icons.Outlined.BugReport) { open(DiagnosticsActivity::class.java) }
                DrawerItem("关于应用", Icons.Default.Info) { open(AboutActivity::class.java) }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (selectedTab == 0) "只读 MCP 服务器" else "运行日志", fontWeight = FontWeight.SemiBold)
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "菜单")
                        }
                    },
                    actions = {
                        if (selectedTab == 1 && logs.isNotEmpty()) {
                            TextButton(onClick = {
                                copyText(context, LogStore.exportText())
                                toast(context, "已复制全部日志（已去除令牌）")
                            }) { Text("复制") }
                            TextButton(onClick = onClearLogs) { Text("清空") }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0, onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) }, label = { Text("主页") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1, onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Info, contentDescription = null) }, label = { Text("日志") },
                    )
                }
            },
        ) { padding ->
            if (selectedTab == 0) {
                HomeTab(
                    context = context,
                    status = status,
                    onStartServer = onStartServer,
                    onStopServer = onStopServer,
                    onOpenTools = { open(ToolsActivity::class.java) },
                    onOpenSecurity = { open(SecurityActivity::class.java) },
                    onOpenDiagnostics = { open(DiagnosticsActivity::class.java) },
                    onOpenSettings = { open(SettingsActivity::class.java) },
                    modifier = Modifier.padding(padding),
                )
            } else {
                LogTab(logs = logs, modifier = Modifier.padding(padding))
            }
        }
    }

    if (showLanNotice) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("“局域网访问”已移除") },
            text = { Text("旧版本的局域网访问会用明文 HTTP 传输访问令牌，新版本已删除。本地端点只供本机使用；远程访问请使用设置中的 TLS 中继。") },
            confirmButton = {
                TextButton(onClick = {
                    Settings.clearLegacyLanAccess(context)
                    showLanNotice = false
                }) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun DrawerItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, contentDescription = null) },
        selected = false,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun HomeTab(
    context: Context,
    status: StatusInfo,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val service = status.service
    val running = McpServerService.running
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ===== 服务状态 =====
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dot = when (service?.state) {
                    ServiceStatus.State.RUNNING -> Color(0xFF2E7D32)
                    ServiceStatus.State.RESTRICTED -> Color(0xFFF9A825)
                    else -> Color(0xFF9E9E9E)
                }
                Box(modifier = Modifier.size(14.dp).background(color = dot, shape = CircleShape))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(service?.title ?: "…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "已开启 ${status.enabledTools}/${status.totalTools} 个只读工具",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
                Button(
                    onClick = if (running) onStopServer else onStartServer,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (running) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (running) "停止" else "启动")
                }
            }
            if (service != null && service.problems.isNotEmpty()) {
                Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp)) {
                    for (p in service.problems) {
                        Text("· $p", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = onOpenDiagnostics) { Text("查看诊断") }
                }
            }
        }

        // ===== 远程访问 =====
        Card {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("远程访问", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(10.dp))
                val (text, color) = relayText(status, running)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
                Spacer(Modifier.height(6.dp))
                InfoLine("最近一次成功连接", formatTime(status.lastRelaySuccessAt))
                val call = status.lastToolCall
                InfoLine("最近一次工具调用",
                    if (call == null) "暂无" else "${formatTime(call.at)} · ${call.name} · ${"%.0f".format(call.ms)} 毫秒" +
                        if (call.ok) "" else " · 出错")
            }
        }

        // ===== 应用更新（1.4.0） =====
        if (status.update.newer) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("有新版本 ${status.update.latest}", style = MaterialTheme.typography.titleSmall)
                    Text(status.update.message ?: "当前版本 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenSettings) { Text("去更新 →") }
                }
            }
        }

        // ===== 需要处理的问题 =====
        when (status.storage) {
            Settings.CredentialStorage.PLAINTEXT_CONSENTED -> AlertCard("兼容模式：凭据未加密保存",
                "本机 Keystore 不可用，你已同意以明文保存访问令牌和设备密钥。获得 root 权限或做设备取证的人可以读到它们。",
                "查看", onOpenSecurity)
            Settings.CredentialStorage.NEEDS_CONSENT -> AlertCard("凭据无法加密保存，远程认证已停用",
                "本机 Keystore 不可用或旧版凭据无法迁移。应用不会自动改为明文保存，目前所有请求都会被拒绝。",
                "去处理", onOpenSecurity)
            Settings.CredentialStorage.UNREADABLE -> AlertCard("已保存的凭据无法解密",
                "所有请求都会被拒绝，应用不会改用残留的明文副本。请到“安全”页重新生成令牌、在“设置”中重新填写设备密钥。",
                "去处理", onOpenSecurity)
            else -> {}
        }
        if (status.tokenProblem != null && status.storage == Settings.CredentialStorage.ENCRYPTED) {
            AlertCard("访问令牌不可用", "已保存的令牌无法读取（${status.tokenProblem}），所有请求都会被拒绝。请到“安全”页重新生成。",
                "去处理", onOpenSecurity)
        }
        if (!status.usageAccess) {
            AlertCard("需要使用情况访问权限",
                "“前台应用”和“使用时长”依赖系统的使用情况访问权限。不需要 Root 或无障碍权限。",
                "去授权") { openUsageAccessSettings(context) }
        }

        TextButton(onClick = onOpenTools) { Text("选择开放给 Violet 的工具 →") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun relayText(status: StatusInfo, running: Boolean): Pair<String, Color> {
    val r = status.relayState
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return when {
        !status.relayEnabled -> "未开启（在“设置”中填写中继地址后开启）" to muted
        !running -> "服务未运行，没有连接" to muted
        r == null -> "未启动" to muted
        r.phase == RelayClient.Phase.CONNECTED -> "已连接 ${status.relayHost}" to Color(0xFF2E7D32)
        r.phase == RelayClient.Phase.CONNECTING -> "正在连接 ${status.relayHost}" to Color(0xFFF9A825)
        r.phase == RelayClient.Phase.WAITING_NETWORK -> "等待网络恢复" to Color(0xFFF9A825)
        r.phase == RelayClient.Phase.REJECTED || r.phase == RelayClient.Phase.REPLACED -> {
            val retry = if (r.nextRetryAt > 0) "，${secondsUntil(r.nextRetryAt)} 秒后再试" else ""
            "${r.message}$retry" to MaterialTheme.colorScheme.error
        }
        else -> "${r.message}，${secondsUntil(r.nextRetryAt)} 秒后重连" to Color(0xFFF9A825)
    }
}

@Composable
private fun AlertCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            Spacer(Modifier.height(8.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
internal fun InfoLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.45f))
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.55f))
    }
}

@Composable
private fun LogTab(logs: List<LogEntry>, modifier: Modifier = Modifier) {
    if (logs.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无日志", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
    ) {
        items(logs, key = { it.id }) { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(entry.timeText, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                Text(
                    entry.level,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (entry.level) {
                        "E" -> Color(0xFFC62828)
                        "W" -> Color(0xFFF9A825)
                        "D" -> Color(0xFF757575)
                        else -> Color(0xFF2E7D32)
                    },
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.width(8.dp))
                Text(entry.message, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            }
        }
    }
}

internal fun formatTime(ms: Long): String {
    if (ms <= 0) return "暂无"
    val ago = (System.currentTimeMillis() - ms) / 1000
    val t = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))
    return when {
        ago < 0 -> t
        ago < 60 -> "$t（${ago} 秒前）"
        ago < 3600 -> "$t（${ago / 60} 分钟前）"
        else -> t
    }
}

internal fun secondsUntil(at: Long): Long = ((at - System.currentTimeMillis()) / 1000).coerceAtLeast(0)

internal fun toast(context: Context, msg: String) =
    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

internal fun openUsageAccessSettings(context: Context) {
    try {
        context.startActivity(
            Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .setData(android.net.Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Exception) {
        // 部分系统不支持直接跳到本应用，退回列表页
        context.startActivity(
            Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

internal fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText("mcp", text))
}
