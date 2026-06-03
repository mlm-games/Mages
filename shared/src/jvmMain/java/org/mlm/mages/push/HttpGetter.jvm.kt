package org.mlm.mages.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

actual suspend fun httpGetString(url: String): String? = withContext(Dispatchers.IO) {
    try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        if (connection.responseCode == 200) {
            connection.inputStream.bufferedReader().readText()
        } else null
    } catch (_: Exception) {
        null
    }
}
