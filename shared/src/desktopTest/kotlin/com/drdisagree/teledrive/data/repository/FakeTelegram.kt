package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.telegram.JoinedChannel
import com.drdisagree.teledrive.core.telegram.MessageChange
import com.drdisagree.teledrive.core.telegram.RemoteDocument
import com.drdisagree.teledrive.core.telegram.RemoteDocumentPage
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.core.telegram.TelegramUploadEvent
import com.drdisagree.teledrive.data.remote.telegram.IndexSnapshotFile
import com.drdisagree.teledrive.data.remote.telegram.RemoteFolderState
import com.drdisagree.teledrive.testing.unused
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json

internal class FakeTelegram : TelegramClient by unused() {
    var documents: List<RemoteDocument> = emptyList()
    var joinedChannels: List<JoinedChannel> = emptyList()
    var channelHistoryVideos: Map<Long, List<RemoteDocument>> = emptyMap()
    val incomingVideos = MutableSharedFlow<RemoteDocument>(extraBufferCapacity = 1_000)
    val copiedDocuments = mutableListOf<RemoteDocument>()
    private val knownUniqueIdsByRemoteId = mutableMapOf<String, String>()
    val editedCaptions = mutableMapOf<Long, String>()
    val uploads = mutableListOf<Pair<String, String>>()
    val deletedMessages = mutableListOf<Long>()
    var failDownloads = false
    val changes = MutableSharedFlow<MessageChange>(extraBufferCapacity = 1_000)
    var openedChats = 0
    var closedChats = 0
    private var nextMessageId = 2_000_000L
    var uploadDateSeconds = 100
    var onFetch: suspend (fromMessageId: Long) -> Unit = {}
    var onDeleteMessages: ((chatId: Long, messageIds: List<Long>) -> Unit)? = null
    private val stateFiles = mutableListOf<File>()
    private var folderStateDocument: RemoteDocument? = null
    private var folderStateFile: File? = null
    private val storedFiles = mutableMapOf<RemoteDocument, File>()

    fun publishFolderState(state: RemoteFolderState, messageId: Long = 1_000_000) {
        val file = File.createTempFile("folders", ".json").also { stateFiles += it }
        file.writeText(Json.encodeToString(RemoteFolderState.serializer(), state))
        folderStateFile = file
        folderStateDocument = RemoteDocument(
            chatId = TEST_CHAT,
            messageId = messageId,
            remoteFileId = "folder-state",
            uniqueFileId = "folder-state-$messageId",
            fileName = RemoteFolderState.FILE_NAME,
            mimeType = "application/json",
            sizeBytes = file.length(),
            caption = RemoteFolderState.MARKER,
            dateSeconds = 100
        )
    }

    fun addOlderFolderState(json: String, messageId: Long, dateSeconds: Int) {
        val file = File.createTempFile("folders", ".json").also { stateFiles += it }
        file.writeText(json)
        storedFiles[
            RemoteDocument(
                chatId = TEST_CHAT,
                messageId = messageId,
                remoteFileId = "folder-state-$messageId",
                uniqueFileId = "folder-state-$messageId",
                fileName = RemoteFolderState.FILE_NAME,
                mimeType = "application/json",
                sizeBytes = file.length(),
                caption = RemoteFolderState.MARKER,
                dateSeconds = dateSeconds
            )
        ] = file
    }

    fun publishRawFolderState(json: String, messageId: Long = 1_000_000) {
        publishFolderState(RemoteFolderState(), messageId)
        folderStateFile!!.writeText(json)
    }

    override suspend fun ensureStorageChat(knownChatId: Long?): Long = TEST_CHAT

    override suspend fun fetchDocuments(
        chatId: Long,
        fromMessageId: Long,
        limit: Int
    ): RemoteDocumentPage {
        onFetch(fromMessageId)
        val newestFirst = (
            documents +
                copiedDocuments.filter { it.chatId == chatId } +
                listOfNotNull(folderStateDocument) +
                storedFiles.keys
            )
            .sortedByDescending { it.messageId }
            .filter { fromMessageId == 0L || it.messageId < fromMessageId }
            .take(limit)
        return RemoteDocumentPage(
            documents = newestFirst,
            nextFromMessageId = newestFirst.lastOrNull()?.messageId ?: 0L
        )
    }

    fun uploadedFolderState(): RemoteFolderState = Json { ignoreUnknownKeys = true }
        .decodeFromString(
            RemoteFolderState.serializer(),
            uploads.last { it.first == RemoteFolderState.FILE_NAME }.second
        )

