plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm("desktop")

    sourceSets {
        commonMain {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(project.dependencies.platform(libs.koin.bom))
                implementation(libs.koin.core)
                api(libs.androidx.paging.common)
                api(libs.androidx.room.runtime)
                api(libs.androidx.room.paging)
                api(libs.androidx.sqlite.bundled)
                api(libs.androidx.datastore.preferences.core)
            }
        }
        val jvmCommonMain = create("jvmCommonMain") {
            dependsOn(commonMain.get())
        }
        val jvmCommonTest = create("jvmCommonTest") {
            dependsOn(commonTest.get())
        }
        named("desktopMain") {
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.tdlight.java)
                // Pick the native for the host so cross-platform packages do not
                // carry a second, unused native set (Windows: Linux natives, and
                // vice versa). TDLight loads the matching library at runtime.
                // TDLight publishes macos_arm64 only, so Intel Macs have no build.
                val targetOs = (findProperty("tdlightNativesOs") as? String)
                    ?.lowercase()
                    ?.takeIf { it.isNotBlank() }
                    ?: System.getProperty("os.name").lowercase()
                val nativesClassifier = when {
                    targetOs.contains("win") -> "windows_amd64"
                    targetOs.contains("mac") -> "macos_arm64"
                    else -> "linux_amd64_gnu_ssl3"
                }
                implementation("it.tdlight:tdlight-natives:${libs.versions.tdlightNatives.get()}:$nativesClassifier")
            }
        }
        named("desktopTest") {
            dependsOn(jvmCommonTest)
            dependencies {
                implementation(libs.junit)
            }
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspDesktop", libs.androidx.room.compiler)
}
