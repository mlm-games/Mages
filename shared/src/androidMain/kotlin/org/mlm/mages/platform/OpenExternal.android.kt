package org.mlm.mages.platform

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import co.touchlab.kermit.Logger
import java.io.File

@Composable
actual fun rememberFileOpener(): (String, String?) -> Boolean {
    val context = LocalContext.current
    return { path, mime ->
        val file = File(path)
        val uri: Uri = try {
            FileProvider.getUriForFile(
                context,
                context.packageName + ".provider",
                file
            )
        } catch (e: Throwable) {
            Logger.d { "OpenExternal: FileProvider failed, file:// fallback: ${e.message}" }
            Uri.fromFile(file) // many won’t accept file://
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(intent) }.isSuccess
    }
}