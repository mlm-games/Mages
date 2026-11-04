package org.mlm.mages.ui.components.composer

private const val MAX_PLAIN_PAYLOAD_BYTES = 60 * 1024
private const val MAX_ENCRYPTED_PAYLOAD_BYTES = 46 * 1024

internal fun isMessageTooLong(body: String, formattedBody: String?, encrypted: Boolean): Boolean {
    val limit = if (encrypted) MAX_ENCRYPTED_PAYLOAD_BYTES else MAX_PLAIN_PAYLOAD_BYTES
    val bytes = body.encodeToByteArray().size + (formattedBody?.encodeToByteArray()?.size ?: 0)
    return bytes > limit
}
