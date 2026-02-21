import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.set(listOf(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi"
        ))
    }

    androidLibrary {
        namespace = "org.mlm.mages.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        androidResources { enable = true }
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui.tooling.preview)

            implementation(libs.navigation3.ui)
            implementation(libs.lifecycle.viewmodel.navigation3)
            implementation(libs.adaptive.navigation3)
            implementation(libs.compose.material.icons.extended)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.androidx.paging.compose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.uri.kmp)
            implementation(libs.multiplatform.markdown.renderer)
            implementation(libs.multiplatform.markdown.renderer.m3)
            implementation(libs.kmp.settings.ui.compose)
            implementation(libs.kmp.settings.core)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.coil.compose)
            implementation(libs.koin.compose.navigation3)
        }

        androidMain.dependencies {
            implementation(project.dependencies.platform(libs.androidx.compose.bom))

            implementation(libs.androidx.activity.compose)
            //noinspection UseTomlInstead
            implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
            implementation(libs.okio)
            implementation(libs.connector)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.work.runtime.ktx)
            implementation(libs.androidx.browser)
            implementation(libs.koin.android)
            implementation(libs.androidx.webkit)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.net.jna)
            implementation(libs.okio)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.dbus.java.core)
            implementation(libs.dbus.java.transport.native.unixsocket)
            implementation(libs.slf4j.simple)
            implementation(libs.systemtray)
            implementation(libs.jcefmaven)
            implementation(libs.json)
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", libs.kmp.settings.ksp)
    add("kspAndroid", libs.kmp.settings.ksp)
    add("kspJvm", libs.kmp.settings.ksp)
}

val cargoAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
val rustDirDefault = rootProject.layout.projectDirectory.dir("rust")
val os = OperatingSystem.current()!!
val hostLibName = when {
    os.isMacOsX -> "libmages_ffi.dylib"
    os.isWindows -> "mages_ffi.dll"
    else -> "libmages_ffi.so"
}
val hostLibFile = rustDirDefault.file("target/release/$hostLibName")

val useCargoFallback = providers.provider { true }
val cargoBinDefault = providers.provider { if (os.isWindows) "cargo.exe" else "cargo" }
val vendoredManifestVar = rustDirDefault.file("uniffi-bindgen/Cargo.toml")
val targetAbiList = providers.gradleProperty("targetAbi").orNull?.let { listOf(it) } ?: cargoAbis

val cargoBuildAndroid = tasks.register<CargoNdkTask>("cargoBuildAndroid") {
    abis.set(targetAbiList)
    cargoBin.set(cargoBinDefault)
    rustDir.set(rustDirDefault)
    jniOut.set(layout.projectDirectory.dir("src/androidMain/jniLibs"))
}

val cargoBuildDesktop = tasks.register<CargoBuildOnlyTask>("cargoBuildDesktop") {
    cargoBin.set(cargoBinDefault)
    rustDir.set(rustDirDefault)
}

val uniffiAndroidOut = layout.buildDirectory.dir("generated/uniffi/androidMain/kotlin")
val uniffiJvmOut = layout.buildDirectory.dir("generated/uniffi/jvmMain/kotlin")

val genUniFFIAndroid = tasks.register<GenerateUniFFITask>("genUniFFIAndroid") {
    dependsOn(cargoBuildDesktop)
    libraryFile.set(hostLibFile)
    configFile.set(rustDirDefault.file("uniffi.android.toml"))
    language.set("kotlin")
    uniffiPath.set("")
    useFallbackCargo.set(useCargoFallback)
    cargoBin.set(cargoBinDefault)
    vendoredManifest.set(vendoredManifestVar)
    outDir.set(uniffiAndroidOut)
}

val genUniFFIJvm = tasks.register<GenerateUniFFITask>("genUniFFIJvm") {
    dependsOn(cargoBuildDesktop)
    libraryFile.set(hostLibFile)
    configFile.set(rustDirDefault.file("uniffi.jvm.toml"))
    language.set("kotlin")
    uniffiPath.set("")
    useFallbackCargo.set(useCargoFallback)
    cargoBin.set(cargoBinDefault)
    vendoredManifest.set(vendoredManifestVar)
    outDir.set(uniffiJvmOut)
}

