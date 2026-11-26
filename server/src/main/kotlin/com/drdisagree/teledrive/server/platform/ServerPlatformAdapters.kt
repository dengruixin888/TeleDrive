package com.drdisagree.teledrive.server.platform

import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.FileImporter
import com.drdisagree.teledrive.core.files.FileNameUtils
import com.drdisagree.teledrive.core.files.ImportSource
import com.drdisagree.teledrive.core.files.ImportedFile
import com.drdisagree.teledrive.core.files.LocalCleanup
import com.drdisagree.teledrive.core.files.LocalCopyDeleter
import com.drdisagree.teledrive.core.files.PendingShare
import com.drdisagree.teledrive.core.files.expandDirectory
import com.drdisagree.teledrive.core.media.MediaInfo
import com.drdisagree.teledrive.core.media.MediaMetadataExtractor
import com.drdisagree.teledrive.core.media.MediaPart
import com.drdisagree.teledrive.core.media.ThumbnailMemoryCache
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.network.NetworkMonitor
import com.drdisagree.teledrive.core.network.NetworkStatus
import com.drdisagree.teledrive.core.power.PowerMonitor
import com.drdisagree.teledrive.core.transfer.TransferErrorMessages
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.data.repository.LocalDataWiper
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

class ServerNetworkMonitor : NetworkMonitor {
    private val state = MutableStateFlow(NetworkStatus.UNMETERED)
    override val status: Flow<NetworkStatus> = state
    override fun currentStatus(): NetworkStatus = state.value
}

class ServerPowerMonitor : PowerMonitor {
    override val charging: Flow<Boolean> = flowOf(true)
    override fun isCharging(): Boolean = true
}

class ServerLocalCopyDeleter : LocalCopyDeleter {
    override fun delete(paths: List<String>): LocalCleanup {
        var deleted = 0
        for (path in paths) {
            val file = File(path)
            if (!file.exists() || file.delete()) deleted++
        }
        return LocalCleanup(deletedCount = deleted)
    }

    override fun isGone(path: String): Boolean = !File(path).exists()
}

class ServerFileImporter(
    private val storagePaths: AppStoragePaths
) : FileImporter {
    override fun import(reference: String): ImportedFile? {
        val file = File(reference)
        if (!file.isFile || !file.canRead()) return null
        return ImportedFile(
            path = file.absolutePath,
            name = FileNameUtils.sanitize(file.name),
            sizeBytes = file.length()
        )
    }

    override fun discard(imported: ImportedFile) {
        if (isStaged(imported.path)) File(imported.path).delete()
    }

    override fun expand(reference: String): List<ImportSource> =
        expandDirectory(reference) ?: listOf(ImportSource(reference, ""))

    override fun isStaged(path: String): Boolean =
        File(path).parentFile == File(storagePaths.filesDir, IMPORT_DIR)

    override fun sweepOrphans(referencedPaths: Set<String>) {
        val staged = File(storagePaths.filesDir, IMPORT_DIR).listFiles() ?: return
        for (file in staged) {
            if (file.isFile && file.absolutePath !in referencedPaths) {
                file.delete()
            }
        }
    }

    private companion object {
        const val IMPORT_DIR = "import"
    }
}

class ServerPendingShare : PendingShare {
    private val _uris = MutableStateFlow<List<String>>(emptyList())
    override val uris: StateFlow<List<String>> = _uris.asStateFlow()
    private val _text = MutableStateFlow<String?>(null)
    override val text: StateFlow<String?> = _text.asStateFlow()
    override fun offer(incoming: List<String>) = Unit
    override fun clear() = Unit
    override fun offerText(incoming: String?) = Unit
    override fun clearText() = Unit
}

class NoopMediaMetadataExtractor : MediaMetadataExtractor {
    override fun extract(file: File, mimeType: String): MediaInfo = MediaInfo(null, null, null)
}

class NoopThumbnailStore : ThumbnailStore {
    override suspend fun thumbnailBytes(fileId: String): ByteArray? = null
    override suspend fun uploadThumbnailFile(fileId: String): File? = null
    override suspend fun storeFromDecrypted(fileId: String, plain: File) = Unit
    override suspend fun storeFromStream(fileId: String, parts: List<MediaPart>, encrypted: Boolean) = Unit
}

class NoopThumbnailMemoryCache : ThumbnailMemoryCache {
    override fun remove(fileId: String) = Unit
    override fun clear() = Unit
}

class ServerLocalDataWiper(
    private val database: TeleDriveDatabase,
    private val storagePaths: AppStoragePaths
) : LocalDataWiper {
    override suspend fun wipe() {
        runCatching { database.close() }
        val files = storagePaths.filesDir
        for (name in WIPED_DIRS) {
            runCatching { File(files, name).deleteRecursively() }
        }
        files.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(DATABASE_PREFIX)) {
                runCatching { file.delete() }
            }
        }
        runCatching { storagePaths.cacheDir.deleteRecursively() }
    }

    private companion object {
        val WIPED_DIRS = listOf("tdlib", "keys", "datastore", "notes", "import")
        const val DATABASE_PREFIX = "teledrive.db"
    }
}

class ServerTransferErrorMessages : TransferErrorMessages {
    override val keyMissing: String = "Encryption key missing"
    override val noFileReference: String = "No file reference"
    override val fileRecordMissing: String = "File record missing"
    override val noLocalCopy: String = "No local copy"
    override val localFileGone: String = "Local file is missing"
    override val uploadEnded: String = "Upload ended unexpectedly"
    override val uploadStalled: String = "Upload stalled"
    override val downloadEnded: String = "Download ended unexpectedly"
    override val downloadStalled: String = "Download stalled"
    override val downloadedMissing: String = "Downloaded file missing"
    override val noRemoteCopy: String = "No remote copy"
    override val decryptionFailed: String = "Decryption failed"
    override val saveFailed: String = "Failed to save file"
    override val verifyFailed: String = "File verification failed"
    override fun destinationFull(neededBytes: Long): String = "Destination storage full (need $neededBytes bytes)"
    override fun cacheFull(neededBytes: Long): String = "Cache storage full (need $neededBytes bytes)"
}
