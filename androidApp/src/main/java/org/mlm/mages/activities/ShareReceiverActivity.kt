package org.mlm.mages.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.ui.ForwardableRoom
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.components.snackbar.snackbarHost
import org.mlm.mages.ui.theme.MainTheme
import org.mlm.mages.ui.theme.Spacing
import java.io.File
import java.io.FileOutputStream

class ShareReceiverActivity : ComponentActivity() {

    private val service: MatrixService by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedContent = parseIntent(intent)
        if (sharedContent == null) {
            finish()
            return
        }

        lifecycleScope.launch {
            runCatching { service.initFromDisk() }

            if (!service.isLoggedIn() || service.portOrNull == null) {
                finish()
                return@launch
            }

            setContent {
                MainTheme {
                    val snackbarManager: SnackbarManager = koinInject()
                    ShareReceiverScreen(
                        sharedContent = sharedContent,
                        service = service,
                        onDismiss = { finish() },
                        onSent = { roomName ->
                            snackbarManager.show("Sent to $roomName")
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun parseIntent(intent: Intent): SharedContent? {
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val mimeType = intent.type ?: "text/plain"
                when {
                    mimeType.startsWith("text/") -> {
                        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                        if (text != null) {
                            SharedContent.Text(text = if (subject != null) "$subject\n\n$text" else text)
                        } else null
                    }
                    else -> {
                        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_STREAM)
                        }

                        if (uri != null) {
                            SharedContent.SingleFile(
                                uri = uri,
                                mimeType = mimeType,
                                fileName = getFileName(uri),
                                caption = intent.getStringExtra(Intent.EXTRA_TEXT)
                            )
                        } else null
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val mimeType = intent.type ?: "*/*"
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }

                if (!uris.isNullOrEmpty()) {
                    SharedContent.MultipleFiles(
                        files = uris.map { uri ->
                            SharedFile(
                                uri = uri,
                                mimeType = contentResolver.getType(uri) ?: mimeType,
                                fileName = getFileName(uri)
                            )
                        }
                    )
                } else null
            }
            else -> null
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "shared_file"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }
}

// Shared content types
sealed class SharedContent {
    data class Text(val text: String) : SharedContent()
    data class SingleFile(val uri: Uri, val mimeType: String, val fileName: String, val caption: String?) : SharedContent()
    data class MultipleFiles(val files: List<SharedFile>) : SharedContent()
}

data class SharedFile(val uri: Uri, val mimeType: String, val fileName: String)

@Composable
private fun ShareReceiverScreen(
    sharedContent: SharedContent,
    service: MatrixService,
    onDismiss: () -> Unit,
    onSent: (roomName: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val snackbarManager: SnackbarManager = koinInject()

    var rooms by remember { mutableStateOf<List<ForwardableRoom>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        rooms = withContext(Dispatchers.IO) {
            val port = service.portOrNull ?: return@withContext emptyList()
            try {
                port.listRooms().map { room ->
                    ForwardableRoom(
                        roomId = room.id,
                        name = room.name,
                        avatarUrl = room.avatarUrl,
                        isDm = room.isDm,
                        lastActivity = 0L
                    )
                }.sortedByDescending { it.lastActivity }
            } catch (_: Exception) {
                emptyList()
            }
        }
        isLoading = false
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarManager.showError(it)
            errorMessage = null
        }
    }

    val filteredRooms = remember(rooms, searchQuery) {
        if (searchQuery.isBlank()) rooms else rooms.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share to...") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Cancel") }
                }
            )
        },
        snackbarHost = { snackbarManager.snackbarHost() }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            SharePreview(content = sharedContent, modifier = Modifier.padding(Spacing.md))
            HorizontalDivider()

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                placeholder = { Text("Search rooms...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                isSending -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("Sending...")
                        }
                    }
                }
                filteredRooms.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (searchQuery.isNotBlank()) "No rooms found" else "No rooms available",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(filteredRooms, key = { it.roomId }) { room ->
                            ShareRoomItem(
                                room = room,
                                onClick = {
                                    scope.launch {
                                        isSending = true
                                        val success = sendSharedContent(
                                            context = context,
                                            service = service,
                                            roomId = room.roomId,
                                            content = sharedContent
                                        )
                                        isSending = false
                                        if (success) onSent(room.name) else errorMessage = "Failed to send"
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SharePreview(content: SharedContent, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            val icon = when (content) {
                is SharedContent.Text -> Icons.Default.TextFields
                is SharedContent.SingleFile -> when {
                    content.mimeType.startsWith("image/") -> Icons.Default.Image
                    content.mimeType.startsWith("video/") -> Icons.Default.Videocam
                    content.mimeType.startsWith("audio/") -> Icons.Default.AudioFile
                    else -> Icons.Default.AttachFile
                }
                is SharedContent.MultipleFiles -> Icons.Default.Folder
            }

            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                when (content) {
                    is SharedContent.Text -> {
                        Text("Text message", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(content.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    is SharedContent.SingleFile -> {
                        Text(content.fileName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(content.mimeType, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        content.caption?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    is SharedContent.MultipleFiles -> {
                        Text("${content.files.size} files", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            content.files.take(3).joinToString(", ") { it.fileName },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShareRoomItem(room: ForwardableRoom, onClick: () -> Unit) {
    Surface(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (room.isDm) {
                        Icon(Icons.Default.Person, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    } else {
                        Text(
                            room.name.take(2).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(room.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (room.isDm) "Direct message" else "Room", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private suspend fun sendSharedContent(
    context: Context,
    service: MatrixService,
    roomId: String,
    content: SharedContent
): Boolean = withContext(Dispatchers.IO) {

    val port: MatrixPort = service.portOrNull ?: return@withContext false
    if (!service.isLoggedIn()) return@withContext false

    try {
        when (content) {
            is SharedContent.Text -> {
                port.send(roomId, content.text)
            }
            is SharedContent.SingleFile -> {
                val tempFile = copyUriToTempFile(context, content.uri, content.fileName)
                val success = port.sendAttachmentFromPath(
                    roomId = roomId,
                    path = tempFile.absolutePath,
                    mime = content.mimeType,
                    filename = content.fileName,
                    onProgress = null
                )
                tempFile.delete()

                if (success && !content.caption.isNullOrBlank()) {
                    port.send(roomId, content.caption)
                } else success
            }
            is SharedContent.MultipleFiles -> {
                var allSuccess = true
                for (file in content.files) {
                    val tempFile = copyUriToTempFile(context, file.uri, file.fileName)
                    val success = port.sendAttachmentFromPath(
                        roomId = roomId,
                        path = tempFile.absolutePath,
                        mime = file.mimeType,
                        filename = file.fileName,
                        onProgress = null
                    )
                    tempFile.delete()
                    if (!success) allSuccess = false
                }
                allSuccess
            }
        }
    } catch (_: Exception) {
        false
    }
}

private fun copyUriToTempFile(context: Context, uri: Uri, fileName: String): File {
    val tempDir = File(context.cacheDir, "share_temp")
    tempDir.mkdirs()
    val tempFile = File(tempDir, fileName)

    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
    }

    return tempFile
}