import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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

            modules("java.instrument", "jdk.security.auth", "jdk.unsupported", "jdk.httpserver")

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