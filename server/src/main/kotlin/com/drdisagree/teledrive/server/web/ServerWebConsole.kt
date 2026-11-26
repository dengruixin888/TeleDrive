package com.drdisagree.teledrive.server.web

import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.telegram.TelegramAuthState
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
import com.drdisagree.teledrive.core.telegram.TelegramProxy
import com.drdisagree.teledrive.core.telegram.TelegramProxyType
import com.drdisagree.teledrive.domain.repository.ChannelRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TelegramAuthRepository
import com.drdisagree.teledrive.server.ServerBuildInfo
import com.drdisagree.teledrive.server.ServerConfig
import com.drdisagree.teledrive.server.daemon.ServerLogEntry
import com.drdisagree.teledrive.server.daemon.ServerLogStore
import com.drdisagree.teledrive.server.daemon.ServerWatchDaemon
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ServerWebConsole(
    private val config: ServerConfig,
    private val authRepository: TelegramAuthRepository,
    private val telegramClient: TelegramClient,
    private val channelRepository: ChannelRepository,
    private val settingsRepository: SettingsRepository,
    private val daemon: ServerWatchDaemon,
    private val log: ServerLogStore
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val authSession = ServerAuthSession(config.adminPassword)
    private var server: HttpServer? = null

    @Synchronized
    fun start() {
        if (server != null) return
        val http = HttpServer.create(InetSocketAddress(config.host, config.port), 0)
        http.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "teledrive-web").apply { isDaemon = true }
        }
        http.createContext("/") { exchange -> handleRequest(exchange) }
        http.start()
        server = http
        log.info("Web Console listening on http://${config.host}:${config.port}")
    }

    @Synchronized
    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handleRequest(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            val method = exchange.requestMethod.uppercase()

            if (method == "GET" && (path == "/" || path == "/index.html")) {
                serveIndexHtml(exchange)
                return
            }
            if (method == "GET" && path == "/health") {
                sendJson(exchange, 200, HealthResponse(ok = true, version = ServerBuildInfo.VERSION))
                return
            }
            if (method == "POST" && path == "/api/auth/login") {
                handleAdminLogin(exchange)
                return
            }
            if (method == "POST" && path == "/api/auth/logout") {
                exchange.responseHeaders.add(
                    "Set-Cookie",
                    "$SESSION_COOKIE=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"
                )
                sendJson(exchange, 200, OkResponse(ok = true))
                return
            }
            if (method == "GET" && path == "/api/auth/session") {
                val authenticated = !authSession.authRequired || isAuthenticated(exchange)
                sendJson(
                    exchange,
                    200,
                    SessionStatusDto(
                        authRequired = authSession.authRequired,
                        authenticated = authenticated
                    )
                )
                return
            }

            if (authSession.authRequired && !isAuthenticated(exchange)) {
                sendJson(exchange, 401, ErrorResponse(ok = false, error = "Unauthorized"))
                return
            }

            routeAuthenticatedApi(exchange, method, path)
        } catch (t: Throwable) {
            log.error("Web API error on ${exchange.requestURI.path}", t)
            runCatching {
                sendJson(exchange, 500, ErrorResponse(ok = false, error = t.message ?: "Internal server error"))
            }
        } finally {
            exchange.close()
        }
    }

    private fun routeAuthenticatedApi(exchange: HttpExchange, method: String, path: String) {
        when {
            method == "GET" && path == "/api/status" -> handleStatus(exchange)
            method == "GET" && path == "/api/logs" -> sendJson(exchange, 200, LogsResponse(log.recent(200)))

            method == "POST" && path == "/api/telegram/config" -> handleTelegramConfig(exchange)
            method == "POST" && path == "/api/telegram/proxy" -> handleTelegramProxy(exchange)
            method == "POST" && path == "/api/telegram/login/qr" -> handleSimpleAuthCall(exchange) {
                authRepository.requestQrCodeAuthentication()
            }
            method == "POST" && path == "/api/telegram/login/phone" -> {
                val req = readJson<PhoneRequest>(exchange)
                handleSimpleAuthCall(exchange) { authRepository.submitPhoneNumber(req.phoneNumber.trim()) }
            }
            method == "POST" && path == "/api/telegram/login/code" -> {
                val req = readJson<CodeRequest>(exchange)
                handleSimpleAuthCall(exchange) { authRepository.submitCode(req.code.trim()) }
            }
            method == "POST" && path == "/api/telegram/login/password" -> {
                val req = readJson<PasswordRequest>(exchange)
                handleSimpleAuthCall(exchange) { authRepository.submitPassword(req.password) }
            }
            method == "POST" && path == "/api/telegram/login/email" -> {
                val req = readJson<EmailRequest>(exchange)
                handleSimpleAuthCall(exchange) { authRepository.submitEmailAddress(req.email.trim()) }
            }
            method == "POST" && path == "/api/telegram/login/email-code" -> {
                val req = readJson<CodeRequest>(exchange)
                handleSimpleAuthCall(exchange) { authRepository.submitEmailCode(req.code.trim()) }
            }
            method == "POST" && path == "/api/telegram/restart" -> handleSimpleAuthCall(exchange) {
                authRepository.restartAuthentication()
            }
            method == "POST" && path == "/api/telegram/logout" -> handleSimpleAuthCall(exchange) {
                authRepository.logout()
            }

            method == "GET" && path == "/api/channels/storage" -> handleListStorageChannels(exchange)
            method == "POST" && path == "/api/channels/storage/select" -> handleSelectStorageChannel(exchange)
            method == "POST" && path == "/api/channels/storage/create" -> handleCreateStorageChannel(exchange)
            method == "GET" && path == "/api/channels/joined" -> handleListJoinedChannels(exchange)

            method == "GET" && path == "/api/watch/config" -> handleGetWatchConfig(exchange)
            method == "PUT" && path == "/api/watch/config" -> handleUpdateWatchConfig(exchange)
            method == "POST" && path == "/api/watch/sync-now" -> handleSyncNow(exchange)
            method == "POST" && path == "/api/watch/reset-history" -> handleResetHistory(exchange)

            else -> sendJson(exchange, 404, ErrorResponse(ok = false, error = "Not found"))
        }
    }

    private fun handleAdminLogin(exchange: HttpExchange) {
        if (!authSession.authRequired) {
            sendJson(exchange, 200, OkResponse(ok = true))
            return
        }
        val body = readJson<AdminLoginRequest>(exchange)
        if (!authSession.verifyPassword(body.password)) {
            sendJson(exchange, 401, ErrorResponse(ok = false, error = "管理密码错误"))
            return
        }
        val token = authSession.issueToken()
        exchange.responseHeaders.add(
            "Set-Cookie",
            "$SESSION_COOKIE=$token; Path=/; HttpOnly; SameSite=Lax; Max-Age=${7 * 24 * 3600}"
        )
        sendJson(exchange, 200, OkResponse(ok = true))
    }

    private fun handleStatus(exchange: HttpExchange) = runBlocking {
        val auth = authRepository.authState.value
        val conn = authRepository.connectionState.value.name
        val prefs = settingsRepository.preferences.first()
        val hasCreds = authRepository.hasCredentials().first()
        val user = if (auth is TelegramAuthState.Ready) {
            (authRepository.getCurrentUser() as? AppResult.Success)?.value
        } else null
        val snap = daemon.snapshot()

        val authDto = when (auth) {
            TelegramAuthState.Uninitialized -> AuthStateDto("UNINITIALIZED")
            TelegramAuthState.Initializing -> AuthStateDto("INITIALIZING")
            TelegramAuthState.WaitingForPhoneNumber -> AuthStateDto("WAITING_FOR_PHONE")
            is TelegramAuthState.WaitingForCode -> AuthStateDto(
                state = "WAITING_FOR_CODE",
                phoneNumber = auth.phoneNumber,
                codeLength = auth.codeLength
            )
            is TelegramAuthState.WaitingForQrScan -> AuthStateDto(
                state = "WAITING_FOR_QR",
                qrLink = auth.link,
                qrImageDataUri = QrCodeRenderer.toBase64PngDataUri(auth.link)
            )
            TelegramAuthState.WaitingForEmailAddress -> AuthStateDto("WAITING_FOR_EMAIL")
            is TelegramAuthState.WaitingForEmailCode -> AuthStateDto(
                state = "WAITING_FOR_EMAIL_CODE",
                emailPattern = auth.emailPattern
            )
            is TelegramAuthState.WaitingForPassword -> AuthStateDto(
                state = "WAITING_FOR_PASSWORD",
                passwordHint = auth.passwordHint
            )
            TelegramAuthState.RegistrationRequired -> AuthStateDto("REGISTRATION_REQUIRED")
            is TelegramAuthState.Failed -> AuthStateDto("FAILED", error = auth.message)
            TelegramAuthState.Ready -> AuthStateDto("READY")
            TelegramAuthState.LoggingOut -> AuthStateDto("LOGGING_OUT")
            TelegramAuthState.Closed -> AuthStateDto("CLOSED")
        }

        val userDto = user?.let {
            UserDto(
                id = it.id,
                name = listOf(it.firstName, it.lastName).filter(String::isNotBlank).joinToString(" "),
                username = it.username,
                phoneNumber = it.phoneNumber,
                isPremium = it.isPremium
            )
        }

        sendJson(
            exchange,
            200,
            StatusResponse(
                version = ServerBuildInfo.VERSION,
                hasApiCredentials = hasCreds,
                connectionState = conn,
                auth = authDto,
                user = userDto,
                storageChatId = prefs.storageChatId,
                watchedChannelCount = prefs.watchedChannelIds.size,
                daemon = DaemonSnapshotDto(
                    watchLoopActive = snap.watchLoopActive,
                    syncingHistoryNow = snap.syncingHistoryNow,
                    pollIntervalMinutes = snap.pollIntervalMinutes,
                    lastSyncAtEpochMs = snap.lastSyncAtEpochMs,
                    nextPollAtEpochMs = snap.nextPollAtEpochMs,
                    lastSyncSavedCount = snap.lastSyncSavedCount,
                    totalSavedSinceBoot = snap.totalSavedSinceBoot,
                    lastError = snap.lastError
                )
            )
        )
    }

    private fun handleTelegramConfig(exchange: HttpExchange) = runBlocking {
        val req = readJson<TelegramConfigRequest>(exchange)
        if (req.apiId <= 0 || req.apiHash.isBlank()) {
            sendJson(exchange, 400, ErrorResponse(ok = false, error = "请输入有效的 API ID 和 API Hash"))
            return@runBlocking
        }
        when (val res = authRepository.configure(TelegramCredentials(req.apiId, req.apiHash.trim()))) {
            is AppResult.Success -> {
                log.info("Telegram API credentials configured via Web Console.")
                sendJson(exchange, 200, OkResponse(ok = true))
            }
            is AppResult.Failure -> sendJson(exchange, 400, ErrorResponse(ok = false, error = res.error.toString()))
        }
    }

    private fun handleTelegramProxy(exchange: HttpExchange) = runBlocking {
        val req = readJson<ProxyRequest>(exchange)
        val proxy = if (!req.enabled) {
            null
        } else {
            val type = runCatching { TelegramProxyType.valueOf(req.type.uppercase()) }
                .getOrDefault(TelegramProxyType.SOCKS5)
            TelegramProxy(
                type = type,
                host = req.host.trim(),
                port = req.port,
                username = req.username?.takeIf { it.isNotBlank() },
                password = req.password?.takeIf { it.isNotBlank() },
                secret = req.secret?.takeIf { it.isNotBlank() }
            )
        }
        runCatching { telegramClient.applyProxy(proxy) }
            .onSuccess {
                log.info(if (proxy == null) "Cleared Telegram proxy." else "Applied ${proxy.type} proxy ${proxy.host}:${proxy.port}")
                sendJson(exchange, 200, OkResponse(ok = true))
            }
            .onFailure {
                sendJson(exchange, 400, ErrorResponse(ok = false, error = it.message ?: "Failed to apply proxy"))
            }
    }

    private fun handleSimpleAuthCall(exchange: HttpExchange, block: suspend () -> AppResult<Unit>) = runBlocking {
        when (val res = block()) {
            is AppResult.Success -> sendJson(exchange, 200, OkResponse(ok = true))
            is AppResult.Failure -> sendJson(exchange, 400, ErrorResponse(ok = false, error = res.error.toString()))
        }
    }

    private fun handleListStorageChannels(exchange: HttpExchange) = runBlocking {
        val activeId = settingsRepository.preferences.first().storageChatId
        when (val res = channelRepository.refresh()) {
            is AppResult.Success -> {
                val items = res.value.map { ch ->
                    StorageChannelDto(
                        chatId = ch.chatId,
                        title = ch.displayName,
                        fileCount = ch.fileCount,
                        isActive = ch.chatId == activeId,
                        isOwned = ch.isOwned
                    )
                }
                sendJson(exchange, 200, StorageChannelsResponse(items))
            }
            is AppResult.Failure -> sendJson(exchange, 400, ErrorResponse(ok = false, error = res.error.toString()))
        }
    }

    private fun handleSelectStorageChannel(exchange: HttpExchange) = runBlocking {
        val req = readJson<SelectStorageRequest>(exchange)
        when (val res = channelRepository.switchTo(req.chatId, index = true)) {
            is AppResult.Success -> {
                log.info("Switched active storage channel to ${req.chatId}")
                sendJson(exchange, 200, OkResponse(ok = true))
            }
            is AppResult.Failure -> sendJson(exchange, 400, ErrorResponse(ok = false, error = res.error.toString()))
        }
    }

    private fun handleCreateStorageChannel(exchange: HttpExchange) = runBlocking {
        val req = readJson<CreateStorageRequest>(exchange)
        when (val res = channelRepository.create(req.label.trim())) {
            is AppResult.Success -> {
                channelRepository.switchTo(res.value.chatId, index = false)
                log.info("Created and activated storage channel ${res.value.displayName} (${res.value.chatId})")
                sendJson(
                    exchange,
                    200,
                    StorageChannelDto(
                        chatId = res.value.chatId,
                        title = res.value.displayName,
                        fileCount = res.value.fileCount,
                        isActive = true,
                        isOwned = true
                    )
                )
            }
            is AppResult.Failure -> sendJson(exchange, 400, ErrorResponse(ok = false, error = res.error.toString()))
        }
    }

    private fun handleListJoinedChannels(exchange: HttpExchange) = runBlocking {
        val channels = runCatching { telegramClient.listJoinedChannels() }
            .getOrElse { e ->
                sendJson(exchange, 400, ErrorResponse(ok = false, error = e.message ?: "Failed to load channels"))
                return@runBlocking
            }
        sendJson(
            exchange,
            200,
            JoinedChannelsResponse(
                channels.map { JoinedChannelDto(it.chatId, it.title, it.isChannel) }
            )
        )
    }

    private fun handleGetWatchConfig(exchange: HttpExchange) = runBlocking {
        val prefs = settingsRepository.preferences.first()
        val snap = daemon.snapshot()
        sendJson(
            exchange,
            200,
            WatchConfigDto(
                watchedChannelIds = prefs.watchedChannelIds.toList(),
                watchedChannelHistoryLimit = prefs.watchedChannelHistoryLimit,
                watchedChannelAutoDownload = prefs.watchedChannelAutoDownload,
                downloadDirectory = prefs.downloadDirectory ?: config.downloadDir.absolutePath,
                pollIntervalMinutes = snap.pollIntervalMinutes
            )
        )
    }

    private fun handleUpdateWatchConfig(exchange: HttpExchange) = runBlocking {
        val req = readJson<WatchConfigDto>(exchange)
        val clampedLimit = req.watchedChannelHistoryLimit.coerceAtLeast(1)
        settingsRepository.update {
            it.copy(
                watchedChannelIds = req.watchedChannelIds.toSet(),
                watchedChannelHistoryLimit = clampedLimit,
                watchedChannelAutoDownload = req.watchedChannelAutoDownload,
                downloadDirectory = req.downloadDirectory.takeIf { d -> d.isNotBlank() }
            )
        }
        daemon.updatePollIntervalMinutes(req.pollIntervalMinutes)
        log.info("Updated watch config: channels=${req.watchedChannelIds.size}, limit=$clampedLimit, autoDownload=${req.watchedChannelAutoDownload}, poll=${req.pollIntervalMinutes}m")
        sendJson(exchange, 200, OkResponse(ok = true))
    }

    private fun handleSyncNow(exchange: HttpExchange) = runBlocking {
        when (val res = daemon.triggerManualSync()) {
            is AppResult.Success -> sendJson(exchange, 200, SyncNowResponse(ok = true, savedCount = res.value))
            is AppResult.Failure -> sendJson(exchange, 400, ErrorResponse(ok = false, error = res.error.toString()))
        }
    }

    private fun handleResetHistory(exchange: HttpExchange) = runBlocking {
        val req = readJson<ResetHistoryRequest>(exchange)
        when (val res = daemon.resetHistory(req.channelIds)) {
            is AppResult.Success -> sendJson(exchange, 200, OkResponse(ok = true))
            is AppResult.Failure -> sendJson(exchange, 400, ErrorResponse(ok = false, error = res.error.toString()))
        }
    }

    private fun isAuthenticated(exchange: HttpExchange): Boolean {
        val cookieHeader = exchange.requestHeaders.getFirst("Cookie") ?: return false
        val token = cookieHeader.split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("$SESSION_COOKIE=") }
            ?.substringAfter('=')
        return authSession.isValidToken(token)
    }

    private fun serveIndexHtml(exchange: HttpExchange) {
        val bytes = javaClass.getResourceAsStream("/web/index.html")?.use { it.readBytes() }
            ?: "<h1>TeleDrive Web Console missing</h1>".toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private inline fun <reified T> readJson(exchange: HttpExchange): T {
        val raw = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return json.decodeFromString(raw.ifBlank { "{}" })
    }

    private inline fun <reified T> sendJson(exchange: HttpExchange, code: Int, payload: T) {
        val bytes = json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val SESSION_COOKIE = "teledrive_session"
    }
}

