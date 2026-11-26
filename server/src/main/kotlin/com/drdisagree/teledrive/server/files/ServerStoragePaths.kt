package com.drdisagree.teledrive.server.files

import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.server.ServerConfig
import java.io.File

class ServerStoragePaths(
    config: ServerConfig
) : AppStoragePaths {

    private val root: File = config.dataDir.absoluteFile.apply { mkdirs() }

    override val filesDir: File
        get() = File(root, "files").apply { mkdirs() }

    override val cacheDir: File
        get() = File(root, "cache").apply { mkdirs() }

    override val externalCacheDir: File? = null

    override val externalStorageRoot: File? = null
}
