package org.mlm.mages.platform

private fun callWasmSetLogConfig(level: String, packs: String) {
    js("if (globalThis.__magesSetLogConfig) { globalThis.__magesSetLogConfig(level, packs); }")
}

actual fun applyTracingConfiguration(logLevel: String, packs: Set<String>) {
    callWasmSetLogConfig(logLevel, packs.sorted().joinToString(","))
}
