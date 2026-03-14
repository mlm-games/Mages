import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.Sync
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val elementCallAar by configurations.creating {
    isTransitive = false
}

dependencies {
    elementCallAar(libs.element.call.embedded)
}

val sharedWasmBridgeDir = rootProject.layout.projectDirectory.dir(
    "shared/src/wasmJsMain/resources/wasm"
)
val rustDir = rootProject.layout.projectDirectory.dir("rust")
val webAppGeneratedWasmResources = layout.buildDirectory.dir("generated/wasmJsApp/wasm")
val webAppGeneratedRootResources = layout.buildDirectory.dir("generated/wasmJsApp/root")
val webAppGeneratedElementCallResources = layout.buildDirectory.dir("generated/wasmJsApp/element-call")
@DisableCachingByDefault(because = "Invokes external Rust tooling")
abstract class GenerateRustWasmBindingsTask @Inject constructor(
    private val execOps: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    abstract val rustProjectDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        execOps.exec {
            workingDir = rustProjectDir.get().asFile
            commandLine("cargo", "build", "--target", "wasm32-unknown-unknown", "--release")
        }

        outputDir.get().asFile.mkdirs()

        execOps.exec {
            workingDir = rustProjectDir.get().asFile
            commandLine(
                "wasm-bindgen",
                "target/wasm32-unknown-unknown/release/mages_ffi.wasm",
                "--target", "bundler",
                "--out-dir", outputDir.get().asFile.absolutePath,
                "--out-name", "mages_ffi",
                "--typescript",
            )
        }
    }
}

val generateRustWasmBindings = tasks.register<GenerateRustWasmBindingsTask>("generateRustWasmBindings") {
    rustProjectDir.set(rustDir)
    outputDir.set(layout.buildDirectory.dir("generated/rustWasmBindings"))
}

val syncWasmAppResources = tasks.register<Sync>("syncWasmAppResources") {
    dependsOn(generateRustWasmBindings)
    from(generateRustWasmBindings.flatMap { it.outputDir })
    from(sharedWasmBridgeDir) {
        include(
            "mages_bridge.js",
        )
    }
    into(webAppGeneratedWasmResources)
}

val extractElementCall = tasks.register<Copy>("extractElementCall") {
    from({ elementCallAar.files.map { zipTree(it) } }) {
        include("assets/element-call/**")
        eachFile { path = path.removePrefix("assets/") }
    }
    into(webAppGeneratedElementCallResources)
    includeEmptyDirs = false
}

val syncRootAppResources = tasks.register<Sync>("syncRootAppResources") {
    dependsOn(extractElementCall)
    from(webAppGeneratedElementCallResources)
    into(webAppGeneratedRootResources)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "mages"
        browser {
            commonWebpackConfig {
                outputFileName = "mages.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.kmp.settings.core)
            implementation(libs.androidx.datastore.preferences.core)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

tasks.named<ProcessResources>("wasmJsProcessResources") {
    dependsOn(syncWasmAppResources)
    dependsOn(syncRootAppResources)
    from(webAppGeneratedWasmResources) {
        into("wasm")
    }
    from(webAppGeneratedRootResources)
}