val jnaPlatformDir: String = run {
    val arch = System.getProperty("os.arch").lowercase()
    when {
        os.isLinux && (arch.contains("aarch64") || arch.contains("arm64")) -> "linux-aarch64"
        os.isLinux -> "linux-x86-64"
        os.isMacOsX && (arch.contains("aarch64") || arch.contains("arm64")) -> "darwin-aarch64"
        os.isMacOsX -> "darwin"
        os.isWindows && arch.contains("64") -> "win32-x86-64"
        os.isWindows -> "win32-x86"
        else -> error("Unsupported OS/arch: ${System.getProperty("os.name")} $arch")
    }
}

val copyNativeForJna = tasks.register<Copy>("copyNativeForJna") {
    dependsOn(cargoBuildDesktop)
    from(rustDirDefault.file("target/release/$hostLibName"))
    into(file("src/jvmMain/resources/$jnaPlatformDir"))
}

tasks.named("jvmProcessResources") {
    dependsOn(copyNativeForJna)
}

@DisableCachingByDefault(because = "Builds native code")
abstract class CargoBuildOnlyTask @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    @get:Input abstract val cargoBin: Property<String>
    @get:InputDirectory abstract val rustDir: DirectoryProperty
    @TaskAction fun run() {
        execOps.exec {
            workingDir = rustDir.get().asFile
            commandLine(cargoBin.get(), "build", "--release")
        }
    }
}

@DisableCachingByDefault(because = "Invokes external tool")
abstract class CargoNdkTask @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val cargoBin: Property<String>
    @get:InputDirectory abstract val rustDir: DirectoryProperty
    @get:OutputDirectory abstract val jniOut: DirectoryProperty
    @TaskAction fun run() {
        val rustDirFile = rustDir.get().asFile
        val outDir = jniOut.get().asFile; if (!outDir.exists()) outDir.mkdirs()
        abis.get().forEach { abi ->
            execOps.exec { workingDir = rustDirFile; commandLine(cargoBin.get(), "ndk", "-t", abi, "-o", outDir.absolutePath, "build", "--release") }
        }
    }
}

@DisableCachingByDefault(because = "Runs external tool")
abstract class GenerateUniFFITask @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    @get:InputFile abstract val libraryFile: RegularFileProperty
    @get:Optional @get:InputFile abstract val configFile: RegularFileProperty
    @get:Input abstract val language: Property<String>
    @get:Input abstract val uniffiPath: Property<String>
    @get:Input abstract val useFallbackCargo: Property<Boolean>
    @get:Input abstract val cargoBin: Property<String>
    @get:Optional @get:InputFile abstract val vendoredManifest: RegularFileProperty
    @get:OutputDirectory abstract val outDir: DirectoryProperty
    @TaskAction fun run() {
        val lib = libraryFile.get().asFile
        val manifest = vendoredManifest.orNull?.asFile ?: throw GradleException("Manifest missing")

        val cmd = mutableListOf(
            cargoBin.get(), "run", "--release",
            "--manifest-path", manifest.absolutePath,
            "--bin", "uniffi-bindgen",
            "--",
            "generate",
            "--library", lib.absolutePath,
            "--language", language.get(),
            "--out-dir", outDir.get().asFile.absolutePath
        )

        configFile.orNull?.let { cfg ->
            cmd += listOf("--config", cfg.asFile.absolutePath)
        }

        outDir.get().asFile.mkdirs()
        execOps.exec { workingDir = manifest.parentFile; commandLine(cmd) }
    }
}

compose.resources {
    publicResClass = true
//    generateResClass = auto
}

kotlin {
    sourceSets {
        named("androidMain") {
            kotlin.srcDir(uniffiAndroidOut)
        }
        named("jvmMain") {
            kotlin.srcDir(uniffiJvmOut)
        }
    }

    androidLibrary {
        compilations.all {
            compileTaskProvider.configure {
                dependsOn(genUniFFIAndroid, cargoBuildAndroid)
            }
        }
    }
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                dependsOn(genUniFFIJvm)
            }
        }
    }
}
tasks.matching { it.name == "mergeAndroidMainJniLibFolders" }.configureEach {
    dependsOn(cargoBuildAndroid)
}

tasks.matching { it.name.contains("JniLibFolders") && it.name.contains("AndroidMain") }.configureEach {
    dependsOn(cargoBuildAndroid)
}

tasks.matching { it.name.startsWith("kspAndroid") }.configureEach {
    dependsOn(genUniFFIAndroid)
}
tasks.matching { it.name.startsWith("kspJvm") || it.name == "kspKotlinJvm" }.configureEach {
    dependsOn(genUniFFIJvm)
}