package com.mcp.server

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mcp.server.core.ToolDefinition
import com.mcp.server.server.Settings
import com.mcp.server.tools.ToolKit
import com.mcp.server.tools.UsageTools
import com.mcp.server.ui.theme.McpTheme
import org.json.JSONArray

class ToolsActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) resumeTick.intValue++
        })
        setContent {
            McpTheme {
                ToolsScreen(onBack = { finish() }, resumeTick = resumeTick.intValue)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit = {}, resumeTick: Int = 0) {
    val context = LocalContext.current
    // 切换开关或从系统设置返回后刷新
    var refresh by remember { mutableIntStateOf(0) }
    val groups = remember(refresh) { ToolKit.groupedTools() }
    val usageAccess = remember(refresh, resumeTick) { UsageTools.hasUsageAccess(context) }
    var fgDetail by remember { mutableStateOf(Settings.foregroundDetail(context)) }
    var selected by remember { mutableStateOf<ToolDefinition?>(null) }
    var confirmSensitive by remember { mutableStateOf<ToolDefinition?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("工具与权限", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { ToolKit.resetToDefaults(); refresh++ }) { Text("恢复默认") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "notice") {
                Text(
                    "这里决定 Violet 能看到哪些信息。关闭的工具不会出现在工具列表里，Violet 直接调用也会被拒绝；" +
                        "这些开关只能在手机上修改。标“敏感”的工具可能透露完整使用记录、已装应用或设备信息，默认关闭。" +
                        "所有工具都只读，命令执行、文件修改、网络请求等能力已从应用中移除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            groups.forEach { (category, tools) ->
                item(key = "header_$category") {
                    Text(
                        category,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                items(tools, key = { it.name }) { tool ->
                    ToolRow(
                        tool = tool,
                        enabled = ToolKit.isEnabled(tool.name),
                        sensitive = ToolKit.isSensitive(tool.name),
                        requirement = ToolKit.requirement(tool.name),
                        usageAccess = usageAccess,
                        onToggle = {
                            val on = ToolKit.isEnabled(tool.name)
                            if (!on && ToolKit.isSensitive(tool.name)) {
                                confirmSensitive = tool
                            } else {
                                ToolKit.setEnabled(tool.name, !on)
                                refresh++
                            }
                        },
                        onInfo = { selected = tool },
                        onGrant = { openUsageAccessSettings(context) },
                    )
                }
                if (category == "手机感知") {
                    item(key = "fg_detail") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("前台应用：详细模式", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (fgDetail) "熄屏、锁屏或无法确定时，也会告诉 Violet 最近一次使用的应用及判断原因"
                                    else "默认：只在确定是当前应用时才给出应用名；其他情况只返回状态",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = fgDetail, onCheckedChange = {
                                fgDetail = it
                                Settings.setForegroundDetail(context, it)
                            })
                        }
                    }
                }
            }
        }
    }

    selected?.let { tool -> ToolDetailDialog(tool = tool, onDismiss = { selected = null }) }

    confirmSensitive?.let { tool ->
        AlertDialog(
            onDismissRequest = { confirmSensitive = null },
            title = { Text("开启敏感工具？") },
            text = { Text("“${tool.name}”可能让 Violet 看到${sensitiveWhat(tool.name)}。只在需要时开启，随时可以关闭。") },
            confirmButton = {
                TextButton(onClick = {
                    ToolKit.setEnabled(tool.name, true)
                    confirmSensitive = null
                    refresh++
                }) { Text("开启") }
            },
            dismissButton = { TextButton(onClick = { confirmSensitive = null }) { Text("取消") } },
        )
    }
}

private fun sensitiveWhat(name: String): String = when (name) {
    "get_app_usage_stats" -> "你在一段时间内使用了哪些应用、各用了多久"
    "installed_apps" -> "手机上装了哪些应用"
    "app_info" -> "某个应用是否已安装及其版本"
    "system_properties", "device_info" -> "手机型号、系统版本等设备信息"
    "permission_state" -> "本应用的权限配置"
    else -> "较多个人信息"
}

@Composable
private fun ToolRow(
    tool: ToolDefinition,
    enabled: Boolean,
    sensitive: Boolean,
    requirement: ToolKit.Requirement,
    usageAccess: Boolean,
    onToggle: () -> Unit,
    onInfo: () -> Unit,
    onGrant: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onInfo)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tool.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (sensitive) {
                        Spacer(Modifier.width(8.dp))
                        Text("敏感", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                if (requirement != ToolKit.Requirement.NONE) {
                    val missing = !usageAccess
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            requirement.label + if (missing) "（未授权）" else "（已授权）",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (missing && requirement == ToolKit.Requirement.USAGE_ACCESS)
                                MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
                        )
                        if (missing) TextButton(onClick = onGrant) { Text("去授权") }
                    }
                    if (missing && enabled && requirement == ToolKit.Requirement.USAGE_ACCESS) {
                        Text("未授权时 Violet 调用会得到“权限不足”", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            IconButton(onClick = onToggle) {
                Icon(
                    if (enabled) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (enabled) "关闭工具" else "开启工具",
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolDetailDialog(tool: ToolDefinition, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tool.name, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("分类：${tool.category}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.padding(6.dp))
                Text("功能描述", style = MaterialTheme.typography.titleSmall)
                Text(tool.description, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.padding(8.dp))
                Text("参数", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.padding(4.dp))
                val props = tool.inputSchema.optJSONObject("properties")
                val required = tool.inputSchema.optJSONArray("required")
                if (props != null && props.length() > 0) {
                    props.keys().forEach { key ->
                        val p = props.optJSONObject(key) ?: return@forEach
                        val req = contains(required, key)
                        Text("• $key (${p.optString("type", "any")})${if (req) " 必填" else ""}",
                            style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                } else {
                    Text("无参数", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

private fun contains(arr: JSONArray?, key: String): Boolean {
    if (arr == null) return false
    for (i in 0 until arr.length()) {
        if (arr.optString(i) == key) return true
    }
    return false
}
