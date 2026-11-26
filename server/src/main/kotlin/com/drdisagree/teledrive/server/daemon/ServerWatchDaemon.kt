package com.drdisagree.teledrive.server.daemon

import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.publish.PublishScheduler
import com.drdisagree.teledrive.core.telegram.TelegramAuthState
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
import com.drdisagree.teledrive.data.repository.ChannelWatchManager
import com.drdisagree.teledrive.data.repository.FolderOwnershipRepair
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TelegramAuthRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import com.drdisagree.teledrive.server.ServerConfig
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class DaemonRuntimeSnapshot(
    val watchLoopActive: Boolean,
    val syncingHistoryNow: Boolean,
    val pollIntervalMinutes: Int,
    val lastSyncAtEpochMs: Long?,
    val nextPollAtEpochMs: Long?,
    val lastSyncSavedCount: Int?,
    val totalSavedSinceBoot: Int,
    val lastError: String?
)

/**
 * Keeps TeleDrive connected 24/7 in headless mode:
 * - boots TDLib with stored credentials (or preset env API credentials)
 * - runs real-time `ChannelWatchManager.watch()` with automatic restart on transient errors
 * - runs an initial catch-up sync + periodic incremental history poll to guarantee zero missed posts
 */
class ServerWatchDaemon(
    private val config: ServerConfig,
    private val authRepository: TelegramAuthRepository,
    private val settingsRepository: SettingsRepository,
    private val channelWatchManager: ChannelWatchManager,
    private val transferRepository: TransferRepository,
    private val fileRepository: FileRepository,
    private val folderOwnershipRepair: FolderOwnershipRepair,
    private val publishScheduler: PublishScheduler,
    private val trashRepository: TrashRepository,
    private val log: ServerLogStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val started = AtomicBoolean(false)

    private val watchActive = AtomicBoolean(false)
    private val syncingNow = AtomicBoolean(false)
    private val pollIntervalMinutes = AtomicInteger(config.pollIntervalMinutes)
    private val lastSyncAt = AtomicLong(0L)
    private val nextPollAt = AtomicLong(0L)
    private val totalSaved = AtomicInteger(0)

    @Volatile
    private var lastSyncSaved: Int? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var pollJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        log.info("Starting TeleDrive Server Watch Daemon (poll=${pollIntervalMinutes.get()}m, syncOnStart=${config.syncOnStart})")

        scope.launch {
            bootstrapTelegram()
        }

        scope.launch {
            runCatching { transferRepository.recoverOrphanedTransfers() }
            runCatching { fileRepository.sweepImportOrphans() }
            runCatching { folderOwnershipRepair.refileStrayFiles() }
            publishScheduler.kick()
            runCatching { trashRepository.repairTrashTree() }
        }

        // Maintain real-time channel watch loop whenever Telegram is Ready and a storage channel is active.
        scope.launch {
            combine(
                authRepository.authState,
                settingsRepository.preferences.map { it.storageChatId to it.watchedChannelIds }
            ) { auth, (storageId, watchedIds) ->
                Triple(auth is TelegramAuthState.Ready, storageId, watchedIds)
            }
                .distinctUntilChanged()
                .collectLatest { (isReady, storageId, watchedIds) ->
                    pollJob?.cancel()
                    pollJob = null
                    nextPollAt.set(0L)

                    if (!isReady) {
                        watchActive.set(false)
                        log.info("Waiting for Telegram authorization...")
                        return@collectLatest
                    }
                    if (storageId == null) {
                        watchActive.set(false)
                        log.warn("Telegram is connected, please select or create a storage channel in the Web Console.")
                        return@collectLatest
                    }
                    if (watchedIds.isEmpty()) {
                        watchActive.set(false)
                        log.info("Storage channel ready ($storageId), no watched channels selected yet.")
                        return@collectLatest
                    }

                    log.info("Active watch started: storageChatId=$storageId, watchedChannels=${watchedIds.size}")

                    // Launch real-time watch loop with automatic backoff recovery
                    val watchLoopJob = launch {
                        var attempt = 0
                        while (true) {
                            watchActive.set(true)
                            try {
                                channelWatchManager.watch()
                                break
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (t: Throwable) {
                                watchActive.set(false)
                                attempt++
                                val backoffSec = minOf(60, 5 * attempt)
                                lastError = t.message ?: t::class.simpleName
                                log.error("Real-time watch loop interrupted, retrying in ${backoffSec}s", t)
                                delay(backoffSec.seconds)
                            }
                        }
                        watchActive.set(false)
                    }

                    // Launch periodic catch-up polling
                    pollJob = launch {
                        if (config.syncOnStart) {
                            runSyncInternal(trigger = "startup")
                        }
                        while (true) {
                            val intervalMin = pollIntervalMinutes.get()
                            if (intervalMin <= 0) {
                                nextPollAt.set(0L)
                                delay(1.minutes)
                                continue
                            }
                            nextPollAt.set(System.currentTimeMillis() + intervalMin * 60_000L)
                            delay(intervalMin.minutes)
                            runSyncInternal(trigger = "scheduled")
                        }
                    }

                    watchLoopJob.join()
                }
        }
    }

    private suspend fun bootstrapTelegram() {
        val startedStored = runCatching { authRepository.startFromStoredCredentials() }.getOrNull()
        if (startedStored is AppResult.Success && startedStored.value) {
            log.info("TDLib started using stored encrypted credentials.")
            return
        }
        val apiId = config.presetApiId
        val apiHash = config.presetApiHash
        if (apiId != null && !apiHash.isNullOrBlank()) {
            log.info("Configuring TDLib from environment preset API credentials...")
            when (val res = authRepository.configure(TelegramCredentials(apiId, apiHash))) {
                is AppResult.Success -> log.info("TDLib initialized with preset API credentials.")
                is AppResult.Failure -> log.error("Failed to initialize preset API credentials: ${res.error}")
            }
        } else {
            log.info("No stored Telegram API credentials found. Please open the Web Console to configure API ID & login.")
        }
    }

    suspend fun triggerManualSync(): AppResult<Int> = runSyncInternal(trigger = "manual")

    suspend fun resetHistory(channelIds: List<Long>?): AppResult<Unit> {
        return syncMutex.withLock {
            val result = if (channelIds == null) {
                channelWatchManager.resetHistoryForCurrentStorage()
            } else {
                channelWatchManager.resetHistoryForChannels(channelIds)
            }
            when (result) {
                is AppResult.Success -> {
                    val scopeLabel = if (channelIds == null) "all channels" else "${channelIds.size} channel(s)"
                    log.info("Reset historical fetch records for $scopeLabel.")
                }
                is AppResult.Failure -> log.warn("Failed to reset history: ${result.error}")
            }
            result
        }
    }

    fun updatePollIntervalMinutes(minutes: Int) {
        val clamped = minutes.coerceAtLeast(0)
        pollIntervalMinutes.set(clamped)
        log.info("Updated scheduled catch-up poll interval to ${clamped}m")
        if (clamped > 0 && nextPollAt.get() > 0L) {
            nextPollAt.set(System.currentTimeMillis() + clamped * 60_000L)
        } else if (clamped == 0) {
            nextPollAt.set(0L)
        }
    }

    fun snapshot(): DaemonRuntimeSnapshot = DaemonRuntimeSnapshot(
        watchLoopActive = watchActive.get(),
        syncingHistoryNow = syncingNow.get(),
        pollIntervalMinutes = pollIntervalMinutes.get(),
        lastSyncAtEpochMs = lastSyncAt.get().takeIf { it > 0L },
        nextPollAtEpochMs = nextPollAt.get().takeIf { it > 0L },
        lastSyncSavedCount = lastSyncSaved,
        totalSavedSinceBoot = totalSaved.get(),
        lastError = lastError
    )

    private suspend fun runSyncInternal(trigger: String): AppResult<Int> {
        if (authRepository.authState.value !is TelegramAuthState.Ready) {
            return AppResult.Success(0)
        }
        val prefs = settingsRepository.preferences.first()
        if (prefs.storageChatId == null || prefs.watchedChannelIds.isEmpty()) {
            return AppResult.Success(0)
        }
        return syncMutex.withLock {
            syncingNow.set(true)
            try {
                log.info("Running $trigger history catch-up sync (limit=${prefs.watchedChannelHistoryLimit} msgs/channel)...")
                val result = channelWatchManager.syncWatchedChannelHistory()
                lastSyncAt.set(System.currentTimeMillis())
                when (result) {
                    is AppResult.Success -> {
                        lastSyncSaved = result.value
                        totalSaved.addAndGet(result.value)
                        lastError = null
                        log.info("History sync ($trigger) finished: ${result.value} new video(s) saved.")
                    }
                    is AppResult.Failure -> {
                        lastError = result.error.toString()
                        log.warn("History sync ($trigger) failed: ${result.error}")
                    }
                }
                result
            } finally {
                syncingNow.set(false)
            }
        }
    }
}
