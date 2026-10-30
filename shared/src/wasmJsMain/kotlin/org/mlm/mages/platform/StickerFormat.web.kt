package org.mlm.mages.platform

internal actual suspend fun readTgsBytes(source: String): ByteArray? {
    retrieveWebBlob(source)?.let { bytes ->
        return bytes.takeIf { it.size <= MaxTgsCompressedBytes }
    }
    return decodeBase64DataUri(source)
}
