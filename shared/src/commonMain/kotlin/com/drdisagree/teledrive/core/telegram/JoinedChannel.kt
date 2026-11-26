package com.drdisagree.teledrive.core.telegram

data class JoinedChannel(
    val chatId: Long,
    val title: String,
    val isChannel: Boolean
)
