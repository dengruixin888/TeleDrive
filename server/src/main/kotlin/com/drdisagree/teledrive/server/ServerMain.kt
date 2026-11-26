package com.drdisagree.teledrive.server

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.logging.DebugLogController
import com.drdisagree.teledrive.server.daemon.ServerLogStore
import com.drdisagree.teledrive.server.daemon.ServerWatchDaemon
import com.drdisagree.teledrive.server.di.serverModule
import com.drdisagree.teledrive.server.web.ServerWebConsole
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin

fun main(args: Array<String>) {
    SafeLog.verbose = true
    val config = ServerConfig.fromEnvironment(args)

    val koinApp = startKoin {
        modules(serverModule(config))
    }
    val koin = koinApp.koin
    val log = koin.get<ServerLogStore>()
    val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    koin.get<DebugLogController>().start(bgScope, alwaysVerbose = true)

    val daemon = koin.get<ServerWatchDaemon>()
    val webConsole = koin.get<ServerWebConsole>()

    daemon.start()
    webConsole.start()

    if (!config.authEnabled) {
        log.warn("TELEDRIVE_ADMIN_PASSWORD is not set; Web Console is bound to ${config.host}:${config.port} without authentication.")
    }

    val shutdownLatch = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(
        Thread({
            log.info("Shutting down TeleDrive Server...")
            runCatching { webConsole.stop() }
            runCatching { koinApp.close() }
            shutdownLatch.countDown()
        }, "teledrive-shutdown")
    )

    shutdownLatch.await()
}
