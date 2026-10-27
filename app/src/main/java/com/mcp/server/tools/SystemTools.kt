package com.mcp.server.tools

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import com.mcp.server.core.ToolDefinition
import com.mcp.server.core.emptySchema
import com.mcp.server.core.inCategory
import com.mcp.server.core.param
import com.mcp.server.core.paramSchema
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** 设备信息类只读工具（原项目保留下来的部分，去掉了序列号等敏感字段） */
object SystemTools {

    fun register(ctx: Context, sink: MutableList<ToolDefinition>) {
        sink.add(deviceInfo().inCategory("设备信息"))
        sink.add(screenInfo(ctx).inCategory("设备信息"))
        sink.add(battery(ctx).inCategory("设备信息"))
        sink.add(storageInfo(ctx).inCategory("设备信息"))
        sink.add(localeInfo().inCategory("设备信息"))
        sink.add(systemProps().inCategory("设备信息"))
        sink.add(now().inCategory("设备信息"))
    }

    private fun deviceInfo(): ToolDefinition = ToolDefinition(
        "device_info", "获取手机硬件与系统信息（品牌、型号、Android 版本、SDK、ABI 等）。只读。",
        emptySchema(),
    ) {
        JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("brand", Build.BRAND)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("product", Build.PRODUCT)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("securityPatch", Build.VERSION.SECURITY_PATCH)
            put("hardware", Build.HARDWARE)
            put("board", Build.BOARD)
            put("fingerprint", Build.FINGERPRINT)
            put("buildId", Build.ID)
            put("buildTime", TimeFmt.iso(Build.TIME))
            put("abi", Build.SUPPORTED_ABIS.joinToString(","))
            put("queryTime", TimeFmt.stamp(System.currentTimeMillis()))
        }
    }

    private fun statusString(s: Int): String = when (s) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

    private fun battery(ctx: Context): ToolDefinition = ToolDefinition(
        "battery", "获取电池状态：电量 percent、充电状态、电源类型、温度、电压、健康度，以及省电模式、Doze、电池优化豁免。只读。",
        emptySchema(),
    ) {
        // 读取粘性广播，不注册接收器
        val intent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        JSONObject().apply {
            put("percent", if (scale > 0 && level >= 0) level * 100 / scale else -1)
            put("status", statusString(status))
            put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            put("plugged", when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "none"
            })
            put("temperatureCelsius", if (temp != Int.MIN_VALUE) temp / 10.0 else JSONObject.NULL)
            put("voltageMillivolts", voltage)
            put("health", when (health) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                else -> "unknown"
            })
            put("powerSaveMode", pm.isPowerSaveMode)
            put("deviceIdle", pm.isDeviceIdleMode)
            put("ignoringBatteryOptimizations", pm.isIgnoringBatteryOptimizations(ctx.packageName))
            put("queryTime", TimeFmt.stamp(System.currentTimeMillis()))
        }
    }

    private fun storageInfo(ctx: Context): ToolDefinition = ToolDefinition(
        "storage_info", "获取内部存储和外部存储的总容量、可用空间（字节）。只读，不访问任何文件内容。",
        emptySchema(),
    ) {
        fun stat(path: File): JSONObject? = try {
            val sf = StatFs(path.absolutePath)
            JSONObject().apply {
                put("totalBytes", sf.totalBytes)
                put("availableBytes", sf.availableBytes)
                put("usedBytes", sf.totalBytes - sf.availableBytes)
            }
        } catch (_: Exception) {
            null
        }
        JSONObject().apply {
            put("internal", stat(Environment.getDataDirectory()) ?: JSONObject.NULL)
            put("external", stat(Environment.getExternalStorageDirectory()) ?: JSONObject.NULL)
            val removable = JSONArray()
            ctx.getExternalFilesDirs(null).filterNotNull().drop(1).forEach { d -> stat(d)?.let { removable.put(it) } }
            put("removable", removable)
            put("queryTime", TimeFmt.stamp(System.currentTimeMillis()))
        }
    }

    private fun screenInfo(ctx: Context): ToolDefinition = ToolDefinition(
        "screen_info", "获取屏幕硬件信息（分辨率、密度、刷新率、亮度设置）。亮灭屏状态请用 get_screen_state。只读。",
        emptySchema(),
    ) {
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val display = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val brightness = try {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        } catch (_: Exception) {
            -1
        }
        val autoBrightness = try {
            Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (_: Exception) {
            false
        }
        JSONObject().apply {
            put("widthPixels", metrics.widthPixels)
            put("heightPixels", metrics.heightPixels)
            put("densityDpi", metrics.densityDpi)
            put("density", metrics.density.toDouble())
            put("refreshRate", display.refreshRate.toDouble())
            put("brightnessSetting", brightness)
            put("autoBrightness", autoBrightness)
            put("queryTime", TimeFmt.stamp(System.currentTimeMillis()))
        }
    }

    private fun localeInfo(): ToolDefinition = ToolDefinition(
        "locale_info", "获取系统语言与时区信息。只读。",
        emptySchema(),
    ) {
        val loc = Locale.getDefault()
        val tz = java.util.TimeZone.getDefault()
        JSONObject().apply {
            put("language", loc.language)
            put("country", loc.country)
            put("displayLanguage", loc.displayLanguage)
            put("timezone", tz.id)
            put("timezoneOffsetMinutes", tz.getOffset(System.currentTimeMillis()) / 60000)
        }
    }

    /**
     * 固定白名单内的 build 属性，全部通过公开的 [Build] API 取得（不再反射调用隐藏 API
     * android.os.SystemProperties），不含序列号、基带等设备标识。
     */
    private fun buildProperties(): Map<String, String> = linkedMapOf(
        "ro.build.version.release" to Build.VERSION.RELEASE,
        "ro.build.version.sdk" to Build.VERSION.SDK_INT.toString(),
        "ro.build.version.security_patch" to Build.VERSION.SECURITY_PATCH,
        "ro.product.model" to Build.MODEL,
        "ro.product.manufacturer" to Build.MANUFACTURER,
        "ro.product.brand" to Build.BRAND,
        "ro.product.device" to Build.DEVICE,
        "ro.hardware" to Build.HARDWARE,
        "ro.build.type" to Build.TYPE,
        "ro.build.tags" to Build.TAGS,
        "ro.build.id" to Build.ID,
        "ro.build.date.utc" to (Build.TIME / 1000).toString(),
        "ro.product.cpu.abi" to (Build.SUPPORTED_ABIS.firstOrNull() ?: ""),
        "persist.sys.timezone" to java.util.TimeZone.getDefault().id,
    )

    private fun systemProps(): ToolDefinition = ToolDefinition(
        "system_properties", "读取固定白名单内的 build 相关属性（通过公开 API，不含序列号等设备标识）。只读。",
        paramSchema(listOf(param("filter", "string", example = "ro.build", description = "按子串过滤属性名"))),
    ) { args ->
        val filter = args.optString("filter", "")
        JSONObject().apply {
            for ((k, v) in buildProperties()) {
                if (filter.isNotEmpty() && !k.contains(filter)) continue
                if (!v.isNullOrEmpty()) put(k, v)
            }
        }
    }

    private fun now(): ToolDefinition = ToolDefinition(
        "time_now", "获取手机当前时间（epoch 毫秒、ISO-8601、时区）。只读。",
        emptySchema(),
    ) {
        val now = System.currentTimeMillis()
        JSONObject().apply {
            put("epochMs", now)
            put("iso", TimeFmt.iso(now))
            put("timezone", java.util.TimeZone.getDefault().id)
            put("elapsedRealtimeMs", android.os.SystemClock.elapsedRealtime())
        }
    }
}
