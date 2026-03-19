@file:OptIn(ExperimentalWasmDsl::class)

import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

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
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "kotlin.js.ExperimentalWasmJsInterop",

        ))
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "org.mlm.mages.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        androidResources { enable = true }
    }

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "mages.js"
            }
        }
        binaries.executable()
        // compileKotlinWeb does NOT depend on genUniFFIWasm
    }

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

            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)
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
            implementation(libs.embedded.fcm.distributor)
            implementation(libs.maplibre.compose)
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

        wasmJsMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.browser)
        }
    }
}

dependencies {
    add("kspCommonMainMetadata", libs.kmp.settings.ksp)
    add("kspAndroid", libs.kmp.settings.ksp)
    add("kspJvm", libs.kmp.settings.ksp)
    add("kspWasmJs", libs.kmp.settings.ksp)
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
val wasmLibFile = rustDirDefault.file("target/wasm32-unknown-unknown/release/mages_ffi.wasm")
val webAppWasmDir = rootProject.layout.projectDirectory.dir("webApp/src/wasm")
val generatedWebWasmResources = layout.buildDirectory.dir("generated/web/wasm")

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

val cargoBuildWasm = tasks.register<CargoBuildWasmTask>("cargoBuildWasm") {
    cargoBin.set(cargoBinDefault)
    rustDir.set(rustDirDefault)
}

val uniffiAndroidOut = layout.buildDirectory.dir("generated/uniffi/androidMain/kotlin")
val uniffiJvmOut = layout.buildDirectory.dir("generated/uniffi/jvmMain/kotlin")
val uniffiWasmOut = layout.buildDirectory.dir("generated/uniffi/wasmJsMain/kotlin")

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

val genUniFFIWasm = tasks.register<GenerateUniFFITask>("genUniFFIWasm") {
    dependsOn(cargoBuildDesktop)
    libraryFile.set(hostLibFile)
    configFile.set(rustDirDefault.file("uniffi.wasm.toml"))
    language.set("kotlin")
    uniffiPath.set("")
    useFallbackCargo.set(useCargoFallback)
    cargoBin.set(cargoBinDefault)
    vendoredManifest.set(vendoredManifestVar)
    outDir.set(uniffiWasmOut)
}

val syncWebWasmAssets = tasks.register<Copy>("syncWebWasmAssets") {
    from(webAppWasmDir)
    into(generatedWebWasmResources)
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

@DisableCachingByDefault(because = "Builds WASM code")
abstract class CargoBuildWasmTask @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    @get:Input abstract val cargoBin: Property<String>
    @get:InputDirectory abstract val rustDir: DirectoryProperty
    @TaskAction fun run() {
        execOps.exec {
            workingDir = rustDir.get().asFile
            commandLine(cargoBin.get(), "build", "--target", "wasm32-unknown-unknown", "--release")
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
        named("wasmJsMain") {
            kotlin.srcDir(layout.buildDirectory.dir("generated/wasmJs/kotlin"))
        }
    }

    android {
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
    wasmJs {
        compilations.all {
            compileTaskProvider.configure {
                dependsOn(generateWasmExterns, cargoBuildWasm)
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
tasks.matching { it.name.startsWith("kspWasmJs") || it.name == "kspKotlinWasmJs" }.configureEach {
    dependsOn(generateWasmExterns)
}

tasks.named<ProcessResources>("wasmJsProcessResources") {
    dependsOn(syncWebWasmAssets)
    from(generatedWebWasmResources) {
        into("wasm")
    }
}


/* WEB tasks */

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

abstract class GenerateWasmExternsTask : DefaultTask() {
    @get:InputFile
    abstract val dtsFile: RegularFileProperty

    @get:OutputFile
    abstract val outputKt: RegularFileProperty

    @TaskAction
    fun generate() {
        val content = dtsFile.get().asFile.readText()

        // Extract only the WasmClient class body
        val classMatch = Regex("""export class WasmClient\s*\{([^}]+(?:\{[^}]*\}[^}]*)*)\}""", RegexOption.DOT_MATCHES_ALL).find(content)
        if (classMatch == null) {
            outputKt.get().asFile.writeText("// Could not find WasmClient class")
            return
        }

        val classBody = classMatch.groupValues[1]

        val sb = StringBuilder()
        sb.appendLine("// AUTO-GENERATED from ${dtsFile.get().asFile.name} — do not edit")
        sb.appendLine("@file:JsModule(\"./wasm/mages_ffi.js\")")
        sb.appendLine()
        sb.appendLine("package org.mlm.mages.matrix")
        sb.appendLine()
        sb.appendLine("import kotlin.js.JsAny")
        sb.appendLine("import kotlin.js.JsName")
        sb.appendLine("import kotlin.js.Promise")
        sb.appendLine()

        val allMethods = extractMethods(classBody)
        val staticMethods = allMethods.filter { it.isStatic }
        val instanceMethods = allMethods.filter { !it.isStatic && it.name !in SKIP_NAMES }

        sb.appendLine("external class WasmClient {")

        // Static methods in companion
        if (staticMethods.isNotEmpty()) {
            sb.appendLine("    companion object {")
            for (m in staticMethods) {
                if (m.name == "createAsync") {
                    sb.appendLine("        fun createAsync(")
                    sb.appendLine("            homeserverUrl: String,")
                    sb.appendLine("            baseStoreDir: String,")
                    sb.appendLine("            accountId: String? = definedExternally")
                    sb.appendLine("        ): Promise<JsAny?>")
                } else {
                    val ktParams = convertParams(m.params)
                    val ktReturn = convertReturnType(m.returnType)
                    sb.appendLine("        fun ${m.name}($ktParams): $ktReturn")
                }
            }
            sb.appendLine("    }")
        }

        sb.appendLine()
        sb.appendLine("    fun free()")

        // Instance methods
        for (m in instanceMethods) {
            if (m.name == "free" || m.name == "createAsync") continue

            val ktName = snakeToCamel(m.name)
            val ktParams = convertParams(m.params)
            val ktReturn = convertReturnType(m.returnType)

            if (m.name != ktName) {
                sb.appendLine("    @JsName(\"${m.name}\")")
            }

            sb.appendLine("    fun $ktName($ktParams): $ktReturn")
        }

        sb.appendLine("}")

        sb.appendLine()
        sb.appendLine("external fun asWasmClient(value: JsAny?): WasmClient")

        outputKt.get().asFile.parentFile?.mkdirs()
        outputKt.get().asFile.writeText(sb.toString())
    }

    companion object {
        data class TsMethod(
            val isStatic: Boolean,
            val name: String,
            val params: String,
            val returnType: String
        )

        private val SKIP_NAMES = setOf("free", "cancel", "pull", "start", "write", "abort", "close")

        fun extractMethods(classBody: String): List<TsMethod> {
            val methods = mutableListOf<TsMethod>()
            val text = classBody.replace("\n", " ").replace(Regex("\\s+"), " ")
            var i = 0
            val methodHead = Regex("""(static\s+)?(\w+)\s*\(""")
            while (i < text.length) {
                val head = methodHead.find(text, i) ?: break
                if (head.range.first < i) { i++; continue }
                val isStatic = head.groupValues[1].isNotBlank()
                val name = head.groupValues[2]
                var depth = 1
                var j = head.range.last + 1
                while (j < text.length && depth > 0) {
                    if (text[j] == '(') depth++
                    if (text[j] == ')') depth--
                    j++
                }
                if (depth != 0) { i = j; continue }
                val params = text.substring(head.range.last + 1, j - 1).trim()
                val afterParens = text.substring(j).trimStart()
                val retMatch = Regex("""^:\s*([^;]+);""").find(afterParens)
                if (retMatch != null) {
                    methods.add(TsMethod(isStatic, name, params, retMatch.groupValues[1].trim()))
                    i = j + retMatch.range.last + 1
                } else {
                    i = j
                }
            }
            return methods
        }

        fun splitBalanced(text: String, delimiter: Char): List<String> {
            val parts = mutableListOf<String>()
            var depth = 0
            var start = 0
            for (i in text.indices) {
                when (text[i]) {
                    '(', '<', '{' -> depth++
                    ')', '>', '}' -> depth--
                    delimiter -> if (depth == 0) {
                        parts.add(text.substring(start, i))
                        start = i + 1
                    }
                }
            }
            parts.add(text.substring(start))
            return parts
        }

        fun snakeToCamel(name: String): String {
            if (!name.contains('_')) return name
            return buildString {
                var capitalizeNext = false
                for (ch in name) {
                    if (ch == '_') {
                        capitalizeNext = true
                    } else {
                        append(if (capitalizeNext) ch.uppercaseChar() else ch)
                        capitalizeNext = false
                    }
                }
            }
        }

        fun convertParamType(ts: String): String {
            val t = ts.trim()
            return when {
                t == "boolean" || t == "bool" -> "Boolean"
                t == "string" -> "String"
                t == "number" -> "Double"
                // Function type: (params) => returnType
                t.startsWith("(") && t.contains("=>") -> {
                    var depth = 0
                    var closeIdx = -1
                    for (i in t.indices) {
                        if (t[i] == '(') depth++
                        if (t[i] == ')') { depth--; if (depth == 0) { closeIdx = i; break } }
                    }
                    if (closeIdx < 0) return "JsAny?"
                    val inner = t.substring(1, closeIdx).trim()
                    val retPart = t.substring(closeIdx + 1).trim().removePrefix("=>").trim()
                    val ktRet = convertReturnType(retPart)
                    if (inner.isBlank()) {
                        "() -> $ktRet"
                    } else {
                        val paramParts = splitBalanced(inner, ',')
                        val ktParams = paramParts.joinToString(", ") { p ->
                            val ci = p.indexOf(':')
                            if (ci < 0) "JsAny?"
                            else convertParamType(p.substring(ci + 1).trim())
                        }
                        "($ktParams) -> $ktRet"
                    }
                }
                // Union types: normalize and resolve
                t.contains("|") -> {
                    val parts = t.split("|").map { it.trim() }.toSet()
                    val nonNull = parts - setOf("null", "undefined")
                    val nullable = parts.contains("null") || parts.contains("undefined")
                    val base = nonNull.singleOrNull()
                    when (base) {
                        "string" -> if (nullable) "String?" else "String"
                        "number" -> if (nullable) "Double?" else "Double"
                        "boolean", "bool" -> if (nullable) "Boolean?" else "Boolean"
                        else -> "JsAny?"
                    }
                }
                t.startsWith("Promise<") -> "Promise<JsAny?>"
                else -> "JsAny?"
            }
        }

        fun convertReturnType(ts: String): String {
            val t = ts.trim()
            return when {
                t == "void" -> "Unit"
                t == "boolean" -> "Boolean"
                t == "string" -> "String"
                t == "number" -> "Double"
                t.startsWith("Promise<") -> "Promise<JsAny?>"
                t.contains("|") -> {
                    val parts = t.split("|").map { it.trim() }.toSet()
                    val nonNull = parts - setOf("null", "undefined")
                    val nullable = parts.contains("null") || parts.contains("undefined")
                    val base = nonNull.singleOrNull()
                    when {
                        base == "string" -> if (nullable) "String?" else "String"
                        base == "number" -> if (nullable) "Double?" else "Double"
                        base == "boolean" -> if (nullable) "Boolean?" else "Boolean"
                        else -> "JsAny?"
                    }
                }
                else -> "JsAny?"
            }
        }

        fun convertParams(ts: String): String {
            if (ts.isBlank()) return ""
            val params = splitBalanced(ts, ',')
            return params.joinToString(", ") { param ->
                val p = param.trim()
                val colonIdx = p.indexOf(':')
                if (colonIdx < 0) return@joinToString "param: JsAny?"
                val rawName = p.substring(0, colonIdx).trim()
                val optional = rawName.endsWith("?")
                val tsName = rawName.removeSuffix("?")
                val ktName = snakeToCamel(tsName)
                val tsType = p.substring(colonIdx + 1).trim()
                val ktType = convertParamType(tsType)
                if (optional) "$ktName: $ktType = definedExternally"
                else "$ktName: $ktType"
            }
        }
    }
}

val generateRustWasmBindings = tasks.register<GenerateRustWasmBindingsTask>("generateRustWasmBindings") {
    rustProjectDir.set(rustDirDefault)
    outputDir.set(layout.buildDirectory.dir("generated/rustWasmBindings"))
}

val generateWasmExterns = tasks.register<GenerateWasmExternsTask>("generateWasmExterns") {
    dtsFile.set(generateRustWasmBindings.flatMap { it.outputDir.file("mages_ffi.d.ts") })
    outputKt.set(layout.buildDirectory.file("generated/wasmJs/kotlin/org/mlm/mages/matrix/WasmClientExterns.generated.kt"))
}