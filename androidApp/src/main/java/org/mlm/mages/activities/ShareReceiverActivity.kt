package org.mlm.mages.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.mlm.mages.MatrixService
import org.mlm.mages.matrix.MatrixPort
import org.mlm.mages.ui.ForwardableRoom
import org.mlm.mages.ui.theme.MainTheme
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.components.sheets.RoomSelectionList
import org.mlm.mages.ui.components.sheets.SelectedRoomsRow
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

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
                    ShareReceiverScreen(
                        sharedContent = sharedContent,
                        service = service,
                        onDismiss = { finish() },
                        onCompleted = { summary ->
                            Toast.makeText(
                                applicationContext,
                                summary.toastMessage(),
                                if (summary.failureCount > 0) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                            ).show()

                            if (summary.successCount > 0) {
                                finish()
                            }
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
                            SharedContent.Text(
                                text = if (subject != null) "$subject\n\n$text" else text
                            )
                        } else {
                            null
                        }
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
                        } else {
                            null
                        }
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
                } else {
                    null
                }
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

sealed class SharedContent {
    data class Text(val text: String) : SharedContent()
    data class SingleFile(
        val uri: Uri,
        val mimeType: String,
        val fileName: String,
        val caption: String?
    ) : SharedContent()

    data class MultipleFiles(val files: List<SharedFile>) : SharedContent()
}

data class SharedFile(
    val uri: Uri,
    val mimeType: String,
    val fileName: String
)

private sealed class PreparedSharedContent {
    data class Text(val text: String) : PreparedSharedContent()

    data class SingleFile(
        val file: File,
        val mimeType: String,
        val fileName: String,
        val caption: String?
    ) : PreparedSharedContent()

    data class MultipleFiles(
        val files: List<PreparedSharedFile>
    ) : PreparedSharedContent()
}

private data class PreparedSharedFile(
    val file: File,
    val mimeType: String,
    val fileName: String
)

private data class ShareBatchProgress(
    val currentRoomIndex: Int,
    val totalRooms: Int,
    val currentRoomName: String
)

private data class ShareRoomResult(
    val roomId: String,
    val roomName: String,
    val success: Boolean
)

private data class ShareBatchSummary(
    val results: List<ShareRoomResult>
) {
    val totalCount: Int get() = results.size
    val successCount: Int get() = results.count { it.success }
    val failureCount: Int get() = totalCount - successCount

    fun toastMessage(): String {
        return when {
            totalCount == 0 -> "Nothing was sent"
            successCount == totalCount && totalCount == 1 ->
                "Sent to ${results.first().roomName}"

            successCount == totalCount ->
                "Sent to $successCount rooms"

            successCount > 0 ->
                "Sent to $successCount/$totalCount rooms"

            else ->
                "Failed to send"
        }
    }
}