    override fun uploadDocument(
        chatId: Long,
        localPath: String,
        fileName: String,
        mimeType: String,
        caption: String,
        thumbnailPath: String?
    ): Flow<TelegramUploadEvent> {
        val content = File(localPath).readText()
        uploads += fileName to content
        val messageId = nextMessageId++
        val uploaded = RemoteDocument(
            chatId = chatId,
            messageId = messageId,
            remoteFileId = "uploaded-$messageId",
            uniqueFileId = "uploaded-unique-$messageId",
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = content.length.toLong(),
            caption = caption,
            dateSeconds = uploadDateSeconds
        )
        if (fileName == RemoteFolderState.FILE_NAME) {
            publishRawFolderState(content, messageId)
        }
        if (fileName == IndexSnapshotFile.FILE_NAME) {
            val copy = File.createTempFile("index", ".gz").also { stateFiles += it }
            File(localPath).copyTo(copy, overwrite = true)
            storedFiles[uploaded] = copy
        }
        return flowOf(TelegramUploadEvent.Completed(uploaded))
    }

    override suspend fun deleteMessages(chatId: Long, messageIds: List<Long>) {
        onDeleteMessages?.invoke(chatId, messageIds)
        deletedMessages += messageIds
        storedFiles.keys.removeAll { it.messageId in messageIds }
    }

    override suspend fun getDocument(chatId: Long, messageId: Long): RemoteDocument? =
        (documents + listOfNotNull(folderStateDocument) + storedFiles.keys)
            .firstOrNull { it.messageId == messageId }

    override fun messageChanges(chatId: Long): Flow<MessageChange> = changes

    override suspend fun openChat(chatId: Long) {
        openedChats++
    }

    override suspend fun closeChat(chatId: Long) {
        closedChats++
    }

    override fun downloadDocument(remoteFileId: String): Flow<TelegramDownloadEvent> {
        if (failDownloads) return flowOf()
        val file = storedFiles.entries.firstOrNull { it.key.remoteFileId == remoteFileId }?.value
            ?: folderStateFile
            ?: return flowOf()
        return flowOf(TelegramDownloadEvent.Completed(file.absolutePath, file.length()))
    }

    var onEditCaption: suspend (messageId: Long) -> Unit = {}

    override suspend fun editCaption(chatId: Long, messageId: Long, caption: String) {
        onEditCaption(messageId)
        editedCaptions[messageId] = caption
    }

    override suspend fun copyDocument(
        chatId: Long,
        remoteFileId: String,
        fileName: String,
        mimeType: String,
        caption: String
    ): RemoteDocument {
        val messageId = nextMessageId++
        val source = (documents + channelHistoryVideos.values.flatten() + copiedDocuments)
            .firstOrNull { it.remoteFileId == remoteFileId }
        val uniqueId = source?.uniqueFileId
            ?: knownUniqueIdsByRemoteId[remoteFileId]
            ?: "copied-unique-$messageId"
        val copied = RemoteDocument(
            chatId = chatId,
            messageId = messageId,
            remoteFileId = "copied-remote-$messageId",
            uniqueFileId = uniqueId,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = source?.sizeBytes ?: 1024L,
            caption = caption,
            dateSeconds = source?.dateSeconds ?: uploadDateSeconds,
            width = source?.width,
            height = source?.height,
            durationMs = source?.durationMs
        )
        copiedDocuments += copied
        return copied
    }

    override suspend fun listJoinedChannels(): List<JoinedChannel> = joinedChannels

    override fun incomingChannelVideos(watchedChatIds: Set<Long>): Flow<RemoteDocument> =
        incomingVideos
            .onEach { doc -> knownUniqueIdsByRemoteId[doc.remoteFileId] = doc.uniqueFileId }
            .filter { it.chatId in watchedChatIds }

    override suspend fun fetchChannelVideos(
        chatId: Long,
        fromMessageId: Long,
        limit: Int
    ): List<RemoteDocument> {
        val all = channelHistoryVideos[chatId].orEmpty()
            .onEach { doc -> knownUniqueIdsByRemoteId[doc.remoteFileId] = doc.uniqueFileId }
        val filtered = if (fromMessageId > 0L) {
            all.filter { it.messageId < fromMessageId }
        } else {
            all
        }
        return filtered.sortedByDescending { it.messageId }.take(limit)
    }

    fun cleanUp() {
        stateFiles.forEach { it.delete() }
    }
}
