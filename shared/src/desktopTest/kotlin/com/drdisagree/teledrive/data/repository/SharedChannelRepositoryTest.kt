package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.telegram.StorageChannel
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.domain.repository.ExclusionRepository
import com.drdisagree.teledrive.domain.repository.SyncRepository
import com.drdisagree.teledrive.testing.unused
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedChannelRepositoryTest {

    private val harness = SyncHarness()
    private val channels = harness.database.storageChannelDao()

    @After
    fun tearDown() = harness.close()

    @Test
    fun `refresh persists shared ownership flag and updates it when changed`() = runBlocking {
        var remote = listOf(
            StorageChannel(chatId = 10L, title = "TeleDrive 主号资源", documentCount = 42, isOwned = false),
            StorageChannel(chatId = 20L, title = "TeleDrive Alt", documentCount = 3, isOwned = true)
        )
        val repository = repositoryFor(
            listChannels = { remote }
        )

        val first = repository.refresh()
        assertTrue(first is AppResult.Success)
        val initial = repository.observeChannels().first().associateBy { it.chatId }
        assertFalse(initial.getValue(10L).isOwned)
        assertTrue(initial.getValue(20L).isOwned)
        assertEquals(42, initial.getValue(10L).remoteFileCount)

        // Ownership changed (e.g., ownership transferred or promoted/adjusted remotely)
        remote = listOf(
            StorageChannel(chatId = 10L, title = "TeleDrive 主号资源", documentCount = 50, isOwned = true),
            StorageChannel(chatId = 20L, title = "TeleDrive Alt", documentCount = 3, isOwned = false)
        )
        val second = repository.refresh()
        assertTrue(second is AppResult.Success)
        val updated = repository.observeChannels().first().associateBy { it.chatId }
        assertTrue(updated.getValue(10L).isOwned)
        assertFalse(updated.getValue(20L).isOwned)
    }

    @Test
    fun `deleteRemotely delegates shared drive removal and forgets local row`() = runBlocking {
        val removedRemote = mutableListOf<Long>()
        val repository = repositoryFor(
            listChannels = {
                listOf(
                    StorageChannel(chatId = 10L, title = "TeleDrive 共享", documentCount = 12, isOwned = false),
                    StorageChannel(chatId = 20L, title = "TeleDrive 自建", documentCount = 0, isOwned = true)
                )
            },
            onDelete = { removedRemote += it }
        )
        repository.refresh()

        val result = repository.deleteRemotely(10L)

        assertTrue(result is AppResult.Success)
        assertEquals(listOf(10L), removedRemote)
        assertNull(channels.byId(10L))
        assertTrue(channels.byId(20L)!!.isOwned)
    }

    private fun repositoryFor(
        listChannels: () -> List<StorageChannel>,
        onDelete: (Long) -> Unit = {}
    ): ChannelRepositoryImpl = ChannelRepositoryImpl(
        channelDao = channels,
        telegramClient = object : TelegramClient by unused() {
            override suspend fun listStorageChannels(knownChatIds: List<Long>): List<StorageChannel> =
                listChannels()

            override suspend fun fetchChannelPhoto(chatId: Long): String? = null

            override suspend fun chatExists(chatId: Long): Boolean? = true

            override suspend fun deleteStorageChannel(chatId: Long) {
                onDelete(chatId)
            }
        },
        settingsRepository = harness.settings,
        syncRepository = object : SyncRepository by unused() {
            override val lockedFileCounts: Flow<Map<Long, Int>> = flowOf(emptyMap())
        },
        channelOwnership = ChannelOwnership(
            harness.database.fileDao(),
            harness.database.folderDao(),
            harness.database.exclusionDao()
        ),
        exclusionRepository = object : ExclusionRepository by unused() {
            override suspend fun ensureDefaults(chatId: Long) = Unit
        }
    )
}