@Composable
private fun ShareReceiverScreen(
    sharedContent: SharedContent,
    service: MatrixService,
    onDismiss: () -> Unit,
    onCompleted: (ShareBatchSummary) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext

    var rooms by remember { mutableStateOf<List<ForwardableRoom>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedRoomIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var progress by remember { mutableStateOf<ShareBatchProgress?>(null) }
    var transientMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        rooms = withContext(Dispatchers.IO) {
            val port = service.portOrNull ?: return@withContext emptyList()
            try {
                port.listRooms()
                    .map { room ->
                        ForwardableRoom(
                            roomId = room.id,
                            name = room.name,
                            avatarUrl = room.avatarUrl,
                            isDm = room.isDm,
                            lastActivity = 0L
                        )
                    }
                    .sortedBy { it.name.lowercase() }
            } catch (_: Exception) {
                emptyList()
            }
        }
        isLoading = false
    }

    LaunchedEffect(transientMessage) {
        transientMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            transientMessage = null
        }
    }

    val filteredRooms = remember(rooms, searchQuery) {
        if (searchQuery.isBlank()) {
            rooms
        } else {
            rooms.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
    }

    val selectedRooms = remember(rooms, selectedRoomIds) {
        rooms.filter { it.roomId in selectedRoomIds }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share to...") },
                navigationIcon = {
                    IconButton(
                        onClick = onDismiss,
                        enabled = !isSending
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                }
            )
        },
        bottomBar = {
            ShareActionBar(
                selectedCount = selectedRooms.size,
                isSending = isSending,
                progress = progress,
                onSend = {
                    if (selectedRooms.isEmpty() || isSending) return@ShareActionBar

                    scope.launch {
                        isSending = true

                        val prepared = try {
                            prepareSharedContent(context, sharedContent)
                        } catch (_: Exception) {
                            null
                        }

                        if (prepared == null) {
                            isSending = false
                            transientMessage = "Could not read shared content"
                            return@launch
                        }

                        try {
                            val results = mutableListOf<ShareRoomResult>()

                            selectedRooms.forEachIndexed { index, room ->
                                progress = ShareBatchProgress(
                                    currentRoomIndex = index + 1,
                                    totalRooms = selectedRooms.size,
                                    currentRoomName = room.name
                                )

                                val success = sendPreparedSharedContent(
                                    service = service,
                                    roomId = room.roomId,
                                    content = prepared
                                )

                                results += ShareRoomResult(
                                    roomId = room.roomId,
                                    roomName = room.name,
                                    success = success
                                )
                            }

                            onCompleted(ShareBatchSummary(results))
                        } finally {
                            progress = null
                            isSending = false
                            prepared.cleanup()
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SharePreview(
                content = sharedContent,
                modifier = Modifier.padding(Spacing.md)
            )

            HorizontalDivider()

            if (selectedRooms.isNotEmpty()) {
                SelectedRoomsRow(
                    rooms = selectedRooms,
                    enabled = !isSending,
                    onRemove = { roomId ->
                        selectedRoomIds = selectedRoomIds - roomId
                    }
                )
            }

            RoomSelectionList(
                rooms = filteredRooms,
                selectedRoomIds = selectedRoomIds,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                onRoomToggle = { roomId ->
                    selectedRoomIds = if (roomId in selectedRoomIds) {
                        selectedRoomIds - roomId
                    } else {
                        selectedRoomIds + roomId
                    }
                },
                isLoading = isLoading,
                enabled = !isSending,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ShareActionBar(
    selectedCount: Int,
    isSending: Boolean,
    progress: ShareBatchProgress?,
    onSend: () -> Unit
) {
    Surface(shadowElevation = 8.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            progress?.let {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Sending to ${it.currentRoomName} (${it.currentRoomIndex}/${it.totalRooms})",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearWavyProgressIndicator(
                        progress = { it.currentRoomIndex.toFloat() / it.totalRooms.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Button(
                onClick = onSend,
                enabled = selectedCount > 0 && !isSending,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isSending) {
                    CircularWavyProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Sending…")
                } else {
                    Text(
                        when (selectedCount) {
                            0 -> "Select rooms"
                            1 -> "Send to 1 room"
                            else -> "Send to $selectedCount rooms"
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedRoomsRow(
    rooms: List<ForwardableRoom>,
    enabled: Boolean,
    onRemove: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.xs)
    ) {
        Text(
            text = "Selected rooms",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(rooms, key = { it.roomId }) { room ->
                AssistChip(
                    onClick = { if (enabled) onRemove(room.roomId) },
                    label = {
                        Text(
                            text = room.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingIcon = {
                        Icon(Icons.Default.Close, contentDescription = "Remove")
                    }
                )
            }
        }
    }
}

@Composable
private fun SharePreview(
    content: SharedContent,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
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

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )

            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                when (content) {
                    is SharedContent.Text -> {
                        Text(
                            text = "Text message",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = content.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    is SharedContent.SingleFile -> {
                        Text(
                            text = content.fileName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = content.mimeType,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        content.caption?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    is SharedContent.MultipleFiles -> {
                        Text(
                            text = "${content.files.size} files",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = content.files.take(3).joinToString(", ") { it.fileName },
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

private suspend fun prepareSharedContent(
    context: Context,
    content: SharedContent
): PreparedSharedContent = withContext(Dispatchers.IO) {
    when (content) {
        is SharedContent.Text -> {
            PreparedSharedContent.Text(content.text)
        }

        is SharedContent.SingleFile -> {
            val tempFile = copyUriToTempFile(context, content.uri, content.fileName)
            PreparedSharedContent.SingleFile(
                file = tempFile,
                mimeType = content.mimeType,
                fileName = content.fileName,
                caption = content.caption
            )
        }

        is SharedContent.MultipleFiles -> {
            PreparedSharedContent.MultipleFiles(
                files = content.files.map { file ->
                    PreparedSharedFile(
                        file = copyUriToTempFile(context, file.uri, file.fileName),
                        mimeType = file.mimeType,
                        fileName = file.fileName
                    )
                }
            )
        }
    }
}

private fun PreparedSharedContent.cleanup() {
    when (this) {
        is PreparedSharedContent.Text -> Unit
        is PreparedSharedContent.SingleFile -> file.delete()
        is PreparedSharedContent.MultipleFiles -> files.forEach { it.file.delete() }
    }
}

private suspend fun sendPreparedSharedContent(
    service: MatrixService,
    roomId: String,
    content: PreparedSharedContent
): Boolean = withContext(Dispatchers.IO) {
    val port: MatrixPort = service.portOrNull ?: return@withContext false
    if (!service.isLoggedIn()) return@withContext false

    try {
        when (content) {
            is PreparedSharedContent.Text -> {
                port.send(roomId, content.text).isSuccess
            }

            is PreparedSharedContent.SingleFile -> {
                val success = port.sendAttachmentFromPath(
                    roomId = roomId,
                    path = content.file.absolutePath,
                    mime = content.mimeType,
                    filename = content.fileName,
                    onProgress = null
                )

                if (success && !content.caption.isNullOrBlank()) {
                    port.send(roomId, content.caption).isSuccess
                } else {
                    success
                }
            }

            is PreparedSharedContent.MultipleFiles -> {
                var allSuccess = true

                for (file in content.files) {
                    val success = port.sendAttachmentFromPath(
                        roomId = roomId,
                        path = file.file.absolutePath,
                        mime = file.mimeType,
                        filename = file.fileName,
                        onProgress = null
                    )
                    if (!success) allSuccess = false
                }

                allSuccess
            }
        }
    } catch (_: Exception) {
        false
    }
}

private fun copyUriToTempFile(
    context: Context,
    uri: Uri,
    fileName: String
): File {
    val tempDir = File(context.cacheDir, "share_temp").apply { mkdirs() }
    val safeName = "${UUID.randomUUID()}_$fileName"
    val tempFile = File(tempDir, safeName)

    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tempFile).use { output ->
            input.copyTo(output)
        }
    } ?: error("Could not open shared content")

    return tempFile
}