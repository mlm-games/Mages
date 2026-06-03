package org.mlm.mages.push

import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.Promise

@JsFun(
    """(url) => fetch(url, { method: 'GET' })
        .then(r => r.ok ? r.text() : null)
        .catch(() => null)"""
)
private external fun httpGetStringJs(url: String): Promise<JsAny?>

actual suspend fun httpGetString(url: String): String? =
    httpGetStringJs(url).await()?.toString()
