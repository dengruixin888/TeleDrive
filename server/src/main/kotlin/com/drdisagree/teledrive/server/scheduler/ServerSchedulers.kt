package com.drdisagree.teledrive.server.scheduler

import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.publish.PublishOutboxDrainer
import com.drdisagree.teledrive.core.publish.PublishScheduler
import com.drdisagree.teledrive.core.transfer.BackupResumeScheduler
import com.drdisagree.teledrive.core.transfer.MaintenanceScheduler
import com.drdisagree.teledrive.core.transfer.TransferDrainResult
import com.drdisagree.teledrive.core.transfer.TransferQueueDrainer
import com.drdisagree.teledrive.core.transfer.TransferScheduler
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ServerPublishScheduler(
    private val drainer: PublishOutboxDrainer,
    dispatchers: DispatcherProvider
) : PublishScheduler {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val draining = AtomicBoolean(false)

    override fun kick() {
        if (!draining.compareAndSet(false, true)) return
        scope.launch {
            try {
                var attempts = 0
                while (!drainer.drain(isStopped = { false }) && attempts++ < MAX_ATTEMPTS) {
                    delay(RETRY_DELAY_SECONDS.seconds)
                }
            } finally {
                draining.set(false)
            }
        }
    }

    private companion object {
        const val RETRY_DELAY_SECONDS = 30
        const val MAX_ATTEMPTS = 10
    }
}

class ServerTransferScheduler(
    private val drainer: TransferQueueDrainer,
    dispatchers: DispatcherProvider
) : TransferScheduler {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val draining = AtomicBoolean(false)
    private val requested = AtomicBoolean(false)

    override fun kick(allowMetered: Boolean) {
        requested.set(true)
        if (!draining.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (requested.getAndSet(false)) {
                    while (drainer.drain(
                            isStopped = { false },
                            onTerminalFailure = { }
                        ) == TransferDrainResult.INTERRUPTED
                    ) {
                        delay(RETRY_DELAY_SECONDS.seconds)
                    }
                }
            } finally {
                draining.set(false)
            }
            if (requested.get()) kick(allowMetered)
        }
    }

    override fun rekick(allowMetered: Boolean) {
        kick(allowMetered)
    }

    private companion object {
        const val RETRY_DELAY_SECONDS = 15
    }
}

class ServerBackupResumeScheduler : BackupResumeScheduler {
    override fun resumeWhen(chargingOnly: Boolean, wifiOnly: Boolean) = Unit
}

class ServerMaintenanceScheduler : MaintenanceScheduler {
    override fun scheduleUpdateCheck(enabled: Boolean) = Unit
    override fun scheduleAll(
        backupEnabled: Boolean,
        backupIntervalHours: Int,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
        instantBackup: Boolean,
        updateChecks: Boolean
    ) = Unit
}
