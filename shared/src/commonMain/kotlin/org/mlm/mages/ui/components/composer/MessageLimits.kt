package org.mlm.mages.ui.components.composer

private const val MAX_PLAIN_MESSAGE_BYTES = 60 * 1024
private const val MAX_ENCRYPTED_MESSAGE_BYTES = 48 * 1024

internal fun isMessageTooLong(text: String, encrypted: Boolean): Boolean {
    val limit = if (encrypted) MAX_ENCRYPTED_MESSAGE_BYTES else MAX_PLAIN_MESSAGE_BYTES
    return text.encodeToByteArray().size > limit
}
