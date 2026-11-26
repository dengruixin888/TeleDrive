package com.drdisagree.teledrive.core.telegram

/** [remoteFileId] can change between sessions; [uniqueFileId] is the stable remote identity. */
data class RemoteDocument(
    val chatId: Long,
    val messageId: Long,
    val remoteFileId: String,
    val uniqueFileId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val caption: String,
    val dateSeconds: Int,
    val miniThumbnail: ByteArray? = null,
    val mediaAlbumId: Long = 0L,
    val sourceChannelTitle: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null
) {
    override fun equals(other: Any?): Boolean =
        other is RemoteDocument && other.chatId == chatId && other.messageId == messageId

    override fun hashCode(): Int = 31 * chatId.hashCode() + messageId.hashCode()
}
