package org.mlm.mages.platform

expect object MagesPaths {
    fun init()
    fun storeDir(): String
    fun cacheDir(): String
}

expect val voiceMessageMimeType: String
expect val voiceMessageExtension: String