@Serializable data class OkResponse(val ok: Boolean)
@Serializable data class HealthResponse(val ok: Boolean, val version: String)
@Serializable data class ErrorResponse(val ok: Boolean, val error: String)
@Serializable data class AdminLoginRequest(val password: String = "")
@Serializable data class SessionStatusDto(val authRequired: Boolean, val authenticated: Boolean)

@Serializable data class TelegramConfigRequest(val apiId: Int = 0, val apiHash: String = "")
@Serializable data class ProxyRequest(
    val enabled: Boolean = false,
    val type: String = "SOCKS5",
    val host: String = "",
    val port: Int = 1080,
    val username: String? = null,
    val password: String? = null,
    val secret: String? = null
)
@Serializable data class PhoneRequest(val phoneNumber: String = "")
@Serializable data class CodeRequest(val code: String = "")
@Serializable data class PasswordRequest(val password: String = "")
@Serializable data class EmailRequest(val email: String = "")

@Serializable data class AuthStateDto(
    val state: String,
    val phoneNumber: String? = null,
    val codeLength: Int? = null,
    val qrLink: String? = null,
    val qrImageDataUri: String? = null,
    val emailPattern: String? = null,
    val passwordHint: String? = null,
    val error: String? = null
)

@Serializable data class UserDto(
    val id: Long,
    val name: String,
    val username: String?,
    val phoneNumber: String,
    val isPremium: Boolean
)

