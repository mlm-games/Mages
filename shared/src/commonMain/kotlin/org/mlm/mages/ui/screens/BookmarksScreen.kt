package org.mlm.mages.ui.screens

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.mlm.mages.bookmarks.Bookmark
import org.mlm.mages.bookmarks.BookmarksStore
import org.mlm.mages.ui.components.core.EmptyState
import org.mlm.mages.ui.components.snackbar.SnackbarManager
import org.mlm.mages.ui.components.snackbar.snackbarHost
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatPinnedTimestamp

@Composable
fun BookmarksRoute(
    onBack: () -> Unit,
    onOpenMessage: (roomId: String, eventId: String, roomName: String) -> Unit,
) {
    val store: BookmarksStore = koinInject()
    val bookmarks by store.bookmarks.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarManager: SnackbarManager = koinInject()

    LaunchedEffect(Unit) {
        store.refresh()
    }

    BookmarksScreen(
        bookmarks = bookmarks,
        onBack = onBack,
        onOpenMessage = { bookmark ->
            onOpenMessage(bookmark.roomId, bookmark.eventId, bookmark.roomName)
        },
        onRemove = { bookmark ->
            scope.launch {
                val failureMessage = getString(Res.string.failed_to_update_bookmark)
                store.remove(bookmark).onFailure { error ->
                    snackbarManager.showError(error.message ?: failureMessage)
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksScreen(
    bookmarks: List<Bookmark>,
    onBack: () -> Unit,
    onOpenMessage: (Bookmark) -> Unit,
    onRemove: (Bookmark) -> Unit,
) {
    val snackbarManager: SnackbarManager = koinInject()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.bookmarks), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                }
            )
        },
        snackbarHost = { snackbarManager.snackbarHost() }
    ) { padding ->
        if (bookmarks.isEmpty()) {
            EmptyState(
                icon = painterResource(Res.drawable.ic_bookmark),
                title = stringResource(Res.string.bookmarks_empty_title),
                subtitle = stringResource(Res.string.bookmarks_empty_subtitle),
                modifier = Modifier.padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = Spacing.sm)
            ) {
                items(bookmarks, key = { "${it.roomId}_${it.eventId}" }) { bookmark ->
                    BookmarkItem(
                        bookmark = bookmark,
                        onClick = { onOpenMessage(bookmark) },
                        onRemove = { onRemove(bookmark) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BookmarkItem(
    bookmark: Bookmark,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val sender = bookmark.sender?.takeIf { it.isNotBlank() }
    ListItem(
        headlineContent = {
            Text(
                text = bookmark.preview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        overlineContent = if (sender != null) {
            {
                Text(
                    text = sender.substringAfter('@').substringBefore(':'),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else null,
        supportingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatPinnedTimestamp(bookmark.timestampMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = bookmark.roomName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        leadingContent = {
            Icon(
                painterResource(Res.drawable.ic_bookmark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(
                    painterResource(Res.drawable.ic_delete),
                    contentDescription = stringResource(Res.string.remove_bookmark),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        modifier = Modifier.clickable { onClick() }
    )
}
