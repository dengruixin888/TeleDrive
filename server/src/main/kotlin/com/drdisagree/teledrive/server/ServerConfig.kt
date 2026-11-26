package com.drdisagree.teledrive.server

import java.io.File

/**
 * Runtime configuration for the headless TeleDrive server, resolved from environment variables
 * and optional CLI arguments (`--port=8080`, `--host=0.0.0.0`, etc.).
 */
data class ServerConfig(
    val host: String,
    val port: Int,
    val adminPassword: String?,
    val dataDir: File,
    val downloadDir: File,
    val pollIntervalMinutes: Int,
    val watchConcurrency: Int,
    val syncOnStart: Boolean,
    val presetApiId: Int?,
    val presetApiHash: String?
) {
    val authEnabled: Boolean
        get() = !adminPassword.isNullOrBlank()

    companion object {
        fun fromEnvironment(
            args: Array<String> = emptyArray(),
            env: Map<String, String> = System.getenv()
        ): ServerConfig {
            val argMap = parseArgs(args)
            fun getValue(argKey: String, envKey: String): String? =
                argMap[argKey]?.takeIf { it.isNotBlank() }
                    ?: env[envKey]?.takeIf { it.isNotBlank() }

            val password = getValue("admin-password", "TELEDRIVE_ADMIN_PASSWORD")
            val port = getValue("port", "TELEDRIVE_PORT")?.toIntOrNull()?.coerceIn(1, 65535) ?: 8080
            val defaultHost = if (password.isNullOrBlank()) "127.0.0.1" else "0.0.0.0"
            val host = getValue("host", "TELEDRIVE_HOST") ?: defaultHost

            val dataDir = File(getValue("data-dir", "TELEDRIVE_DATA_DIR") ?: "./data")
            val downloadDir = File(getValue("download-dir", "TELEDRIVE_DOWNLOAD_DIR") ?: "./downloads")
            val pollMinutes = getValue("poll-interval", "TELEDRIVE_POLL_INTERVAL_MINUTES")
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 30
            val concurrency = getValue("concurrency", "TELEDRIVE_WATCH_CONCURRENCY")
                ?.toIntOrNull()
                ?.coerceIn(1, 8)
                ?: 4
            val syncOnStart = getValue("sync-on-start", "TELEDRIVE_SYNC_ON_START")
                ?.lowercase()
                ?.let { it == "true" || it == "1" || it == "yes" }
                ?: true

            val presetApiId = getValue("api-id", "TELEDRIVE_API_ID")?.toIntOrNull()?.takeIf { it > 0 }
            val presetApiHash = getValue("api-hash", "TELEDRIVE_API_HASH")

            return ServerConfig(
                host = host,
                port = port,
                adminPassword = password,
                dataDir = dataDir,
                downloadDir = downloadDir,
                pollIntervalMinutes = pollMinutes,
                watchConcurrency = concurrency,
                syncOnStart = syncOnStart,
                presetApiId = presetApiId,
                presetApiHash = presetApiHash
            )
        }

        private fun parseArgs(args: Array<String>): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            for (arg in args) {
                if (!arg.startsWith("--")) continue
                val eq = arg.indexOf('=')
                if (eq > 2) {
                    val key = arg.substring(2, eq).trim()
                    val value = arg.substring(eq + 1).trim()
                    if (key.isNotEmpty()) map[key] = value
                }
            }
            return map
        }
    }
}
