package com.drdisagree.teledrive.server.daemon

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import kotlinx.serialization.Serializable

@Serializable
data class ServerLogEntry(
    val timestamp: String,
    val level: String,
    val message: String
)

/**
 * Thread-safe ring buffer for recent server daemon events, mirroring messages to stdout for Docker logs.
 */
class ServerLogStore(
    private val maxEntries: Int = 250
) {
    private val entries = ArrayDeque<ServerLogEntry>(maxEntries)
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    @Synchronized
    fun info(message: String) = append("INFO", message)

    @Synchronized
    fun warn(message: String) = append("WARN", message)

    @Synchronized
    fun error(message: String, throwable: Throwable? = null) {
        val detail = throwable?.let { " (${it::class.simpleName}: ${it.message})" }.orEmpty()
        append("ERROR", "$message$detail")
    }

    @Synchronized
    fun recent(limit: Int = 200): List<ServerLogEntry> {
        val list = entries.toList()
        return if (list.size <= limit) list else list.takeLast(limit)
    }

    private fun append(level: String, message: String) {
        val ts = formatter.format(Instant.now())
        val entry = ServerLogEntry(ts, level, message)
        if (entries.size >= maxEntries) {
            entries.removeFirst()
        }
        entries.addLast(entry)
        println("[$ts] [$level] $message")
    }
}
