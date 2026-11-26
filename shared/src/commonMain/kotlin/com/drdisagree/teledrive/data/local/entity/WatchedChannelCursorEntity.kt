package com.drdisagree.teledrive.data.local.entity

import androidx.room.Entity

@Entity(
    tableName = "watched_channel_cursors",
    primaryKeys = ["storageChatId", "watchedChatId"]
)
data class WatchedChannelCursorEntity(
    val storageChatId: Long,
    val watchedChatId: Long,
    val newestScannedMessageId: Long = 0L,
    val oldestScannedMessageId: Long = 0L,
    val hasReachedEnd: Boolean = false
)
