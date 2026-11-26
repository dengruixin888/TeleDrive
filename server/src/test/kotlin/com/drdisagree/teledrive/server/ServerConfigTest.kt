package com.drdisagree.teledrive.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigTest {

    @Test
    fun `defaults to loopback when admin password is not set`() {
        val cfg = ServerConfig.fromEnvironment(emptyArray(), emptyMap())
        assertEquals("127.0.0.1", cfg.host)
        assertEquals(8080, cfg.port)
        assertFalse(cfg.authEnabled)
        assertEquals(0, cfg.pollIntervalMinutes)
        assertEquals(4, cfg.watchConcurrency)
        assertFalse(cfg.syncOnStart)
    }

    @Test
    fun `binds all interfaces when admin password is set and respects cli overrides`() {
        val cfg = ServerConfig.fromEnvironment(
            args = arrayOf("--port=9090", "--poll-interval=15", "--sync-on-start=true"),
            env = mapOf("TELEDRIVE_ADMIN_PASSWORD" to "secret123")
        )
        assertEquals("0.0.0.0", cfg.host)
        assertEquals(9090, cfg.port)
        assertTrue(cfg.authEnabled)
        assertEquals("secret123", cfg.adminPassword)
        assertEquals(15, cfg.pollIntervalMinutes)
        assertTrue(cfg.syncOnStart)
    }
}
