package org.mlm.mages.ui.components.sheets

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReportContentDialog(
    onReport: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var reason by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    val categories = listOf(
        "spam" to stringResource(Res.string.spam),
        "abuse" to stringResource(Res.string.abuse),
        "illegal" to stringResource(Res.string.illegal_content),
        "other" to stringResource(Res.string.other)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(
                            painterResource(Res.drawable.ic_warning),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Column {
                    Text(
                        text = stringResource(Res.string.report_room),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            Text(
                text = stringResource(Res.string.report_this_room_to_the_server_administrators),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(Spacing.lg))

            Text(
                text = stringResource(Res.string.category),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(Spacing.sm))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                categories.forEach { (value, label) ->
                    val selected = selectedCategory == value
                    FilterChip(
                        selected = selected,
                        onClick = { selectedCategory = value },
                        label = { Text(label) },
                        leadingIcon = if (selected) {
                            {
                                Icon(
                                    painterResource(Res.drawable.ic_check),
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize)
                                )
                            }
                        } else null
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(Res.string.additional_details_optional)) },
                minLines = 3,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            )

            Spacer(Modifier.height(Spacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)
            ) {
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.cancel))
                }

                Button(
                    onClick = {
                        val fullReason = buildString {
                            selectedCategory?.let { append("[$it] ") }
                            append(reason)
                        }
                        onReport(fullReason)
                    },
                    enabled = selectedCategory != null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(Res.string.report))
                }
            }
        }
    }
}