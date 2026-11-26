package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.files.FileNameUtils
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.files.VideoNamingInput
import com.drdisagree.teledrive.core.files.VideoNamingResolver
import com.drdisagree.teledrive.core.publish.PublishScheduler
import com.drdisagree.teledrive.core.telegram.RemoteDocument
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.WatchedChannelHistoryDao
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.WatchedChannelCursorEntity
import com.drdisagree.teledrive.data.local.entity.WatchedChannelFetchedEntity
import com.drdisagree.teledrive.data.remote.telegram.ExtraFields
import com.drdisagree.teledrive.data.remote.telegram.FileTagsCodec
import com.drdisagree.teledrive.data.remote.telegram.ManifestCodec
import com.drdisagree.teledrive.data.remote.telegram.RemoteFileManifest
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Watches selected Telegram channels for new videos, debounces multi-video albums into subfolders,
 * resolves names/tags, and saves them server-side into the user's storage channel.
 */
class ChannelWatchManager(
    private val telegramClient: TelegramClient,
    private val settingsRepository: SettingsRepository,
    private val fileDao: FileDao,
    private val folderDao: FolderDao,
    private val folderPathResolver: FolderPathResolver,
    private val manifestCodec: ManifestCodec,
    private val publishScheduler: PublishScheduler,
    private val transferRepository: TransferRepository,
    private val watchedChannelHistoryDao: WatchedChannelHistoryDao
) {

    private val dedupMutex = Mutex()
    private val folderMutex = Mutex()
    private val historyDbMutex = Mutex()
    private val inFlightUniqueIds = HashSet<String>()

    suspend fun watch() {
        settingsRepository.preferences
            .map { it.watchedChannelIds to it.storageChatId }
            .distinctUntilChanged()
            .collectLatest { (watchedIds, storageChatId) ->
                val effectiveIds = watchedIds.filterTo(LinkedHashSet()) { it != storageChatId }
                if (effectiveIds.isEmpty()) return@collectLatest

                // Launch an independent worker coroutine per channel on Dispatchers.IO so multiple
                // watched channels are monitored and processed concurrently without blocking each other.
                coroutineScope {
                    for (chatId in effectiveIds) {
                        launch(Dispatchers.IO) {
                            watchSingleChannel(chatId)
                        }
                    }
                }
            }
    }

    private suspend fun watchSingleChannel(chatId: Long) {
        val opened = runCatching { telegramClient.openChat(chatId) }
            .onFailure { SafeLog.w(TAG, "Failed to open watched chat $chatId", it) }
            .isSuccess
        try {
            debounceAndCollect(setOf(chatId))
        } finally {
            if (opened) {
                withContext(NonCancellable) {
                    runCatching { telegramClient.closeChat(chatId) }
                }
            }
        }
    }

    private suspend fun debounceAndCollect(watchedIds: Set<Long>) = coroutineScope {
        val albumMutex = Mutex()
        val albumBuffers = mutableMapOf<Pair<Long, Long>, MutableList<RemoteDocument>>()
        val albumJobs = mutableMapOf<Pair<Long, Long>, Job>()

        telegramClient.incomingChannelVideos(watchedIds).collect { doc ->
            if (isManifestedOrEmpty(doc)) return@collect
            if (doc.mediaAlbumId == 0L) {
                launch(Dispatchers.IO) {
                    try {
                        processVideoBatch(listOf(doc))
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: TelegramException) {
                        SafeLog.w(TAG, "Auto-save failed for message ${doc.messageId}: ${e.code}")
                    }
                }
            } else {
                val key = doc.chatId to doc.mediaAlbumId
                albumMutex.withLock {
                    albumBuffers.getOrPut(key) { mutableListOf() }.add(doc)
                    albumJobs.remove(key)?.cancel()
                    albumJobs[key] = launch(Dispatchers.IO) {
                        delay(ALBUM_DEBOUNCE_MS.milliseconds)
                        val batch = albumMutex.withLock {
                            albumJobs.remove(key)
                            albumBuffers.remove(key)
                        }
                        if (!batch.isNullOrEmpty()) {
                            try {
                                processVideoBatch(batch.sortedBy { it.messageId })
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (e: TelegramException) {
                                SafeLog.w(
                                    TAG,
                                    "Auto-save failed for album ${doc.mediaAlbumId}: ${e.code}"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun syncWatchedChannelHistory(
        limitPerChannel: Int? = null,
        concurrency: Int = DEFAULT_CONCURRENCY
    ): AppResult<Int> {
        val prefs = settingsRepository.preferences.first()
        if (prefs.watchedChannelIds.isEmpty()) return AppResult.Success(0)
        val storageChatId = try {
            telegramClient.ensureStorageChat(prefs.storageChatId)
        } catch (e: TelegramException) {
            return AppResult.Failure(e.toAppError())
        }

        val targetChannels = prefs.watchedChannelIds.filter { it != storageChatId }
        if (targetChannels.isEmpty()) return AppResult.Success(0)

        val limit = (limitPerChannel ?: prefs.watchedChannelHistoryLimit).coerceAtLeast(1)
        val maxConcurrent = concurrency.coerceIn(1, MAX_CONCURRENCY)
        val semaphore = Semaphore(maxConcurrent)
        val savedTotal = AtomicInteger(0)
        val errorMutex = Mutex()
        var lastError: AppError? = null

        coroutineScope {
            targetChannels.map { channelId ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val saved = syncSingleChannelHistory(
                                channelId = channelId,
                                storageChatId = storageChatId,
                                limitMessages = limit,
                                autoDownload = prefs.watchedChannelAutoDownload
                            )
                            savedTotal.addAndGet(saved)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: TelegramException) {
                            SafeLog.w(TAG, "Fetching history failed for channel $channelId: ${e.code}")
                            errorMutex.withLock { lastError = e.toAppError() }
                        } catch (t: Throwable) {
                            SafeLog.w(TAG, "Unexpected error fetching history for channel $channelId", t)
                        }
                    }
                }
            }.awaitAll()
        }

        val total = savedTotal.get()
        val finalError = lastError
        return if (total == 0 && finalError != null) {
            AppResult.Failure(finalError)
        } else {
            AppResult.Success(total)
        }
    }

    private suspend fun syncSingleChannelHistory(
        channelId: Long,
        storageChatId: Long,
        limitMessages: Int,
        autoDownload: Boolean
    ): Int {
        var cursor = historyDbMutex.withLock {
            watchedChannelHistoryDao.getCursor(storageChatId, channelId)
        } ?: WatchedChannelCursorEntity(storageChatId, channelId)

        var remainingQuota = limitMessages
        var savedVideosCount = 0

        // Phase 1: Head catch-up (check if any new messages were posted above newestScannedMessageId)
        if (cursor.newestScannedMessageId > 0L) {
            val headVideos = fetchHeadVideos(channelId, cursor.newestScannedMessageId)
            if (headVideos.isNotEmpty()) {
                val maxHeadId = headVideos.maxOf { it.messageId }
                cursor = cursor.copy(
                    newestScannedMessageId = maxOf(cursor.newestScannedMessageId, maxHeadId)
                )

                val posts = groupIntoPosts(headVideos).sortedByDescending { it.topMessageId }
                for (post in posts) {
                    if (remainingQuota <= 0) break
                    if (isPostAlreadyFetched(storageChatId, channelId, post.documents)) {
                        continue
                    }
                    val saved = processVideoBatch(
                        items = post.documents,
                        storageChatIdOverride = storageChatId,
                        autoDownloadOverride = autoDownload
                    )
                    if (saved > 0) {
                        savedVideosCount += saved
                        remainingQuota--
                    }
                }
            }
        }

        // Phase 2: Tail continuation (walk backwards from oldestScannedMessageId into older history)
        var currentFromId = cursor.oldestScannedMessageId
        var consecutiveEmptyPages = 0
        while (remainingQuota > 0 && !cursor.hasReachedEnd) {
            val pageVideos = telegramClient.fetchChannelVideos(
                chatId = channelId,
                fromMessageId = currentFromId,
                limit = HISTORY_PAGE_SIZE
            )
            if (pageVideos.isEmpty()) {
                cursor = cursor.copy(hasReachedEnd = true)
                break
            }

            val maxId = pageVideos.maxOf { it.messageId }
            val minId = pageVideos.minOf { it.messageId }

            // Guard against TDLib returning the same boundary message ID and causing an infinite loop
            if (currentFromId > 0L && minId >= currentFromId) {
                cursor = cursor.copy(hasReachedEnd = true)
                break
            }

            if (cursor.newestScannedMessageId == 0L) {
                cursor = cursor.copy(newestScannedMessageId = maxId)
            }

            val posts = groupIntoPosts(pageVideos).sortedByDescending { it.topMessageId }
            var lastEvaluatedId = currentFromId
            var pageSavedCount = 0
            for (post in posts) {
                if (remainingQuota <= 0) break
                val postMinId = post.documents.minOf { it.messageId }
                lastEvaluatedId = if (lastEvaluatedId == 0L) postMinId else minOf(lastEvaluatedId, postMinId)

                if (isPostAlreadyFetched(storageChatId, channelId, post.documents)) {
                    continue
                }
                val saved = processVideoBatch(
                    items = post.documents,
                    storageChatIdOverride = storageChatId,
                    autoDownloadOverride = autoDownload
                )
                if (saved > 0) {
                    savedVideosCount += saved
                    pageSavedCount += saved
                    remainingQuota--
                }
            }

            if (remainingQuota <= 0 && lastEvaluatedId > 0L) {
                cursor = cursor.copy(oldestScannedMessageId = lastEvaluatedId)
                break
            }

            cursor = cursor.copy(oldestScannedMessageId = minId)
            if (pageVideos.size < HISTORY_PAGE_SIZE) {
                cursor = cursor.copy(hasReachedEnd = true)
                break
            }
            if (pageSavedCount == 0) {
                consecutiveEmptyPages++
                if (consecutiveEmptyPages >= MAX_CONSECUTIVE_SKIPPED_PAGES) {
                    SafeLog.d(TAG, "Stopping tail scan for channel $channelId after $consecutiveEmptyPages already-fetched pages")
                    break
                }
            } else {
                consecutiveEmptyPages = 0
            }
            currentFromId = minId
        }

        historyDbMutex.withLock {
            watchedChannelHistoryDao.upsertCursor(cursor)
        }
        return savedVideosCount
    }

    private suspend fun fetchHeadVideos(channelId: Long, stopAtMessageId: Long): List<RemoteDocument> {
        val collected = mutableListOf<RemoteDocument>()
        var fromId = 0L
        var pages = 0
        while (pages++ < MAX_HEAD_PAGES) {
            val batch = telegramClient.fetchChannelVideos(
                chatId = channelId,
                fromMessageId = fromId,
                limit = HISTORY_PAGE_SIZE
            )
            if (batch.isEmpty()) break
            val newer = batch.filter { it.messageId > stopAtMessageId }
            collected += newer
            if (newer.size < batch.size) {
                break
            }
            val nextFromId = batch.minOf { it.messageId }
            if (fromId > 0L && nextFromId >= fromId) break
            fromId = nextFromId
        }
        return collected
    }

    private suspend fun isPostAlreadyFetched(
        storageChatId: Long,
        channelId: Long,
        items: List<RemoteDocument>
    ): Boolean {
        if (items.isEmpty()) return true
        val messageIds = items.map { it.messageId }
        val fetchedIds = watchedChannelHistoryDao.getFetchedMessageIds(
            storageChatId = storageChatId,
            watchedChatId = channelId,
            messageIds = messageIds
        )
        if (fetchedIds.containsAll(messageIds)) return true

        val uniqueIds = items.map { it.uniqueFileId }.filter { it.isNotBlank() }
        if (uniqueIds.isNotEmpty()) {
            val alreadySaved = fileDao.byRemoteUniqueIds(uniqueIds)
                .filter { it.chatId == storageChatId }
                .mapNotNull { it.remoteUniqueId }
                .toSet()
            if (alreadySaved.containsAll(uniqueIds)) {
                val now = System.currentTimeMillis()
                val entities = items.map { doc ->
                    WatchedChannelFetchedEntity(
                        storageChatId = storageChatId,
                        watchedChatId = channelId,
                        sourceMessageId = doc.messageId,
                        mediaAlbumId = doc.mediaAlbumId,
                        fetchedAt = now
                    )
                }
                watchedChannelHistoryDao.upsertFetched(entities)
                return true
            }
        }
        return false
    }

    internal data class ChannelVideoPost(
        val topMessageId: Long,
        val mediaAlbumId: Long,
        val documents: List<RemoteDocument>
    )

    internal fun groupIntoPosts(videos: List<RemoteDocument>): List<ChannelVideoPost> {
        val albums = videos.filter { it.mediaAlbumId != 0L }
            .groupBy { it.mediaAlbumId }
            .map { (albumId, docs) ->
                ChannelVideoPost(
                    topMessageId = docs.maxOf { it.messageId },
                    mediaAlbumId = albumId,
                    documents = docs.sortedBy { it.messageId }
                )
            }
        val singles = videos.filter { it.mediaAlbumId == 0L }
            .map { doc ->
                ChannelVideoPost(
                    topMessageId = doc.messageId,
                    mediaAlbumId = 0L,
                    documents = listOf(doc)
                )
            }
        return (albums + singles).sortedByDescending { it.topMessageId }
    }

    suspend fun resetHistoryForChannels(channelIds: List<Long>): AppResult<Unit> {
        val prefs = settingsRepository.preferences.first()
        val storageChatId = prefs.storageChatId ?: return AppResult.Success(Unit)
        watchedChannelHistoryDao.deleteFetchedForChannels(storageChatId, channelIds)
        watchedChannelHistoryDao.deleteCursorsForChannels(storageChatId, channelIds)
        return AppResult.Success(Unit)
    }

    suspend fun resetHistoryForCurrentStorage(): AppResult<Unit> {
        val prefs = settingsRepository.preferences.first()
        val storageChatId = prefs.storageChatId ?: return AppResult.Success(Unit)
        watchedChannelHistoryDao.deleteFetchedForStorage(storageChatId)
        watchedChannelHistoryDao.deleteCursorsForStorage(storageChatId)
        return AppResult.Success(Unit)
    }

    suspend fun getTrackedChannelsForCurrentStorage(): List<Long> {
        val prefs = settingsRepository.preferences.first()
        val storageChatId = prefs.storageChatId ?: return emptyList()
        return watchedChannelHistoryDao.getTrackedChannels(storageChatId)
    }

    internal suspend fun processVideoBatch(
        items: List<RemoteDocument>,
        storageChatIdOverride: Long? = null,
        autoDownloadOverride: Boolean? = null
    ): Int {
        if (items.isEmpty()) return 0
        val preFiltered = items.filterNot(::isManifestedOrEmpty).distinctBy { it.uniqueFileId }
        if (preFiltered.isEmpty()) return 0

        val prefs = settingsRepository.preferences.first()
        val storageChatId = storageChatIdOverride
            ?: telegramClient.ensureStorageChat(prefs.storageChatId)
        val candidates = preFiltered.filter { it.chatId != storageChatId }
        if (candidates.isEmpty()) return 0
        val autoDownload = autoDownloadOverride ?: prefs.watchedChannelAutoDownload

        val uniqueIds = candidates.map { it.uniqueFileId }.distinct()
        val pending = dedupMutex.withLock {
            val alreadySavedIds = fileDao.byRemoteUniqueIds(uniqueIds)
                .filter { it.chatId == storageChatId }
                .mapNotNull { it.remoteUniqueId }
                .toSet()

            candidates.filter { doc ->
                doc.uniqueFileId !in alreadySavedIds && inFlightUniqueIds.add(doc.uniqueFileId)
            }
        }
        if (pending.isEmpty()) return 0

        return try {
            savePendingBatch(pending, storageChatId, autoDownload)
        } finally {
            dedupMutex.withLock {
                pending.forEach { inFlightUniqueIds.remove(it.uniqueFileId) }
            }
        }
    }

    private suspend fun savePendingBatch(
        pending: List<RemoteDocument>,
        storageChatId: Long,
        autoDownload: Boolean
    ): Int {
        val channelSegment = pending.firstNotNullOfOrNull {
            it.sourceChannelTitle?.takeIf(String::isNotBlank)
        }?.let(FileNameUtils::sanitize)

        val sharedAlbumId = pending.first().mediaAlbumId.takeIf { id ->
            id != 0L && pending.size > 1 && pending.all { it.mediaAlbumId == id }
        } ?: 0L

        val inputs = pending.map { doc ->
            VideoNamingInput(
                messageId = doc.messageId,
                rawFileName = doc.fileName,
                caption = doc.caption,
                mimeType = effectiveVideoMime(doc)
            )
        }

        val folderPath: String
        val resolvedNames = if (sharedAlbumId != 0L) {
            val album = VideoNamingResolver.resolveAlbum(sharedAlbumId, inputs)
            folderPath = listOfNotNull(channelSegment, album.albumFolderName).joinToString("/")
            album.items
        } else {
            folderPath = channelSegment.orEmpty()
            inputs.map(VideoNamingResolver::resolveSingle)
        }

        var createdFolders = false
        val (folderId, usedNames) = folderMutex.withLock {
            val resolvedFolderId = if (folderPath.isBlank()) {
                null
            } else {
                folderPathResolver.resolveExisting(folderPath, storageChatId)
                    ?: folderPathResolver.resolveOrCreate(folderPath, chatId = storageChatId)?.also {
                        createdFolders = true
                        folderDao.markPendingPublish()
                    }
            }
            resolvedFolderId to fileDao.namesInFolder(resolvedFolderId).toMutableList()
        }
        var savedCount = 0
        val savedDocs = mutableListOf<RemoteDocument>()

        for ((doc, resolved) in pending.zip(resolvedNames)) {
            val uniqueName = FileNameUtils.uniqueName(resolved.fileName) { candidate ->
                usedNames.any { it.equals(candidate, ignoreCase = true) }
            }
            usedNames += uniqueName

            val mime = effectiveVideoMime(doc, uniqueName)
            val fileId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val createdAt = doc.dateSeconds * 1000L
            val extra = FileTagsCodec.writeTags(emptyMap(), resolved.tags)

            val manifest = RemoteFileManifest(
                fileId = fileId,
                name = uniqueName,
                folderPath = folderPath,
                folderId = folderId,
                mimeType = mime,
                sizeBytes = doc.sizeBytes,
                encrypted = false,
                createdAt = createdAt,
                modifiedAt = createdAt,
                width = doc.width,
                height = doc.height,
                durationMs = doc.durationMs,
                extra = extra
            )
            val caption = manifestCodec.encode(manifest, encrypt = false)

            val copied = try {
                telegramClient.copyDocument(
                    chatId = storageChatId,
                    remoteFileId = doc.remoteFileId,
                    fileName = uniqueName,
                    mimeType = mime,
                    caption = caption
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: TelegramException) {
                SafeLog.w(TAG, "copyDocument failed for ${doc.messageId}: ${e.code}")
                continue
            }

            val entity = FileEntity(
                id = fileId,
                folderId = folderId,
                name = uniqueName,
                sizeBytes = copied.sizeBytes.takeIf { it > 0 } ?: doc.sizeBytes,
                mimeType = mime,
                category = FileCategory.fromMimeType(mime),
                localPath = null,
                contentHash = null,
                chatId = storageChatId,
                messageId = copied.messageId,
                remoteFileId = copied.remoteFileId,
                remoteUniqueId = copied.uniqueFileId,
                backupState = BackupState.BACKED_UP,
                isEncrypted = false,
                width = doc.width ?: copied.width,
                height = doc.height ?: copied.height,
                durationMs = doc.durationMs ?: copied.durationMs,
                pendingPublish = false,
                createdAt = createdAt,
                modifiedAt = createdAt,
                addedAt = now,
                manifestExtra = ExtraFields.toText(extra),
                remoteBase = FileBase.of(manifest).toText()
            )
            withContext(NonCancellable) {
                fileDao.upsert(entity)
                savedCount++
                savedDocs += doc

                if (autoDownload) {
                    transferRepository.enqueueDownload(entity.id)
                }
            }
        }

        if (savedDocs.isNotEmpty()) {
            val fetchedAt = System.currentTimeMillis()
            val entities = savedDocs.map { doc ->
                WatchedChannelFetchedEntity(
                    storageChatId = storageChatId,
                    watchedChatId = doc.chatId,
                    sourceMessageId = doc.messageId,
                    mediaAlbumId = doc.mediaAlbumId,
                    fetchedAt = fetchedAt
                )
            }
            watchedChannelHistoryDao.upsertFetched(entities)
        }

        if (createdFolders) {
            publishScheduler.kick()
        }

        return savedCount
    }

    private fun isManifestedOrEmpty(doc: RemoteDocument): Boolean =
        doc.remoteFileId.isBlank() ||
            doc.uniqueFileId.isBlank() ||
            doc.caption.startsWith("td1:") ||
            doc.caption.startsWith("tde1:")

    private fun effectiveVideoMime(doc: RemoteDocument, fileName: String = doc.fileName): String =
        if (MimeTypes.isVideo(doc.mimeType)) {
            doc.mimeType
        } else {
            val fromName = MimeTypes.fromFileName(fileName)
            if (MimeTypes.isVideo(fromName)) fromName else "video/mp4"
        }

    private fun TelegramException.toAppError(): AppError =
        if (isRateLimit) AppError.RateLimited(retryAfterSeconds ?: 0)
        else AppError.TelegramError(code, message)

    companion object {
        private const val TAG = "ChannelWatchManager"
        internal const val ALBUM_DEBOUNCE_MS = 2_000L
        private const val DEFAULT_HISTORY_LIMIT = 50
        private const val HISTORY_PAGE_SIZE = 100
        internal const val DEFAULT_CONCURRENCY = 4
        internal const val MAX_CONCURRENCY = 8
        private const val MAX_CONSECUTIVE_SKIPPED_PAGES = 15
        private const val MAX_HEAD_PAGES = 20
    }
}
