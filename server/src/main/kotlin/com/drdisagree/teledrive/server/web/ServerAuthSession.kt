package com.drdisagree.teledrive.server.web

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless HMAC-SHA256 cookie signer for the Web Console admin password.
 */
class ServerAuthSession(
    private val adminPassword: String?
) {
    private val signingKey: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    val authRequired: Boolean
        get() = !adminPassword.isNullOrBlank()

    fun verifyPassword(candidate: String?): Boolean {
        val expected = adminPassword ?: return true
        if (candidate == null) return false
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            candidate.toByteArray(StandardCharsets.UTF_8)
        )
    }

    fun issueToken(ttlSeconds: Long = 7L * 24 * 3600): String {
        val expiresAt = (System.currentTimeMillis() / 1000L) + ttlSeconds
        val payload = "admin:$expiresAt"
        val sig = sign(payload)
        val raw = "$payload:$sig"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray(StandardCharsets.UTF_8))
    }

    fun isValidToken(token: String?): Boolean {
        if (!authRequired) return true
        if (token.isNullOrBlank()) return false
        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8)
        }.getOrNull() ?: return false
        val parts = decoded.split(':')
        if (parts.size != 3 || parts[0] != "admin") return false
        val expiresAt = parts[1].toLongOrNull() ?: return false
        if ((System.currentTimeMillis() / 1000L) > expiresAt) return false
        val expectedSig = sign("${parts[0]}:${parts[1]}")
        return MessageDigest.isEqual(
            expectedSig.toByteArray(StandardCharsets.UTF_8),
            parts[2].toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(signingKey, "HmacSHA256"))
        val bytes = mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
