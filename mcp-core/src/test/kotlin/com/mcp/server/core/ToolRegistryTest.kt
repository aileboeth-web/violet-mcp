package com.mcp.server.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolRegistryTest {

    @Test
    fun everyRemovedToolIsRejectedAtRegistration() {
        val r = ToolRegistry()
        for (name in ReadOnlyPolicy.REMOVED_TOOLS) {
            try {
                r.register(ToolDefinition(name, "x", emptySchema()) { "should never run" })
                fail("$name 不应允许注册")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertTrue(r.all().isEmpty())
    }

    @Test
    fun allowlistAndRemovedListDoNotOverlap() {
        for (name in ReadOnlyPolicy.REMOVED_TOOLS) assertFalse(name, ReadOnlyPolicy.isAllowed(name))
    }

    @Test
    fun duplicateRegistrationRejected() {
        val r = ToolRegistry()
        r.register(ToolDefinition("battery", "x", emptySchema()) { null })
        try {
            r.register(ToolDefinition("battery", "y", emptySchema()) { null })
            fail()
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun disabledToolIsHiddenAndNotResolvable() {
        val r = TestTools.registry { it != "battery" }
        assertNull(r.resolve("battery"))
        assertNotNull(r.resolve("time_now"))
        val tools = r.toListResult().getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertFalse("battery" in names)
        assertEquals(4, names.size)
    }

    @Test
    fun listedToolsCarryReadOnlyAnnotations() {
        val tools = TestTools.registry().toListResult().getJSONArray("tools")
        for (i in 0 until tools.length()) {
            val a = tools.getJSONObject(i).getJSONObject("annotations")
            assertTrue(a.getBoolean("readOnlyHint"))
            assertFalse(a.getBoolean("destructiveHint"))
        }
    }
}
