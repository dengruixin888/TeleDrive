package com.drdisagree.teledrive.server.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAuthSessionTest {

    @Test
    fun `validates password and signed session tokens`() {
        val session = ServerAuthSession("my-strong-pass")
        assertTrue(session.authRequired)
        assertTrue(session.verifyPassword("my-strong-pass"))
        assertFalse(session.verifyPassword("wrong"))
        assertFalse(session.verifyPassword(null))

        val token = session.issueToken()
        assertTrue(session.isValidToken(token))
        assertFalse(session.isValidToken(token + "tamper"))
        assertFalse(session.isValidToken(""))
    }

    @Test
    fun `allows access when password is not configured`() {
        val session = ServerAuthSession(null)
        assertFalse(session.authRequired)
        assertTrue(session.isValidToken(null))
    }
}
