"""
静态检查 APK：权限、组件和危险 API 引用。

  python tests/verify_apk.py --apk app/build/outputs/apk/debug/violet-mcp-<版本>-debug.apk \
      --build-tools <SDK>/build-tools/35.0.0

建议对 debug 包运行（未混淆，能按类名定位引用）；release 包同样可检查权限和第三方库。
退出码：0 通过；1 失败。
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
import tempfile
import zipfile
from collections import defaultdict
from pathlib import Path

EXPECTED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.WAKE_LOCK",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.PACKAGE_USAGE_STATS",
    # 1.4.0：安装本应用自己的更新（只在 AppUpdater 里用 PackageInstaller，见下面的检查）
    "android.permission.REQUEST_INSTALL_PACKAGES",
}
# androidx.core 自动添加的签名级权限（只允许本应用自己使用），不是对外权限
AUTO_PERMISSION = re.compile(r"\.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION$")

REMOVED_PERMISSIONS = [
    "MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "QUERY_ALL_PACKAGES",
    "SYSTEM_ALERT_WINDOW", "READ_PHONE_STATE", "USE_FULL_SCREEN_INTENT", "ACCESS_WIFI_STATE",
    "FOREGROUND_SERVICE_DATA_SYNC", "BIND_ACCESSIBILITY_SERVICE",
]

# 不应出现在 APK 中的第三方库/已删除组件
FORBIDDEN_CLASSES = {
    "Shizuku": "Lrikka/shizuku/",
    "QuickJS": "Lcom/quickjs/",
    "QuickJS(taoweiji)": "Lio/github/taoweiji/",
    "DocumentFile": "Landroidx/documentfile/",
    "原 BridgeManager": "Lcom/mcp/server/bridge/",
    "原 BoreClient": "Lcom/mcp/server/server/BoreClient",
    "原 FileTools": "Lcom/mcp/server/tools/FileTools",
    "原 ScriptTools": "Lcom/mcp/server/tools/ScriptTools",
    "原 NetTools": "Lcom/mcp/server/tools/NetTools",
    "原 FloatWindowService": "Lcom/mcp/server/server/FloatWindowService",
}

# 本应用自己的代码（com.mcp.server.*）中不允许出现的调用
FORBIDDEN_CALLS = {
    "Runtime.exec": "Ljava/lang/Runtime;.exec:",
    "ProcessBuilder": "Ljava/lang/ProcessBuilder;.",
    "File.delete": "Ljava/io/File;.delete:",
    "File 写入": "Ljava/io/FileOutputStream;.<init>:",
    "ContentResolver 写入": "Landroid/content/ContentResolver;.(insert|update|delete):",
    "强杀应用": "Landroid/app/ActivityManager;.killBackgroundProcesses:",
    "启动其他应用": "Landroid/content/pm/PackageManager;.getLaunchIntentForPackage:.*\n.*startActivity",
    "发通知（工具）": "Landroidx/core/app/NotificationManagerCompat;.notify:",
    "设置系统设置": "Landroid/provider/Settings\\$(System|Secure|Global);.put",
}
# 允许的例外：界面上“复制令牌”按钮（用户主动操作，不经过 MCP）
ALLOWED_CALLERS = {
    "ClipboardManager.setPrimaryClip": {"Lcom/mcp/server/MainScreenKt;"},
}
# 1.4.0 应用更新：只有这些类可以写、删文件（自己缓存目录里下载的安装包）和使用 PackageInstaller。
# 不经过 MCP，工具和协议核心（com/mcp/server/core、tools）仍然不允许任何写文件操作。
UPDATER_PREFIXES = ("Lcom/mcp/server/update/", "Lcom/mcp/server/server/AppUpdater")
UPDATER_ONLY_CALLS = {"File.delete", "File 写入"}


def run(cmd: list[str], cwd: str | None = None) -> str:
    return subprocess.run(cmd, check=True, capture_output=True, text=True, encoding="utf-8", errors="replace",
                          cwd=cwd).stdout


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--build-tools", required=True)
    a = ap.parse_args()
    bt = Path(a.build_tools)
    exe = ".exe" if sys.platform == "win32" else ""
    failures: list[str] = []

    def check(name: str, ok: bool, detail: str = "") -> None:
        print(f"[{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))
        if not ok:
            failures.append(name)

    perms_out = run([str(bt / f"aapt2{exe}"), "dump", "permissions", a.apk])
    perms = set(re.findall(r"uses-permission: name='([^']+)'", perms_out))
    declared = {p for p in perms if not AUTO_PERMISSION.search(p)}
    check("权限与清单完全一致", declared == EXPECTED_PERMISSIONS,
          f"多出 {sorted(declared - EXPECTED_PERMISSIONS)} 缺少 {sorted(EXPECTED_PERMISSIONS - declared)}")
    for p in REMOVED_PERMISSIONS:
        check(f"未声明 {p}", not any(x.endswith("." + p) for x in perms))

    manifest = run([str(bt / f"aapt2{exe}"), "dump", "xmltree", "--file", "AndroidManifest.xml", a.apk])
    check("没有 Shizuku Provider", "ShizukuProvider" not in manifest)
    check("没有无障碍服务", "AccessibilityService" not in manifest and "BIND_ACCESSIBILITY" not in manifest)
    check("allowBackup=false", re.search(r"allowBackup\(0x[0-9a-f]+\)=false", manifest) is not None)
    check("usesCleartextTraffic=false", re.search(r"usesCleartextTraffic\(0x[0-9a-f]+\)=false", manifest) is not None)
    check("前台服务类型为 specialUse", "foregroundServiceType" in manifest and "0x40000000" in manifest)

    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(a.apk) as z:
            dexes = [n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)]
            for n in dexes:
                z.extract(n, tmp)
        # dexdump 打不开含非 ASCII 字符的路径，切到临时目录用相对文件名
        dump = "".join(run([str(bt / f"dexdump{exe}"), "-d", n], cwd=tmp) for n in dexes)

    for label, prefix in FORBIDDEN_CLASSES.items():
        check(f"APK 中没有 {label}", f"Class descriptor  : '{prefix}" not in dump)

    # 按类归属调用：只检查本应用自己的类
    calls: dict[str, set[str]] = defaultdict(set)
    current = ""
    for line in dump.splitlines():
        m = re.match(r"\s*Class descriptor\s*:\s*'([^']+)'", line)
        if m:
            current = m.group(1)
            continue
        if current.startswith("Lcom/mcp/server/") and "invoke-" in line:
            calls[current].add(line)
    own = {c: "\n".join(lines) for c, lines in calls.items()}
    for label, pattern in FORBIDDEN_CALLS.items():
        hits = [c for c, body in own.items() if re.search(pattern, body)
                and not (label in UPDATER_ONLY_CALLS and c.startswith(UPDATER_PREFIXES))]
        check(f"本应用代码不调用 {label}" + ("（应用更新除外）" if label in UPDATER_ONLY_CALLS else ""), not hits,
              ", ".join(hits))
    installer = [c for c, body in own.items() if "PackageInstaller;.createSession" in body
                 or "PackageInstaller$Session;.commit" in body]
    check("PackageInstaller 只出现在应用更新里", bool(installer) and all(c.startswith(UPDATER_PREFIXES) for c in installer),
          ", ".join(installer) or "没有找到")
    check("更新结果接收器不导出", re.search(r"UpdateInstallReceiver\"[^\n]*\n(?:[^\n]*\n){0,3}?[^\n]*exported\(0x[0-9a-f]+\)=false",
                                              manifest) is not None)
    clip = [c for c, body in own.items() if "ClipboardManager;.setPrimaryClip" in body]
    check("剪贴板写入仅来自界面“复制”按钮", set(clip) <= ALLOWED_CALLERS["ClipboardManager.setPrimaryClip"], ", ".join(clip))
    tool_classes = [c for c in own if c.startswith("Lcom/mcp/server/tools/")]
    check("工具类存在（确认检查的是未混淆的包）", bool(tool_classes), f"{len(tool_classes)} 个类")

    # MCP 工具所在的包（以及协议/中继核心）必须没有任何“改变设备状态”的调用
    side_effects = {
        "启动组件": r";\.(startActivity|startService|startForegroundService|bindService|sendBroadcast|sendOrderedBroadcast)",
        "发通知": r"(NotificationManager|NotificationManagerCompat);\.notify",
        "写剪贴板": r"ClipboardManager;\.setPrimaryClip",
        "写系统设置": r"Settings\$(System|Secure|Global);\.put",
        "音量/亮度/网络开关": r"(AudioManager;\.set|WifiManager;\.set|BluetoothAdapter;\.(enable|disable)|setStreamVolume)",
        "执行进程": r"(Runtime;\.exec|ProcessBuilder;\.)",
        "电话/短信": r"(SmsManager;\.send|TelecomManager;\.placeCall|ACTION_CALL)",
        "写文件": r"(FileOutputStream;\.<init>|File;\.(delete|renameTo|mkdir))",
    }
    scoped = {c: b for c, b in own.items()
              if c.startswith("Lcom/mcp/server/tools/") or c.startswith("Lcom/mcp/server/core/")}
    for label, pattern in side_effects.items():
        hits = [c for c, body in scoped.items() if re.search(pattern, body)]
        check(f"工具与协议核心代码中没有：{label}", not hits, ", ".join(hits))

    print(f"\n失败 {len(failures)} 项")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
