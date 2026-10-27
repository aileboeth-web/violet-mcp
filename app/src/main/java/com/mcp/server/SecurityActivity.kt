package com.mcp.server

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mcp.server.core.RelayClient
import com.mcp.server.core.Secrets
import com.mcp.server.server.McpServerService
import com.mcp.server.server.SecretStorageUnavailableException
import com.mcp.server.server.SecretStore
import com.mcp.server.server.Settings
import com.mcp.server.ui.theme.McpTheme
import kotlinx.coroutines.delay

class SecurityActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { McpTheme { SecurityScreen(onBack = { finish() }) } }
    }
}

private enum class Confirm { ROTATE, DISCONNECT, REVOKE, CLEAR_ALL, ENABLE_COMPAT, DISABLE_COMPAT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var relayState by remember { mutableStateOf(McpServerService.relayState) }
    LaunchedEffect(Unit) {
        while (true) {
            relayState = McpServerService.relayState
            delay(1000)
        }
    }

    val token = remember(tick) { Settings.token(context) }
    val tokenProblem = remember(tick) { Settings.tokenProblem(context) }
    val tokenMode = remember(tick) { Settings.tokenStorageMode(context) }
    val secretMode = remember(tick) { Settings.relaySecretStorageMode(context) }
    val storeError = remember(tick) { Settings.secretStore(context).lastError }
    val pins = remember(tick) { Settings.relayPin(context).split(Regex("[,\\s]+")).filter { it.isNotBlank() } }
    val relayEnabled = remember(tick) { Settings.relayEnabled(context) }
    val storage = remember(tick) { Settings.credentialStorage(context) }
    var compatAck by remember { mutableStateOf(false) }

