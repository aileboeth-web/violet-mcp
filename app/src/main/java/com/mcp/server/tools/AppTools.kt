package com.mcp.server.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.mcp.server.core.ErrorCodes
import com.mcp.server.core.Err
import com.mcp.server.core.ToolDefinition
import com.mcp.server.core.inCategory
import com.mcp.server.core.param
import com.mcp.server.core.paramSchema
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 应用信息类只读工具。
 * 不再申请 QUERY_ALL_PACKAGES：Manifest 里用 <queries> 声明了桌面启动器 intent，
 * 因此能看到所有带桌面图标的应用（用户实际会使用的应用），看不到纯后台组件包。
 */
object AppTools {

    fun register(ctx: Context, sink: MutableList<ToolDefinition>) {
        sink.add(appInfo(ctx).inCategory("应用信息"))
        sink.add(installedApps(ctx).inCategory("应用信息"))
    }

    private fun appInfo(ctx: Context): ToolDefinition = ToolDefinition(
        "app_info", "获取单个应用的名称、版本、安装/更新时间、是否系统应用等信息。只读。",
        paramSchema(listOf(
            param("packageName", "string", example = "com.android.settings", description = "应用包名", minLength = 1, maxLength = 255),
        ), required = listOf("packageName")),
    ) { args ->
        val pkg = args.getString("packageName").trim()
        val pm = ctx.packageManager
        try {
            val ai = applicationInfo(pm, pkg)
            val pi = packageInfo(pm, pkg)
            JSONObject().apply {
                put("packageName", pkg)
                put("appName", pm.getApplicationLabel(ai).toString())
                put("versionName", pi.versionName ?: "")
                put("versionCode", if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong())
                put("system", (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                put("enabled", ai.enabled)
                put("launchable", pm.getLaunchIntentForPackage(pkg) != null)
                put("isHomeScreen", pkg in AppLabels.homePackages(ctx))
                put("firstInstallTime", TimeFmt.stamp(pi.firstInstallTime))
                put("lastUpdateTime", TimeFmt.stamp(pi.lastUpdateTime))
                put("targetSdk", ai.targetSdkVersion)
                put("minSdk", ai.minSdkVersion)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            Err.of(ErrorCodes.NOT_FOUND, "未找到应用 $pkg",
                "包名可能有误，或该应用没有桌面图标（本服务器只声明了启动器应用的包可见性）。可用 installed_apps 查看可见应用。")
        }
    }

    private fun installedApps(ctx: Context): ToolDefinition = ToolDefinition(
        "installed_apps", "列出手机上带桌面图标的应用（包名、名称、版本、安装时间）。支持按名称或包名关键字过滤。只读。",
        paramSchema(listOf(
            param("keyword", "string", example = "微信", description = "按应用名或包名过滤（不区分大小写）"),
            param("includeSystem", "boolean", example = false, description = "是否包含系统预装应用，默认 false"),
            param("limit", "integer", example = 100, minimum = 1, maximum = 500, description = "最多返回条数，默认 200"),
        )),
    ) { args ->
        val keyword = args.optString("keyword", "").trim().lowercase()
        val includeSystem = args.optBoolean("includeSystem", false)
        val limit = args.optInt("limit", 200).coerceIn(1, 500)
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.queryIntentActivities(launcher, 0)
        val seen = HashSet<String>()
        val apps = ArrayList<JSONObject>()
        for (ri in resolved) {
            val ai = ri.activityInfo?.applicationInfo ?: continue
            val pkg = ai.packageName
            if (!seen.add(pkg)) continue
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (isSystem && !includeSystem) continue
            val label = pm.getApplicationLabel(ai).toString()
            if (keyword.isNotEmpty() && !label.lowercase().contains(keyword) && !pkg.lowercase().contains(keyword)) continue
            val pi = try { packageInfo(pm, pkg) } catch (_: Exception) { null }
            apps.add(JSONObject().apply {
                put("packageName", pkg)
                put("appName", label)
                put("versionName", pi?.versionName ?: "")
                put("system", isSystem)
                put("firstInstallTime", pi?.let { TimeFmt.iso(it.firstInstallTime) } ?: JSONObject.NULL)
            })
        }
        apps.sortBy { it.optString("appName").lowercase() }
        val arr = JSONArray()
        apps.take(limit).forEach { arr.put(it) }
        JSONObject()
            .put("count", arr.length())
            .put("totalMatched", apps.size)
            .put("apps", arr)
            .put("note", "仅包含带桌面图标的应用")
            .put("queryTime", TimeFmt.stamp(System.currentTimeMillis()))
    }

    internal fun applicationInfo(pm: PackageManager, pkg: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, 0)

    private fun packageInfo(pm: PackageManager, pkg: String) =
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
}

/** 包名 → 应用名（带缓存），以及桌面启动器包名 */
object AppLabels {
    private val cache = ConcurrentHashMap<String, String>()

    /** 返回应用名；包不可见或已卸载时返回 null */
    fun label(ctx: Context, pkg: String): String? {
        cache[pkg]?.let { return it }
        return try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(AppTools.applicationInfo(pm, pkg)).toString().also { cache[pkg] = it }
        } catch (_: Exception) {
            null
        }
    }

    @Volatile
    private var homeCache: Pair<Long, Set<String>>? = null

    /** 桌面启动器包名（缓存 5 分钟，避免每次查询都访问 PackageManager） */
    fun homePackages(ctx: Context): Set<String> {
        val now = android.os.SystemClock.elapsedRealtime()
        homeCache?.let { (at, v) -> if (now - at < 300_000L) return v }
        return queryHomePackages(ctx).also { homeCache = now to it }
    }

    private fun queryHomePackages(ctx: Context): Set<String> {
        val pm = ctx.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val list = if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(home, PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.queryIntentActivities(home, 0)
        return list.mapNotNull { it.activityInfo?.packageName }.toSet()
    }
}
