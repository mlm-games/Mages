package org.mlm.mages.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.auto_download_previews
import mages.shared.generated.resources.back
import mages.shared.generated.resources.media_cache
import mages.shared.generated.resources.media_cache_clear_confirm_body
import mages.shared.generated.resources.media_cache_clear_confirm_title
import mages.shared.generated.resources.media_cache_clear_media_cache
import mages.shared.generated.resources.media_cache_storage_used
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.mlm.mages.ui.components.dialogs.ConfirmationDialog
import org.mlm.mages.ui.util.formatBytes
import org.mlm.mages.ui.viewmodel.MediaCacheUiState
import org.mlm.mages.ui.viewmodel.MediaCacheViewModel

@Composable
fun MediaCacheRoute(onBack: () -> Unit) {
    val viewModel: MediaCacheViewModel = koinViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    MediaCacheScreen(
        state = state,
        onBack = onBack,
        onClearCache = viewModel::clearCache,
        onAutoDownloadChange = viewModel::setAutoDownloadPreviews,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaCacheScreen(
    state: MediaCacheUiState,
    onBack: () -> Unit,
    onClearCache: () -> Unit,
    onAutoDownloadChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        ConfirmationDialog(
            title = stringResource(Res.string.media_cache_clear_confirm_title),
            message = stringResource(Res.string.media_cache_clear_confirm_body),
            onConfirm = {
                showConfirm = false
                onClearCache()
            },
            onDismiss = { showConfirm = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.media_cache)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                }
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {

            ListItem(
                headlineContent = {
                    Text(stringResource(Res.string.media_cache_storage_used))
                },
                trailingContent = {
                    if (state.isLoading) {
                        CircularWavyProgressIndicator()
                    } else {
                        Text(
                            text = formatBytes(state.overview?.totalBytes?.toLong() ?: 0L),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            )

            HorizontalDivider()

//            ListItem(
//                headlineContent = {
//                    Text(stringResource(Res.string.auto_download_previews))
//                },
//                trailingContent = {
//                    Switch(
//                        checked = state.autoDownloadPreviews,
//                        onCheckedChange = onAutoDownloadChange,
//                    )
//                }
//            )

            HorizontalDivider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = { showConfirm = true },
                    enabled = !state.isClearing && !state.isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    if (state.isClearing) {
                        CircularWavyProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            color = MaterialTheme.colorScheme.onError,
                        )
                    }
                    Text(stringResource(Res.string.media_cache_clear_media_cache))
                }

                Spacer(Modifier.padding(6.dp))

                Row(Modifier.fillMaxWidth(), Arrangement.Center) { Text("Room listings will be added later, once the sdk adds supports for caching based on room keys") }

                state.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}