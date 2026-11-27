package org.mlm.mages.platform

import org.mlm.mages.matrix.wasmSetLogConfig

private var wasmReady = false
private var pendingConfig: Pair<String, String>? = null

internal fun markWasmReady() {
    wasmReady = true
    pendingConfig?.let { wasmSetLogConfig(it.first, it.second) }
    pendingConfig = null
}

actual fun applyTracingConfiguration(logLevel: String, packs: Set<String>) {
    val config = logLevel to packs.sorted().joinToString(",")
    if (wasmReady) {
        wasmSetLogConfig(config.first, config.second)
    } else {
        pendingConfig = config
    }
}
