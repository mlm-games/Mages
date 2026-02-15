import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)
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
        mainClass = "org.mlm.mages.DesktopMainKt"

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