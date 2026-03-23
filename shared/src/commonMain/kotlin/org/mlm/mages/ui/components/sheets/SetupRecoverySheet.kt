package org.mlm.mages.ui.components.sheets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.change_recovery_key_instructions
import mages.shared.generated.resources.change_recovery_key_title
import mages.shared.generated.resources.copy
import mages.shared.generated.resources.copied
import mages.shared.generated.resources.generate_new_recovery_key
import mages.shared.generated.resources.generate_recovery_key
import mages.shared.generated.resources.i_have_saved_my_key
import mages.shared.generated.resources.recovery_key_created
import mages.shared.generated.resources.recovery_key_instructions
import mages.shared.generated.resources.save_recovery_key_warning
import mages.shared.generated.resources.setup_recovery_title
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.viewmodel.SecurityViewModel

@Composable
fun SetupRecoverySheet(
    viewModel: SecurityViewModel,
    isChange: Boolean,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val generatedKey = state.generatedRecoveryKey
    val isWorking = state.isEnablingRecovery
    val progress = state.recoveryProgress
    val clipboardManager = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Icon(
            Icons.Default.Key,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )

        Text(
            text = if (generatedKey != null) {
                stringResource(Res.string.recovery_key_created)
            } else if (isChange) {
                stringResource(Res.string.change_recovery_key_title)
            } else {
                stringResource(Res.string.setup_recovery_title)
            },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        AnimatedContent(
            targetState = Triple(generatedKey, isWorking, progress),
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(150))
            }
        ) { (key, working, prog) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                modifier = Modifier.fillMaxWidth()
            ) {
                when {
                    key != null -> {
                        Text(
                            stringResource(Res.string.save_recovery_key_warning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        RecoveryKeyDisplay(key = key)

                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(key))
                                copied = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(
                                if (copied) stringResource(Res.string.copied) else stringResource(Res.string.copy)
                            )
                        }

                        Spacer(Modifier.height(Spacing.sm))

                        Button(
                            onClick = onDone,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(Res.string.i_have_saved_my_key))
                        }
                    }

                    working -> {
                        // Working
                        Text(
                            if (isChange) stringResource(Res.string.change_recovery_key_instructions)
                            else stringResource(Res.string.recovery_key_instructions),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(Spacing.md))

                        LinearWavyProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                        )

                        if (prog != null) {
                            Text(
                                prog,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    else -> {
                        Text(
                            if (isChange) stringResource(Res.string.change_recovery_key_instructions)
                            else stringResource(Res.string.recovery_key_instructions),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(Modifier.height(Spacing.sm))

                        Button(
                            onClick = { viewModel.setupRecovery() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (isChange) stringResource(Res.string.generate_new_recovery_key)
                                else stringResource(Res.string.generate_recovery_key)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecoveryKeyDisplay(key: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        SelectionContainer {
            Text(
                text = key.chunked(4).joinToString(" "),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(Spacing.lg),
                letterSpacing = 0.5.sp
            )
        }
    }
}