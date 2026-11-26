package com.drdisagree.teledrive.server.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.drdisagree.teledrive.core.crypto.CredentialCipher
import com.drdisagree.teledrive.core.crypto.FileWrappedKeyRepository
import com.drdisagree.teledrive.core.crypto.KeyBackupCodec
import com.drdisagree.teledrive.core.crypto.PassphraseKdf
import com.drdisagree.teledrive.core.crypto.SecureFileDeleter
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.TdlibDatabaseKeyProviderImpl
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DefaultDispatcherProvider
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.FileImporter
import com.drdisagree.teledrive.core.files.LocalCopyDeleter
import com.drdisagree.teledrive.core.files.PendingShare
import com.drdisagree.teledrive.core.files.sharedFilesModule
import com.drdisagree.teledrive.core.logging.debugLogModule
import com.drdisagree.teledrive.core.media.MediaMetadataExtractor
import com.drdisagree.teledrive.core.media.ThumbnailMemoryCache
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.network.NetworkMonitor
import com.drdisagree.teledrive.core.power.PowerMonitor
import com.drdisagree.teledrive.core.proxy.ProxyProbe
import com.drdisagree.teledrive.core.publish.PublishOutboxDrainer
import com.drdisagree.teledrive.core.publish.PublishScheduler
import com.drdisagree.teledrive.core.security.AppLockManager
import com.drdisagree.teledrive.core.telegram.DesktopTelegramClient
import com.drdisagree.teledrive.core.telegram.TdlibDatabaseKeyProvider
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramPacer
import com.drdisagree.teledrive.core.transfer.BackupResumeScheduler
import com.drdisagree.teledrive.core.transfer.BackupSessionTracker
import com.drdisagree.teledrive.core.transfer.CountingBackupSessionTracker
import com.drdisagree.teledrive.core.transfer.MaintenanceScheduler
import com.drdisagree.teledrive.core.transfer.TransferErrorMessages
import com.drdisagree.teledrive.core.transfer.TransferScheduler
import com.drdisagree.teledrive.core.transfer.transferEngineModule
import com.drdisagree.teledrive.data.local.database.MIGRATION_10_11
import com.drdisagree.teledrive.data.local.database.MIGRATION_11_12
import com.drdisagree.teledrive.data.local.database.MIGRATION_12_13
import com.drdisagree.teledrive.data.local.database.MIGRATION_13_14
import com.drdisagree.teledrive.data.local.database.MIGRATION_14_15
import com.drdisagree.teledrive.data.local.database.MIGRATION_15_16
import com.drdisagree.teledrive.data.local.database.MIGRATION_16_17
import com.drdisagree.teledrive.data.local.database.MIGRATION_17_18
import com.drdisagree.teledrive.data.local.database.MIGRATION_18_19
import com.drdisagree.teledrive.data.local.database.MIGRATION_1_2
import com.drdisagree.teledrive.data.local.database.MIGRATION_2_3
import com.drdisagree.teledrive.data.local.database.MIGRATION_3_4
import com.drdisagree.teledrive.data.local.database.MIGRATION_4_5
import com.drdisagree.teledrive.data.local.database.MIGRATION_5_6
import com.drdisagree.teledrive.data.local.database.MIGRATION_6_7
import com.drdisagree.teledrive.data.local.database.MIGRATION_7_8
import com.drdisagree.teledrive.data.local.database.MIGRATION_8_9
import com.drdisagree.teledrive.data.local.database.MIGRATION_9_10
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.data.local.database.daosModule
import com.drdisagree.teledrive.data.repository.LocalDataWiper
import com.drdisagree.teledrive.data.repository.repositoryModule
import com.drdisagree.teledrive.domain.usecase.useCaseModule
import com.drdisagree.teledrive.server.ServerBuildInfo
import com.drdisagree.teledrive.server.ServerConfig
import com.drdisagree.teledrive.server.crypto.LocalKeyCredentialCipher
import com.drdisagree.teledrive.server.daemon.ServerLogStore
import com.drdisagree.teledrive.server.daemon.ServerWatchDaemon
import com.drdisagree.teledrive.server.files.ServerDownloadWriter
import com.drdisagree.teledrive.server.files.ServerStoragePaths
import com.drdisagree.teledrive.server.platform.NoopMediaMetadataExtractor
import com.drdisagree.teledrive.server.platform.NoopThumbnailMemoryCache
import com.drdisagree.teledrive.server.platform.NoopThumbnailStore
import com.drdisagree.teledrive.server.platform.ServerFileImporter
import com.drdisagree.teledrive.server.platform.ServerLocalCopyDeleter
import com.drdisagree.teledrive.server.platform.ServerLocalDataWiper
import com.drdisagree.teledrive.server.platform.ServerNetworkMonitor
import com.drdisagree.teledrive.server.platform.ServerPendingShare
import com.drdisagree.teledrive.server.platform.ServerPowerMonitor
import com.drdisagree.teledrive.server.platform.ServerTransferErrorMessages
import com.drdisagree.teledrive.server.scheduler.ServerBackupResumeScheduler
import com.drdisagree.teledrive.server.scheduler.ServerMaintenanceScheduler
import com.drdisagree.teledrive.server.scheduler.ServerPublishScheduler
import com.drdisagree.teledrive.server.scheduler.ServerTransferScheduler
import com.drdisagree.teledrive.server.web.ServerWebConsole
import java.io.File
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toOkioPath
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

