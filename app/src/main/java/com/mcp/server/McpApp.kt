package com.mcp.server

import android.app.Application
import com.mcp.server.server.McpAppCtx
import com.mcp.server.tools.ToolKit

class McpApp : Application() {
    override fun onCreate() {
        super.onCreate()
        McpAppCtx.app = this
        ToolKit.init(this)
    }
}
