package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.telegram.RemoteDocument
import com.drdisagree.teledrive.data.local.FileQueryBuilder
import com.drdisagree.teledrive.data.mapper.toDomain
import com.drdisagree.teledrive.data.remote.telegram.FileTagsCodec
import com.drdisagree.teledrive.domain.model.FileQuerySpec
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.testing.unused
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelWatchSyncTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val telegram = harness.telegram
    private val enqueuedDownloads = mutableListOf<String>()

    private fun newManager(): ChannelWatchManager {
        val fakeTransfers = object : TransferRepository by unused() {
            override suspend fun enqueueDownload(fileId: String, priority: Int): AppResult<String> {
                enqueuedDownloads += fileId
                return AppResult.Success("download-$fileId")
            }
        }
        return ChannelWatchManager(
            telegramClient = telegram,
            settingsRepository = harness.settings,
            fileDao = database.fileDao(),
            folderDao = database.folderDao(),
            watchedChannelHistoryDao = database.watchedChannelHistoryDao(),
            folderPathResolver = harness.folderPaths,
            manifestCodec = harness.codec,
            publishScheduler = harness.scheduler,
            transferRepository = fakeTransfers
        )
    }

    @After
    fun tearDown() = harness.close()

    @Test
    fun `live single video creates channel folder saves tags and matches tag search`() = runBlocking {
        val watchedChatId = -1001L
        harness.settings.update { it.copy(watchedChannelIds = setOf(watchedChatId)) }
        val manager = newManager()
        val job = launch { manager.watch() }

        try {
            withTimeout(5_000) {
                telegram.incomingVideos.subscriptionCount.first { it > 0 }
            }
            telegram.incomingVideos.emit(
                RemoteDocument(
                    chatId = watchedChatId,
                    messageId = 500L,
                    remoteFileId = "ch-r-500",
                    uniqueFileId = "ch-u-500",
                    fileName = "VID_20260501.mp4",
                    mimeType = "video/mp4",
                    sizeBytes = 4096L,
                    caption = "#小姨妈 #陆雪琪 #AI短视频 #3D国漫\n⭐作品来源于国漫精品社区",
                    dateSeconds = 1_000,
                    sourceChannelTitle = "国漫频道"
                )
            )
            awaitCondition(5_000) { telegram.copiedDocuments.size == 1 && harness.scheduler.kicks >= 1 }
        } finally {
            job.cancel()
            job.join()
        }

        assertEquals(1, telegram.openedChats)
        assertEquals(1, telegram.closedChats)

        val channelFolder = database.folderDao().childrenOf(null, TEST_CHAT)
            .single { it.name == "国漫频道" }
        val copied = telegram.copiedDocuments.single()
        val decoded = harness.codec.decode(copied.caption)!!
        assertEquals("#小姨妈 #陆雪琪 #AI短视频 #3D国漫.mp4", decoded.name)
        assertEquals("国漫频道", decoded.folderPath)
        assertFalse(decoded.encrypted)
        assertEquals(
            listOf("#小姨妈", "#陆雪琪", "#AI短视频", "#3D国漫"),
            FileTagsCodec.readTags(decoded.extra)
        )

        val savedEntity = database.fileDao().byId(decoded.fileId)!!
        assertEquals(channelFolder.id, savedEntity.folderId)
        assertFalse(savedEntity.isEncrypted)
        assertEquals(
            listOf("#小姨妈", "#陆雪琪", "#AI短视频", "#3D国漫"),
            savedEntity.toDomain().tags
        )
        assertTrue(database.folderDao().pendingPublishCount() > 0)
        assertTrue(harness.scheduler.kicks >= 1)

        val tagMatches = database.fileDao()
            .observeList(FileQueryBuilder.build(FileQuerySpec(nameQuery = "#小姨妈", filterByFolder = false)))
            .first()
        assertEquals(listOf(savedEntity.id), tagMatches.map { it.id })
    }

    @Test
    fun `live multi video album debounces into album subfolder while single item album stays flat`() = runBlocking {
        val watchedChatId = -1002L
        harness.settings.update { it.copy(watchedChannelIds = setOf(watchedChatId)) }
        val manager = newManager()
        val job = launch { manager.watch() }

        try {
            withTimeout(5_000) {
                telegram.incomingVideos.subscriptionCount.first { it > 0 }
            }
            telegram.incomingVideos.emit(
                RemoteDocument(
                    chatId = watchedChatId,
                    messageId = 601L,
                    remoteFileId = "ch-r-601",
                    uniqueFileId = "ch-u-601",
                    fileName = "document.mp4",
                    mimeType = "video/mp4",
                    sizeBytes = 2048L,
                    caption = "#AI短视频 #3D国漫\n作品来源",
                    dateSeconds = 2_000,
                    mediaAlbumId = 9001L,
                    sourceChannelTitle = "国漫频道"
                )
            )
            telegram.incomingVideos.emit(
                RemoteDocument(
                    chatId = watchedChatId,
                    messageId = 602L,
                    remoteFileId = "ch-r-602",
                    uniqueFileId = "ch-u-602",
                    fileName = "document.mp4",
                    mimeType = "video/mp4",
                    sizeBytes = 3072L,
                    caption = "",
                    dateSeconds = 2_000,
                    mediaAlbumId = 9001L,
                    sourceChannelTitle = "国漫频道"
                )
            )
            awaitCondition(5_000) { telegram.copiedDocuments.size == 2 }

            // Mixed photo + 1 video album has only 1 video in this albumId, so no extra album folder.
            telegram.incomingVideos.emit(
                RemoteDocument(
                    chatId = watchedChatId,
                    messageId = 701L,
                    remoteFileId = "ch-r-701",
                    uniqueFileId = "ch-u-701",
                    fileName = "video_701.mp4",
                    mimeType = "video/mp4",
                    sizeBytes = 1024L,
                    caption = "单视频合集测试",
                    dateSeconds = 3_000,
                    mediaAlbumId = 9002L,
                    sourceChannelTitle = "国漫频道"
                )
            )
            awaitCondition(5_000) {
                telegram.copiedDocuments.size == 3 &&
                    database.fileDao().byRemoteUniqueIds(listOf("ch-u-701")).isNotEmpty()
            }
        } finally {
            job.cancel()
            job.join()
        }

        val channelFolder = database.folderDao().childrenOf(null, TEST_CHAT)
            .single { it.name == "国漫频道" }
        val albumFolder = database.folderDao().childrenOf(channelFolder.id, TEST_CHAT)
            .single { it.name == "#AI短视频 #3D国漫" }

        val albumFiles = database.fileDao()
            .observeList(FileQueryBuilder.build(FileQuerySpec(filterByFolder = true, folderId = albumFolder.id)))
            .first()
            .sortedBy { it.name }
        assertEquals(
            listOf("#AI短视频 #3D国漫_01.mp4", "#AI短视频 #3D国漫_02.mp4"),
            albumFiles.map { it.name }
        )
        assertTrue(albumFiles.all { it.toDomain().tags == listOf("#AI短视频", "#3D国漫") })

        val channelDirectFiles = database.fileDao()
            .observeList(FileQueryBuilder.build(FileQuerySpec(filterByFolder = true, folderId = channelFolder.id)))
            .first()
        assertEquals(listOf("单视频合集测试.mp4"), channelDirectFiles.map { it.name })
    }

    @Test
    fun `history sync skips duplicates and triggers optional local download`() = runBlocking {
        val watchedChatId = -1003L
        val existingDoc = RemoteDocument(
            chatId = watchedChatId,
            messageId = 801L,
            remoteFileId = "hist-r-1",
            uniqueFileId = "hist-u-1",
            fileName = "已存在.mp4",
            mimeType = "video/mp4",
            sizeBytes = 1024L,
            caption = "#旧视频",
            dateSeconds = 1_000,
            sourceChannelTitle = "历史频道"
        )
        val newDoc = RemoteDocument(
            chatId = watchedChatId,
            messageId = 802L,
            remoteFileId = "hist-r-2",
            uniqueFileId = "hist-u-2",
            fileName = "video_802.mp4",
            mimeType = "video/mp4",
            sizeBytes = 2048L,
            caption = "#新番 #4K",
            dateSeconds = 2_000,
            sourceChannelTitle = "历史频道"
        )
        telegram.channelHistoryVideos = mapOf(watchedChatId to listOf(existingDoc))

        val manager = newManager()
        harness.settings.update {
            it.copy(
                watchedChannelIds = setOf(watchedChatId),
                watchedChannelAutoDownload = false
            )
        }
        val firstRun = manager.syncWatchedChannelHistory()
        assertEquals(AppResult.Success(1), firstRun)
        assertTrue(enqueuedDownloads.isEmpty())

        telegram.channelHistoryVideos = mapOf(watchedChatId to listOf(existingDoc, newDoc))
        harness.settings.update {
            it.copy(
                watchedChannelIds = setOf(watchedChatId),
                watchedChannelAutoDownload = true
            )
        }
        val secondRun = manager.syncWatchedChannelHistory()
        assertEquals(AppResult.Success(1), secondRun)
        assertEquals(1, enqueuedDownloads.size)
        val savedNew = database.fileDao().byRemoteUniqueIds(listOf("hist-u-2")).single()
        assertEquals(listOf(savedNew.id), enqueuedDownloads)
        assertEquals("#新番 #4K.mp4", savedNew.name)
    }

    @Test
    fun `history sync counts quota by post not by video file so multi video album counts as 1 message`() = runBlocking {
        val watchedChatId = -2001L
        val post1 = RemoteDocument(
            chatId = watchedChatId,
            messageId = 100L,
            remoteFileId = "p1-r-100",
            uniqueFileId = "p1-u-100",
            fileName = "v100.mp4",
            mimeType = "video/mp4",
            sizeBytes = 1024L,
            caption = "#第一集",
            dateSeconds = 3000,
            sourceChannelTitle = "测试频道"
        )
        val post2a = RemoteDocument(
            chatId = watchedChatId,
            messageId = 90L,
            remoteFileId = "p2-r-90",
            uniqueFileId = "p2-u-90",
            fileName = "v90.mp4",
            mimeType = "video/mp4",
            sizeBytes = 2048L,
            caption = "#第二集合集",
            dateSeconds = 2000,
            mediaAlbumId = 8888L,
            sourceChannelTitle = "测试频道"
        )
        val post2b = RemoteDocument(
            chatId = watchedChatId,
            messageId = 89L,
            remoteFileId = "p2-r-89",
            uniqueFileId = "p2-u-89",
            fileName = "v89.mp4",
            mimeType = "video/mp4",
            sizeBytes = 3072L,
            caption = "",
            dateSeconds = 2000,
            mediaAlbumId = 8888L,
            sourceChannelTitle = "测试频道"
        )
        val post3 = RemoteDocument(
            chatId = watchedChatId,
            messageId = 80L,
            remoteFileId = "p3-r-80",
            uniqueFileId = "p3-u-80",
            fileName = "v80.mp4",
            mimeType = "video/mp4",
            sizeBytes = 1024L,
            caption = "#第三集",
            dateSeconds = 1000,
            sourceChannelTitle = "测试频道"
        )

        telegram.channelHistoryVideos = mapOf(watchedChatId to listOf(post1, post2a, post2b, post3))
        harness.settings.update {
            it.copy(
                watchedChannelIds = setOf(watchedChatId),
                watchedChannelHistoryLimit = 2
            )
        }

        val manager = newManager()
        val result = manager.syncWatchedChannelHistory()

        assertEquals(AppResult.Success(3), result)
        assertEquals(3, telegram.copiedDocuments.size)
        val savedUniqueIds = database.fileDao().observeList(FileQueryBuilder.build(FileQuerySpec())).first().map { it.remoteUniqueId }
        assertTrue(savedUniqueIds.contains("p1-u-100"))
        assertTrue(savedUniqueIds.contains("p2-u-90"))
        assertTrue(savedUniqueIds.contains("p2-u-89"))
        assertFalse(savedUniqueIds.contains("p3-u-80"))
    }

    @Test
    fun `history sync incremental run skips already fetched posts permanently even if deleted from drive`() = runBlocking {
        val watchedChatId = -2002L
        val doc1 = RemoteDocument(
            chatId = watchedChatId,
            messageId = 150L,
            remoteFileId = "perm-r-150",
            uniqueFileId = "perm-u-150",
            fileName = "v150.mp4",
            mimeType = "video/mp4",
            sizeBytes = 1024L,
            caption = "#永久去重1",
            dateSeconds = 2000,
            sourceChannelTitle = "测试频道2"
        )
        val doc2 = RemoteDocument(
            chatId = watchedChatId,
            messageId = 140L,
            remoteFileId = "perm-r-140",
            uniqueFileId = "perm-u-140",
            fileName = "v140.mp4",
            mimeType = "video/mp4",
            sizeBytes = 1024L,
            caption = "#永久去重2",
            dateSeconds = 1000,
            sourceChannelTitle = "测试频道2"
        )

        telegram.channelHistoryVideos = mapOf(watchedChatId to listOf(doc1, doc2))
        harness.settings.update {
            it.copy(
                watchedChannelIds = setOf(watchedChatId),
                watchedChannelHistoryLimit = 10
            )
        }

        val manager = newManager()
        val firstResult = manager.syncWatchedChannelHistory()
        assertEquals(AppResult.Success(2), firstResult)

        val allFiles = database.fileDao().observeList(FileQueryBuilder.build(FileQuerySpec())).first()
        database.fileDao().deleteByIds(allFiles.map { it.id })

        val secondResult = manager.syncWatchedChannelHistory()
        assertEquals(AppResult.Success(0), secondResult)
    }

    @Test
    fun `reset history records clears tracking and allows re fetching previously fetched posts`() = runBlocking {
        val watchedChatId = -2003L
        val doc = RemoteDocument(
            chatId = watchedChatId,
            messageId = 200L,
            remoteFileId = "rst-r-200",
            uniqueFileId = "rst-u-200",
            fileName = "vrst.mp4",
            mimeType = "video/mp4",
            sizeBytes = 1024L,
            caption = "#重置测试",
            dateSeconds = 1000,
            sourceChannelTitle = "测试频道3"
        )

        telegram.channelHistoryVideos = mapOf(watchedChatId to listOf(doc))
        harness.settings.update {
            it.copy(
                watchedChannelIds = setOf(watchedChatId),
                watchedChannelHistoryLimit = 10
            )
        }

        val manager = newManager()
        val firstResult = manager.syncWatchedChannelHistory()
        assertEquals(AppResult.Success(1), firstResult)

        val saved = database.fileDao().byRemoteUniqueIds(listOf("rst-u-200")).single()
        database.fileDao().deleteByIds(listOf(saved.id))

        assertEquals(AppResult.Success(0), manager.syncWatchedChannelHistory())

        val resetResult = manager.resetHistoryForChannels(listOf(watchedChatId))
        assertTrue(resetResult is AppResult.Success)

        val thirdResult = manager.syncWatchedChannelHistory()
        assertEquals(AppResult.Success(1), thirdResult)
    }

    @Test
    fun `multi channel history sync runs concurrently and isolates per channel failures`() = runBlocking {
        val ch1 = -3001L
        val ch2 = -3002L
        val ch3 = -3003L

        val doc1 = RemoteDocument(
            chatId = ch1, messageId = 10L, remoteFileId = "mc-r-1", uniqueFileId = "mc-u-1",
            fileName = "c1.mp4", mimeType = "video/mp4", sizeBytes = 1024L, caption = "#频道1",
            dateSeconds = 1000, sourceChannelTitle = "频道A"
        )
        val doc3a = RemoteDocument(
            chatId = ch3, messageId = 20L, remoteFileId = "mc-r-2", uniqueFileId = "mc-u-2",
            fileName = "c3a.mp4", mimeType = "video/mp4", sizeBytes = 1024L, caption = "#频道3a",
            dateSeconds = 1000, sourceChannelTitle = "频道C"
        )
        val doc3b = RemoteDocument(
            chatId = ch3, messageId = 21L, remoteFileId = "mc-r-3", uniqueFileId = "mc-u-3",
            fileName = "c3b.mp4", mimeType = "video/mp4", sizeBytes = 1024L, caption = "#频道3b",
            dateSeconds = 1000, sourceChannelTitle = "频道C"
        )

        telegram.channelHistoryVideos = mapOf(
            ch1 to listOf(doc1),
            ch2 to emptyList(),
            ch3 to listOf(doc3a, doc3b)
        )
        harness.settings.update {
            it.copy(
                watchedChannelIds = setOf(ch1, ch2, ch3),
                watchedChannelHistoryLimit = 10
            )
        }

        val manager = newManager()
        val result = manager.syncWatchedChannelHistory(concurrency = 4)
        assertEquals(AppResult.Success(3), result)

        val savedUniqueIds = database.fileDao().observeList(FileQueryBuilder.build(FileQuerySpec())).first().map { it.remoteUniqueId }
        assertTrue(savedUniqueIds.contains("mc-u-1"))
        assertTrue(savedUniqueIds.contains("mc-u-2"))
        assertTrue(savedUniqueIds.contains("mc-u-3"))
    }

    @Test
    fun `sync auto organizes manually forwarded unmanifested single video and multi video album`() = runBlocking {
        telegram.documents = listOf(
            RemoteDocument(
                chatId = TEST_CHAT,
                messageId = 10L,
                remoteFileId = "fwd-r-10",
                uniqueFileId = "fwd-u-10",
                fileName = "IMG_0010.mp4",
                mimeType = "video/mp4",
                sizeBytes = 4_000L,
                caption = "#小姨妈 #陆雪琪",
                dateSeconds = 100,
                sourceChannelTitle = "转发来源频道"
            ),
            RemoteDocument(
                chatId = TEST_CHAT,
                messageId = 20L,
                remoteFileId = "fwd-r-20",
                uniqueFileId = "fwd-u-20",
                fileName = "VID_0020.mp4",
                mimeType = "video/mp4",
                sizeBytes = 5_000L,
                caption = "#AI短视频 #3D国漫",
                dateSeconds = 100,
                mediaAlbumId = 5555L,
                sourceChannelTitle = "转发来源频道"
            ),
            RemoteDocument(
                chatId = TEST_CHAT,
                messageId = 21L,
                remoteFileId = "fwd-r-21",
                uniqueFileId = "fwd-u-21",
                fileName = "VID_0021.mp4",
                mimeType = "video/mp4",
                sizeBytes = 6_000L,
                caption = "",
                dateSeconds = 100,
                mediaAlbumId = 5555L,
                sourceChannelTitle = "转发来源频道"
            )
        )

        val result = harness.newSync().fullResync()
        assertTrue(result is AppResult.Success)

        val channelFolder = database.folderDao().childrenOf(null, TEST_CHAT)
            .single { it.name == "转发来源频道" }
        val albumFolder = database.folderDao().childrenOf(channelFolder.id, TEST_CHAT)
            .single { it.name == "#AI短视频 #3D国漫" }

        val singleFile = database.fileDao().byRemoteUniqueIds(listOf("fwd-u-10")).single()
        assertEquals(channelFolder.id, singleFile.folderId)
        assertEquals("#小姨妈 #陆雪琪.mp4", singleFile.name)
        assertEquals(listOf("#小姨妈", "#陆雪琪"), singleFile.toDomain().tags)
        assertTrue(singleFile.pendingPublish)

        val albumFiles = database.fileDao().byRemoteUniqueIds(listOf("fwd-u-20", "fwd-u-21"))
            .sortedBy { it.name }
        assertEquals(listOf("#AI短视频 #3D国漫_01.mp4", "#AI短视频 #3D国漫_02.mp4"), albumFiles.map { it.name })
        assertTrue(albumFiles.all { it.folderId == albumFolder.id && it.pendingPublish })
        assertNotNull(FileTagsCodec.readTags(albumFiles[1].manifestExtra).takeIf { it == listOf("#AI短视频", "#3D国漫") })
        assertTrue(database.folderDao().pendingPublishCount() > 0)
        assertTrue(harness.scheduler.kicks >= 1)
    }

    @Test
    fun `videos originating from active storage channel are never copied back into storage`() = runBlocking {
        telegram.channelHistoryVideos = mapOf(
            TEST_CHAT to listOf(
                RemoteDocument(
                    chatId = TEST_CHAT,
                    messageId = 901L,
                    remoteFileId = "self-r-901",
                    uniqueFileId = "self-u-901",
                    fileName = "self_video.mp4",
                    mimeType = "video/mp4",
                    sizeBytes = 1024L,
                    caption = "#self",
                    dateSeconds = 1_000,
                    sourceChannelTitle = "TeleDrive"
                )
            )
        )
        harness.settings.update { it.copy(watchedChannelIds = setOf(TEST_CHAT)) }

        val manager = newManager()
        val job = launch { manager.watch() }
        try {
            val historyResult = manager.syncWatchedChannelHistory()
            assertEquals(AppResult.Success(0), historyResult)

            telegram.incomingVideos.emit(
                RemoteDocument(
                    chatId = TEST_CHAT,
                    messageId = 902L,
                    remoteFileId = "self-r-902",
                    uniqueFileId = "self-u-902",
                    fileName = "live_self.mp4",
                    mimeType = "video/mp4",
                    sizeBytes = 2048L,
                    caption = "#live",
                    dateSeconds = 2_000,
                    sourceChannelTitle = "TeleDrive"
                )
            )
            delay(200)
        } finally {
            job.cancel()
            job.join()
        }

        assertTrue(telegram.copiedDocuments.isEmpty())
    }

    private suspend fun awaitCondition(timeoutMs: Long, predicate: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!predicate()) {
                delay(25)
            }
        }
    }
}