fun serverModule(config: ServerConfig) = module {
    includes(
        daosModule,
        repositoryModule,
        useCaseModule,
        sharedFilesModule,
        debugLogModule,
        transferEngineModule
    )

    single { config }
    singleOf(::ServerStoragePaths) bind AppStoragePaths::class
    singleOf(::LocalKeyCredentialCipher) bind CredentialCipher::class
    singleOf(::FileWrappedKeyRepository) bind WrappedKeyRepository::class
    singleOf(::TdlibDatabaseKeyProviderImpl) bind TdlibDatabaseKeyProvider::class
    singleOf(::DefaultDispatcherProvider) bind DispatcherProvider::class
    singleOf(::StreamCrypto)
    singleOf(::SecureFileDeleter)
    singleOf(::PassphraseKdf)
    singleOf(::KeyBackupCodec)
    singleOf(::TelegramPacer)
    singleOf(::ProxyProbe)
    singleOf(::AppLockManager)
    singleOf(::PublishOutboxDrainer)
    singleOf(::ServerNetworkMonitor) bind NetworkMonitor::class
    singleOf(::ServerPowerMonitor) bind PowerMonitor::class
    singleOf(::ServerTransferErrorMessages) bind TransferErrorMessages::class
    singleOf(::ServerDownloadWriter) bind DownloadWriter::class
    singleOf(::ServerFileImporter) bind FileImporter::class
    singleOf(::ServerPendingShare) bind PendingShare::class
    singleOf(::ServerLocalCopyDeleter) bind LocalCopyDeleter::class
    singleOf(::NoopMediaMetadataExtractor) bind MediaMetadataExtractor::class
    singleOf(::NoopThumbnailStore) bind ThumbnailStore::class
    singleOf(::NoopThumbnailMemoryCache) bind ThumbnailMemoryCache::class
    singleOf(::CountingBackupSessionTracker) bind BackupSessionTracker::class
    singleOf(::ServerTransferScheduler) bind TransferScheduler::class
    singleOf(::ServerBackupResumeScheduler) bind BackupResumeScheduler::class
    singleOf(::ServerMaintenanceScheduler) bind MaintenanceScheduler::class
    singleOf(::ServerLocalDataWiper) bind LocalDataWiper::class
    singleOf(::ServerPublishScheduler) bind PublishScheduler::class

    single<TelegramClient> {
        DesktopTelegramClient(get(), get(), get(), ServerBuildInfo.VERSION)
    }

    single<DataStore<Preferences>> {
        val storagePaths = get<AppStoragePaths>()
        PreferenceDataStoreFactory.createWithPath {
            File(File(storagePaths.filesDir, "datastore"), "settings.preferences_pb")
                .apply { parentFile?.mkdirs() }
                .toOkioPath()
        }
    }

    single<TeleDriveDatabase> {
        val storagePaths = get<AppStoragePaths>()
        Room.databaseBuilder<TeleDriveDatabase>(
            name = File(storagePaths.filesDir, "teledrive.db").absolutePath
        )
            .addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
                MIGRATION_17_18,
                MIGRATION_18_19
            )
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    single { ServerLogStore() }
    singleOf(::ServerWatchDaemon)
    singleOf(::ServerWebConsole)
}
