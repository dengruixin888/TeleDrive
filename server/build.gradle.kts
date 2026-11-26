plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

val generateServerBuildInfo = tasks.register("generateServerBuildInfo") {
    val version = libs.versions.appVersion.get()
    val outDir = layout.buildDirectory.dir("generated/buildinfo")
    inputs.property("version", version)
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("com/drdisagree/teledrive/server/ServerBuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            listOf(
                "package com.drdisagree.teledrive.server",
                "",
                "object ServerBuildInfo {",
                "    const val VERSION = \"$version\"",
                "}",
                ""
            ).joinToString(System.lineSeparator())
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateServerBuildInfo)
}

application {
    mainClass.set("com.drdisagree.teledrive.server.ServerMainKt")
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.koin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
