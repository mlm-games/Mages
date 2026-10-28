package org.mlm.mages.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.launch
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.add
import mages.shared.generated.resources.back
import mages.shared.generated.resources.no_quick_reactions
import mages.shared.generated.resources.remove
import mages.shared.generated.resources.reset
import mages.shared.generated.resources.setting_quick_reactions
import mages.shared.generated.resources.setting_quick_reactions_description
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.encodeQuickReactions
import org.mlm.mages.settings.parseQuickReactions
import org.mlm.mages.ui.components.sheets.EmojiPickerSheet
import org.mlm.mages.ui.theme.Spacing

@Composable
fun QuickReactionsRoute(onBack: () -> Unit) {
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val settings by settingsRepository.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    QuickReactionsScreen(
        reactions = parseQuickReactions(settings.quickReactions),
        onChange = { reactions ->
            val encoded = encodeQuickReactions(reactions)
            scope.launch { settingsRepository.update { it.copy(quickReactions = encoded) } }
        },
        onReset = {
            scope.launch { settingsRepository.update { it.copy(quickReactions = "") } }
        },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickReactionsScreen(
    reactions: List<String>,
    onChange: (List<String>) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    var showEmojiPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(Res.string.setting_quick_reactions),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onReset) {
                        Text(stringResource(Res.string.reset))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                stringResource(Res.string.setting_quick_reactions_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (reactions.isEmpty()) {
                Text(
                    stringResource(Res.string.no_quick_reactions),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    reactions.forEach { emoji ->
                        InputChip(
                            selected = false,
                            onClick = { onChange(reactions - emoji) },
                            label = { Text(emoji, fontSize = 20.sp) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(Res.string.remove),
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onChange(reactions - emoji) }
                                )
                            },
                            colors = InputChipDefaults.inputChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            border = null,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            OutlinedButton(
                onClick = { showEmojiPicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Spacing.sm))
                Text(stringResource(Res.string.add))
            }
        }
    }

    if (showEmojiPicker) {
        EmojiPickerSheet(
            onDismiss = { showEmojiPicker = false },
            onEmojiSelected = { emoji ->
                showEmojiPicker = false
                if (emoji !in reactions) onChange(reactions + emoji)
            }
        )
    }
}
