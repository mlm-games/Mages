package org.mlm.mages.platform

actual fun applyTracingConfiguration(logLevel: String, packs: Set<String>) {
    val level = when (logLevel) {
        "Verbose" -> mages.LogLevel.TRACE
        "Debug" -> mages.LogLevel.DEBUG
        "Warn" -> mages.LogLevel.WARN
        "Error", "Assert" -> mages.LogLevel.ERROR
        else -> mages.LogLevel.INFO
    }
    val ffiPacks = packs.mapNotNull { key ->
        runCatching { mages.TraceLogPacks.valueOf(key.uppercase()) }.getOrNull()
    }
    mages.initLogging(mages.TracingConfiguration(level, ffiPacks, listOf("mages_ffi")))
}
