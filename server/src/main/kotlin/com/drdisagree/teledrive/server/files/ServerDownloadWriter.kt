package com.drdisagree.teledrive.server.files

import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.FileNameUtils
import com.drdisagree.teledrive.core.files.FilePartialDownload
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.server.ServerConfig
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class ServerDownloadWriter(
    private val config: ServerConfig,
    private val settingsRepository: SettingsRepository
) : DownloadWriter {

    override fun write(
        fileName: String,
        mimeType: String,
        folderPath: String?,
        body: (OutputStream) -> Unit
    ): String? = runCatching {
        val target = directoryFor(folderPath)
        target.mkdirs()
        val unique = FileNameUtils.uniqueName(FileNameUtils.sanitize(fileName)) { candidate ->
            File(target, candidate).exists()
        }
        val destination = File(target, unique)
        destination.outputStream().use(body)
        destination.absolutePath
    }.getOrNull()

    override fun openPartial(
        fileName: String,
        mimeType: String,
        folderPath: String?,
        token: String?
    ): PartialDownload? = runCatching {
        FilePartialDownload.open(directoryFor(folderPath), fileName, token)
    }.getOrNull()

    override fun freeBytes(folderPath: String?): Long? = runCatching {
        val target = directoryFor(folderPath)
        target.mkdirs()
        target.usableSpace.takeIf { it > 0 }
    }.getOrNull()

    private fun directoryFor(folderPath: String?): File {
        val configured = runBlocking { settingsRepository.preferences.first() }.downloadDirectory
        val base = configured?.takeIf { it.isNotBlank() }?.let(::File) ?: config.downloadDir.absoluteFile
        return folderPath
            ?.split('/')
            ?.filter { it.isNotBlank() }
            ?.fold(base) { parent, segment -> File(parent, FileNameUtils.sanitize(segment)) }
            ?: base
    }
}