    fun applyAndRefresh(action: String) {
        McpServerService.notifyIfRunning(context, action)
        tick++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("安全", fontWeight = FontWeight.SemiBold) },
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
            // ===== 凭据存储 =====
            if (storage != Settings.CredentialStorage.ENCRYPTED) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val onErr = MaterialTheme.colorScheme.onErrorContainer
                        when (storage) {
                            Settings.CredentialStorage.PLAINTEXT_CONSENTED -> {
                                Text("兼容模式已开启：凭据未加密保存", style = MaterialTheme.typography.titleSmall, color = onErr)
                                Text("访问令牌和设备密钥以明文保存在本应用的私有目录（不进入备份）。获得 root 权限或做设备取证的人可以直接读到。" +
                                    "Keystore 恢复可用后，建议关闭兼容模式并重新生成令牌。",
                                    style = MaterialTheme.typography.bodySmall, color = onErr)
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { confirm = Confirm.DISABLE_COMPAT }) { Text("关闭兼容模式", color = onErr) }
                            }
                            Settings.CredentialStorage.NEEDS_CONSENT -> {
                                Text("凭据无法加密保存，远程认证已停用", style = MaterialTheme.typography.titleSmall, color = onErr)
                                Text("${Settings.secretStore(context).lastError ?: "本机 Android Keystore 不可用"}。" +
                                    "为了安全，应用不会自动改为明文保存：在你明确同意之前，所有 MCP 请求都会被拒绝，中继也不能使用。" +
                                    "如果是从旧版升级，旧令牌仍保留在本机，只是暂时不能用于认证。",
                                    style = MaterialTheme.typography.bodySmall, color = onErr)
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { compatAck = false; confirm = Confirm.ENABLE_COMPAT }) {
                                    Text("了解兼容模式…", color = onErr)
                                }
                            }
                            else -> {
                                Text("已保存的凭据无法解密", style = MaterialTheme.typography.titleSmall, color = onErr)
                                Text("${Settings.secretStore(context).lastError ?: "解密失败"}。所有 MCP 请求都会被拒绝；应用不会改用任何残留的明文副本。" +
                                    "请点下方“更换令牌”生成新令牌（并更新 Violet 配置），设备密钥在“设置”中重新填写。",
                                    style = MaterialTheme.typography.bodySmall, color = onErr)
                            }
                        }
                    }
                }
            }

            // ===== 访问令牌 =====
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("访问令牌", style = MaterialTheme.typography.titleSmall)
                    Text("Violet 每次请求都要带上它（Authorization: Bearer …）。不能关闭。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    if (tokenProblem != null) {
                        Text("令牌无法读取：$tokenProblem。所有请求都会被拒绝，请重新生成。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(Secrets.mask(token), fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            IconButton(onClick = { copyText(context, token); toast(context, "已复制访问令牌") }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "复制",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    StorageLine(tokenMode)
                    storeError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { confirm = Confirm.ROTATE }) { Text("更换令牌") }
                }
            }

            // ===== 远程连接 =====
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("远程连接", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    InfoLine("中继连接", if (relayEnabled) "已开启" else "已关闭")
                    InfoLine("设备密钥", when (secretMode) {
                        SecretStore.Mode.ENCRYPTED -> "已保存（加密）"
                        SecretStore.Mode.PLAINTEXT_FALLBACK -> "已保存（兼容模式，未加密）"
                        null -> "未保存"
                    })
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirm = Confirm.DISCONNECT }, enabled = relayEnabled) { Text("断开远程连接") }
                        OutlinedButton(onClick = { confirm = Confirm.REVOKE }) { Text("撤销远程凭据") }
                    }
                    Text("“断开”只是暂时关闭中继，配置保留；“撤销”会删除手机上的设备密钥和证书指纹。" +
                        "怀疑设备密钥泄露时，还要在 VPS 上更换 device.secret（见部署说明）。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ===== 证书指纹 =====
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("中继证书指纹", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    if (pins.isEmpty()) {
                        Text("未设置：按系统证书颁发机构校验中继证书（适用于正式证书）。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        pins.forEachIndexed { i, p ->
                            Text("${i + 1}. $p", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    val presented = relayState?.presentedPin.orEmpty()
                    if (presented.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("中继出示的证书与已保存的指纹不一致", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                                Text(presented, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer)
                                Text(
                                    "如果你刚在 VPS 上更换了证书：在 VPS 上运行 violet-mcp-relay -print-pin，确认输出与上面完全一致后，" +
                                        "再到“设置”里更新指纹。如果你没有换过证书，可能有人在拦截连接，不要更新。",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(onClick = { copyText(context, presented); toast(context, "已复制，用于核对") }) {
                                    Text("复制以便核对")
                                }
                            }
                        }
                    } else if (relayState?.phase == RelayClient.Phase.CONNECTED) {
                        Text("当前连接的证书与指纹一致", style = MaterialTheme.typography.bodySmall, color = Color(0xFF2E7D32))
                    }
                }
            }

            // ===== 清除 =====
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("清除本地认证配置", style = MaterialTheme.typography.titleSmall)
                    Text("删除中继地址、设备密钥、证书指纹，并生成新的访问令牌。之后 Violet 需要使用新令牌。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { confirm = Confirm.CLEAR_ALL }) {
                        Text("全部清除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    confirm?.let { c ->
        val (title, body) = when (c) {
            Confirm.ROTATE -> "更换访问令牌？" to "旧令牌立即失效，Violet 需要改用新令牌。"
            Confirm.DISCONNECT -> "断开远程连接？" to "Violet 将无法访问手机，直到你重新开启中继。"
            Confirm.REVOKE -> "撤销远程凭据？" to "将删除手机上的设备密钥和证书指纹并关闭中继。重新连接需要再次填写。"
            Confirm.CLEAR_ALL -> "清除全部认证配置？" to "中继配置会被删除，访问令牌会更换。此操作不能撤销。"
            Confirm.ENABLE_COMPAT -> "开启兼容模式（不加密）？" to
                "本机 Android Keystore 不可用，开启后访问令牌和设备密钥将以明文保存在本应用的私有目录中（不进入备份）。" +
                "普通应用读不到这些文件，但获得 root 权限的程序、或拿到手机做数据取证的人可以直接读取，" +
                "进而冒充你的手机连接中继、或用令牌访问本服务器。只有在你确认需要远程访问、且了解这一风险时才开启。"
            Confirm.DISABLE_COMPAT -> "关闭兼容模式？" to
                "将删除所有以明文保存的令牌和设备密钥，并关闭中继。之后如果 Keystore 仍不可用，远程访问将无法使用。"
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = {
                Column {
                    Text(body)
                    if (c == Confirm.ENABLE_COMPAT) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = compatAck, onCheckedChange = { compatAck = it })
                            Text("我了解凭据将不加密保存的风险", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (c) {
                        Confirm.ROTATE -> {
                            try {
                                Settings.regenerateToken(context)
                                applyAndRefresh(McpServerService.ACTION_TOKEN_CHANGED)
                                toast(context, "已更换令牌")
                            } catch (e: SecretStorageUnavailableException) {
                                tick++
                                toast(context, "无法安全保存新令牌：${e.message}")
                            }
                        }
                        Confirm.ENABLE_COMPAT -> {
                            if (!compatAck) return@TextButton
                            Settings.setPlaintextConsent(context, true)
                            applyAndRefresh(McpServerService.ACTION_APPLY)
                            toast(context, "已开启兼容模式（凭据未加密）")
                        }
                        Confirm.DISABLE_COMPAT -> {
                            Settings.setPlaintextConsent(context, false)
                            applyAndRefresh(McpServerService.ACTION_APPLY)
                            toast(context, "已关闭兼容模式，明文凭据已删除")
                        }
                        Confirm.DISCONNECT -> {
                            Settings.setRelayEnabled(context, false)
                            applyAndRefresh(McpServerService.ACTION_APPLY)
                        }
                        Confirm.REVOKE -> {
                            Settings.revokeRelayCredentials(context)
                            applyAndRefresh(McpServerService.ACTION_APPLY)
                            toast(context, "已撤销远程凭据")
                        }
                        Confirm.CLEAR_ALL -> {
                            Settings.clearAllCredentials(context)
                            applyAndRefresh(McpServerService.ACTION_APPLY)
                            toast(context, "已清除，并生成了新令牌")
                        }
                    }
                    confirm = null
                }, enabled = c != Confirm.ENABLE_COMPAT || compatAck) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun StorageLine(mode: SecretStore.Mode?) {
    val (text, color) = when (mode) {
        SecretStore.Mode.ENCRYPTED -> "用 Android Keystore 加密保存，不进入备份" to Color(0xFF2E7D32)
        SecretStore.Mode.PLAINTEXT_FALLBACK ->
            "兼容模式：本机 Keystore 不可用，未加密保存在应用私有目录（不进入备份）" to Color(0xFFF9A825)
        null -> "" to Color.Unspecified
    }
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodySmall, color = color)
}
