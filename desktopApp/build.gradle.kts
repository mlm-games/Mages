import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.internal.os.OperatingSystem

plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.systemtray)
    implementation(libs.net.jna)
    implementation(libs.androidx.datastore.preferences.core)
    implementation(libs.kmp.settings.core)
}

compose.desktop {
    application {
        run {
            val sharedLibDirProvider = project(":shared").layout.buildDirectory.dir("nativeLibs")
            dependsOn(":shared:cargoBuildDesktop")
            jvmArgs("-Djna.library.path=${sharedLibDirProvider.get().asFile.absolutePath}")
        }
        mainClass = "org.mlm.mages.DesktopMainKt"

        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Mages"
            packageVersion = "9.9.9"  //android.defaultConfig.versionName
            description = "Mages Matrix Client"
            vendor = "MLM Games"
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            modules("java.instrument", "jdk.security.auth", "jdk.unsupported")

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