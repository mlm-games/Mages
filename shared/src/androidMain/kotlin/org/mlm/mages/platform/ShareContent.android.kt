package org.mlm.mages.platform

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

@Composable
actual fun rememberShareHandler(): (ShareContent) -> Unit {
    val context = LocalContext.current

    return remember {
        { content ->
            try {
                val files = content.allFilePaths
                    .map { File(it) }
                    .filter { it.exists() && it.canRead() }

                // Text-only
                if (files.isEmpty() && content.text != null) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        content.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                        putExtra(Intent.EXTRA_TEXT, content.text)
                    }
                    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return@remember
                }

                // Nothing to share
                if (files.isEmpty()) return@remember

                val uris: List<Uri> = files.map { file ->
                    FileProvider.getUriForFile(
                        context,
                        context.packageName + ".provider",
                        file
                    )
                }

                val mime = content.effectiveMimeType

                val intent = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        content.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                        content.text?.let { putExtra(Intent.EXTRA_TEXT, it) }
                        putExtra(Intent.EXTRA_STREAM, uris.first())
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = mime
                        content.subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
                        content.text?.let { putExtra(Intent.EXTRA_TEXT, it) }
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                        // Some targets behave better if ClipData is set for multiple Uris
                        clipData = ClipData.newUri(context.contentResolver, "files", uris.first()).apply {
                            for (i in 1 until uris.size) addItem(ClipData.Item(uris[i]))
                        }
                    }
                }

                val chooser = Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }
}