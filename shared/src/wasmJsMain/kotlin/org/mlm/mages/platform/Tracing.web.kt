package org.mlm.mages.platform

import org.mlm.mages.matrix.setLogConfig

private var wasmReady = false
private var pendingConfig: Pair<String, String>? = null

internal fun markWasmReady() {
    wasmReady = true
    pendingConfig?.let { setLogConfig(it.first, it.second) }
    pendingConfig = null
}

actual fun applyTracingConfiguration(logLevel: String, packs: Set<String>) {
    val config = logLevel to packs.sorted().joinToString(",")
    if (wasmReady) {
        setLogConfig(config.first, config.second)
    } else {
        pendingConfig = config
    }
}
