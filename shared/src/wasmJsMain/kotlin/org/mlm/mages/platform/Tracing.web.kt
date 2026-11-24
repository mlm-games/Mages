package org.mlm.mages.platform

@JsModule("./wasm/mages_ffi.js")
external fun setLogConfig(level: String, packs: String)

actual fun applyTracingConfiguration(logLevel: String, packs: Set<String>) {
    setLogConfig(logLevel, packs.sorted().joinToString(","))
}
