package com.drdisagree.teledrive.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.drdisagree.teledrive.data.local.entity.WatchedChannelCursorEntity
import com.drdisagree.teledrive.data.local.entity.WatchedChannelFetchedEntity

@Dao
interface WatchedChannelHistoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFetched(entities: List<WatchedChannelFetchedEntity>)

    @Query(
        """SELECT sourceMessageId FROM watched_channel_fetched_messages
           WHERE storageChatId = :storageChatId AND watchedChatId = :watchedChatId
             AND sourceMessageId IN (:messageIds)"""
    )
    suspend fun getFetchedMessageIds(
        storageChatId: Long,
        watchedChatId: Long,
        messageIds: List<Long>
    ): List<Long>

    @Query(
        """SELECT * FROM watched_channel_cursors
           WHERE storageChatId = :storageChatId AND watchedChatId = :watchedChatId"""
    )
    suspend fun getCursor(storageChatId: Long, watchedChatId: Long): WatchedChannelCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCursor(cursor: WatchedChannelCursorEntity)

    @Query(
        """DELETE FROM watched_channel_fetched_messages
           WHERE storageChatId = :storageChatId AND watchedChatId IN (:watchedChatIds)"""
    )
    suspend fun deleteFetchedForChannels(storageChatId: Long, watchedChatIds: List<Long>)

    @Query(
        """DELETE FROM watched_channel_cursors
           WHERE storageChatId = :storageChatId AND watchedChatId IN (:watchedChatIds)"""
    )
    suspend fun deleteCursorsForChannels(storageChatId: Long, watchedChatIds: List<Long>)

    @Query(
        """DELETE FROM watched_channel_fetched_messages
           WHERE storageChatId = :storageChatId"""
    )
    suspend fun deleteFetchedForStorage(storageChatId: Long)

    @Query(
        """DELETE FROM watched_channel_cursors
           WHERE storageChatId = :storageChatId"""
    )
    suspend fun deleteCursorsForStorage(storageChatId: Long)

    @Query(
        """SELECT DISTINCT watchedChatId FROM watched_channel_fetched_messages
           WHERE storageChatId = :storageChatId"""
    )
    suspend fun getTrackedChannels(storageChatId: Long): List<Long>
}
