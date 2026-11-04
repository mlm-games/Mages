package org.mlm.mages.ui.components.dialogs

import org.jetbrains.compose.resources.painterResource
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import mages.shared.generated.resources.*
import org.mlm.mages.matrix.SasPhase
import org.mlm.mages.ui.animation.AnimationSpecs
import org.mlm.mages.ui.theme.Sizes
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

@Composable
fun SasDialog(
    phase: SasPhase?,
    emojis: List<String>,
    otherUser: String,
    otherDevice: String,
    error: String?,
    showAcceptRequest: Boolean,
    actionInFlight: Boolean,
    onAccept: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val dismissable =
        phase == SasPhase.Done || phase == SasPhase.Cancelled || phase == SasPhase.Failed
    Dialog(
        onDismissRequest = { if (dismissable) onCancel() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = dismissable,
            dismissOnClickOutside = dismissable
        )
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f).wrapContentHeight(),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    painterResource(Res.drawable.ic_verified_user_filled),
                    null,
                    Modifier.size(Sizes.avatarMedium),
                    MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(Spacing.lg))
                Text(stringResource(Res.string.verify_device), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

                if (otherUser.isNotBlank() || otherDevice.isNotBlank()) {
                    Spacer(Modifier.height(Spacing.sm))
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            "$otherUser • $otherDevice",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 6.dp)
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.xl))

                AnimatedContent(
                    targetState = phase,
                    transitionSpec = { AnimationSpecs.contentTransform() },
                    label = stringResource(Res.string.sasphase)
                ) { currentPhase ->
                    SasPhaseContent(
                        phase = currentPhase,
                        emojis = emojis,
                        showAcceptRequest = showAcceptRequest,
                        actionInFlight = actionInFlight
                    )
                }

                error?.let {
                    Spacer(Modifier.height(Spacing.lg))
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(Spacing.md),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.xl))

                SasActions(
                    phase = phase,
                    showAcceptRequest = showAcceptRequest,
                    actionInFlight = actionInFlight,
                    onAccept = onAccept,
                    onConfirm = onConfirm,
                    onCancel = onCancel
                )
            }
        }
    }
}

@Composable
private fun SasPhaseContent(
    phase: SasPhase?,
    emojis: List<String>,
    showAcceptRequest: Boolean,
    actionInFlight: Boolean
) {
    when (phase) {
        SasPhase.Created -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingIndicator()
                Spacer(Modifier.height(Spacing.sm))
                Text(stringResource(Res.string.preparing_verification))
            }
        }

        SasPhase.Requested -> {
            if (showAcceptRequest && !actionInFlight) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(Res.string.verification_request_received), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(Spacing.sm))
                    Text(
                        stringResource(Res.string.accept_to_continue_with_emoji_verification),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LoadingIndicator()
                    Spacer(Modifier.height(Spacing.sm))
                    Text(stringResource(Res.string.waiting_for_acceptance))
                }
            }
        }

        SasPhase.Ready, SasPhase.Started, SasPhase.Accepted -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingIndicator()
                Spacer(Modifier.height(Spacing.sm))
                Text(stringResource(Res.string.waiting_for_the_other_device), textAlign = TextAlign.Center)
            }
        }

        SasPhase.Emojis -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(Res.string.compare_these_emojis), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(Spacing.lg))
            EmojiGrid(emojis)
            Spacer(Modifier.height(Spacing.lg))
            Text(
                stringResource(Res.string.do_these_match_on_the_other_device),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SasPhase.Confirmed -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LoadingIndicator()
                Spacer(Modifier.height(Spacing.sm))
                Text(stringResource(Res.string.confirmed_finishing), textAlign = TextAlign.Center)
            }
        }

        SasPhase.Done -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(Sizes.avatarLarge)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(Res.drawable.ic_check_circle),
                        null,
                        Modifier.size(Sizes.iconLarge),
                        MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            Text(
                stringResource(Res.string.verification_complete),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        SasPhase.Failed -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.verification_failed), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    stringResource(Res.string.you_can_cancel_and_try_again),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        SasPhase.Cancelled -> {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.verification_cancelled), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            }
        }

        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularWavyProgressIndicator()
            Spacer(Modifier.height(Spacing.sm))
            Text(stringResource(Res.string.preparing))
        }
    }
}

@Composable
private fun EmojiGrid(emojis: List<String>) {
    emojis.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            row.forEach { emoji ->
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(4.dp)
                ) {
                    Text(emoji, Modifier.padding(Spacing.md), style = MaterialTheme.typography.headlineMedium)
                }
            }
        }
    }
}

@Composable
private fun SasActions(
    phase: SasPhase?,
    showAcceptRequest: Boolean,
    actionInFlight: Boolean,
    onAccept: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        when (phase) {
            SasPhase.Requested -> {
                if (showAcceptRequest) {
                    OutlinedButton(onClick = onCancel, enabled = !actionInFlight, modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.reject))
                    }
                    Button(onClick = onAccept, enabled = !actionInFlight, modifier = Modifier.weight(1f)) {
                        Text(stringResource(Res.string.accept))
                    }
                } else {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.cancel))
                    }
                }
            }

            SasPhase.Ready, SasPhase.Started -> {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.cancel))
                }
            }

            SasPhase.Accepted,
            SasPhase.Created,
            SasPhase.Confirmed -> {
                OutlinedButton(onClick = onCancel, enabled = !actionInFlight, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.cancel))
                }
            }

            SasPhase.Emojis -> {
                OutlinedButton(onClick = onCancel, Modifier.weight(1f)) { Text(stringResource(Res.string.they_dont_match)) }
                Button(onClick = onConfirm, Modifier.weight(1f)) { Text(stringResource(Res.string.they_match)) }
            }

            SasPhase.Done -> {
                Button(onClick = onCancel, Modifier.fillMaxWidth()) { Text(stringResource(Res.string.close)) }
            }

            SasPhase.Failed,
            SasPhase.Cancelled -> {
                OutlinedButton(onClick = onCancel, Modifier.fillMaxWidth()) { Text(stringResource(Res.string.close)) }
            }

            else -> {
                OutlinedButton(onClick = onCancel, Modifier.fillMaxWidth()) { Text(stringResource(Res.string.cancel)) }
            }
        }
    }
}