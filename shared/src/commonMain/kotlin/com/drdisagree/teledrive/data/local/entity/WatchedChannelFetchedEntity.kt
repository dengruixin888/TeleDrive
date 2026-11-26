package com.drdisagree.teledrive.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "watched_channel_fetched_messages",
    primaryKeys = ["storageChatId", "watchedChatId", "sourceMessageId"],
    indices = [
        Index(value = ["storageChatId", "watchedChatId"])
    ]
)
data class WatchedChannelFetchedEntity(
    val storageChatId: Long,
    val watchedChatId: Long,
    val sourceMessageId: Long,
    val mediaAlbumId: Long = 0L,
    val fetchedAt: Long = System.currentTimeMillis()
)