@Serializable data class DaemonSnapshotDto(
    val watchLoopActive: Boolean,
    val syncingHistoryNow: Boolean,
    val pollIntervalMinutes: Int,
    val lastSyncAtEpochMs: Long?,
    val nextPollAtEpochMs: Long?,
    val lastSyncSavedCount: Int?,
    val totalSavedSinceBoot: Int,
    val lastError: String?
)

@Serializable data class StatusResponse(
    val version: String,
    val hasApiCredentials: Boolean,
    val connectionState: String,
    val auth: AuthStateDto,
    val user: UserDto?,
    val storageChatId: Long?,
    val watchedChannelCount: Int,
    val daemon: DaemonSnapshotDto
)

@Serializable data class StorageChannelDto(
    val chatId: Long,
    val title: String,
    val fileCount: Int,
    val isActive: Boolean,
    val isOwned: Boolean
)
@Serializable data class StorageChannelsResponse(val channels: List<StorageChannelDto>)
@Serializable data class SelectStorageRequest(val chatId: Long)
@Serializable data class CreateStorageRequest(val label: String = "")

@Serializable data class JoinedChannelDto(val chatId: Long, val title: String, val isChannel: Boolean)
@Serializable data class JoinedChannelsResponse(val channels: List<JoinedChannelDto>)

@Serializable data class WatchConfigDto(
    val watchedChannelIds: List<Long> = emptyList(),
    val watchedChannelHistoryLimit: Int = 50,
    val watchedChannelAutoDownload: Boolean = false,
    val downloadDirectory: String = "",
    val pollIntervalMinutes: Int = 30
)
@Serializable data class SyncNowResponse(val ok: Boolean, val savedCount: Int)
@Serializable data class ResetHistoryRequest(val channelIds: List<Long>? = null)
@Serializable data class LogsResponse(val entries: List<ServerLogEntry>)
