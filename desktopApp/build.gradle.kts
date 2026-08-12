import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.javafx)
}

kotlin {
    jvmToolchain(25) // for MapLibre Native FFI (FFM)
}

javafx {
    version = libs.versions.javafx.get()
    modules("javafx.base", "javafx.graphics", "javafx.media", "javafx.swing")
}

val maplibreRuntime: Provider<MinimalExternalModuleDependency>? = run {
    val os = OperatingSystem.current() ?: return@run null
    val arch = System.getProperty("os.arch").lowercase()
    val isArm = arch.contains("aarch64") || arch.contains("arm64")
    when {
        os.isLinux && isArm -> libs.maplibre.runtime.vulkan.linux.arm64
        os.isLinux -> libs.maplibre.runtime.vulkan.linux.x64
        os.isMacOsX && isArm -> libs.maplibre.runtime.metal.macos.arm64
        os.isMacOsX -> null // no upstream macos-x64 runtime
        os.isWindows && isArm -> libs.maplibre.runtime.vulkan.windows.arm64
        os.isWindows -> libs.maplibre.runtime.vulkan.windows.x64
        else -> null
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.systemtray)
    implementation(libs.net.jna)
    implementation(libs.androidx.datastore.preferences.core)
    implementation(libs.kmp.settings.core)
    implementation(libs.maplibre.compose)

    // Host-only native MapLibre runtime (picked at build machine OS/arch)
    if (maplibreRuntime != null) {
        runtimeOnly(maplibreRuntime)
    } else {
        logger.warn("No MapLibre desktop runtime for this OS/arch; maps will fail at runtime.")
    }
}

compose.desktop {
    application {
        mainClass = "org.mlm.mages.DesktopMainKt"

        jvmArgs("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            packageName = "Mages"
            packageVersion = System.getenv("APP_VERSION") ?: "9.9.9"
            description = "Mages Matrix Client"
            vendor = "MLM Games"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            modules("java.instrument", "jdk.security.auth", "jdk.unsupported", "jdk.httpserver")

            windows {
                iconFile.set(project.file("../packaging/icon.ico"))
                menuGroup = "Mages"
                shortcut = true
                dirChooser = true
                perUserInstall = true
            }

            macOS {
                iconFile.set(project.file("../packaging/icon.icns"))
                bundleID = "org.mlm.mages"
                appCategory = "public.app-category.social-networking"
            }

            linux {
                iconFile.set(project.file("../fastlane/metadata/android/en-US/images/icon.png"))
                packageName = "mages"
                debMaintainer = "gfxoxinzh@mozmail.com"
                menuGroup = "Network;InstantMessaging"
                appCategory = "Network"
            }
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// ================ Element Call Embedded Assets
val elementCallAar by configurations.creating {
    isTransitive = false
}

dependencies {
    elementCallAar(libs.element.call.embedded)
}

val elementCallResDir = layout.buildDirectory.dir("generated/element-call/resources")

val extractElementCall by tasks.registering(Copy::class) {
    from({ elementCallAar.files.map { zipTree(it) } }) {
        include("assets/element-call/**")
        eachFile { path = path.removePrefix("assets/") } // -> element-call/...
    }
    into(elementCallResDir)
    includeEmptyDirs = false
}

sourceSets["main"].resources.srcDir(elementCallResDir)

tasks.named("processResources") {
    dependsOn(extractElementCall)
}
// =============== End Element Call
