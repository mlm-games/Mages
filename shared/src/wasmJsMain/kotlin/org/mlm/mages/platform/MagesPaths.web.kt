package org.mlm.mages.platform

actual object MagesPaths {
    actual fun init() {
        // No filesystem initialisation needed on web; storage is handled by IndexedDB via matrix-sdk.
    }

    actual fun storeDir(): String {
        // Not used on web; matrix-sdk-indexeddb uses its own store.
        return "indexeddb://mages"
    }

    actual fun cacheDir(): String {
        return "indexeddb://mages-cache"
    }
}

actual val voiceMessageMimeType: String = "audio/ogg"

actual val voiceMessageExtension: String = "ogg"
