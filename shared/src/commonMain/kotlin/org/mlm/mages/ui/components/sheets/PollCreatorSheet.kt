package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.theme.Spacing

@Composable
fun PollCreatorSheet(
    onCreatePoll: (question: String, answers: List<String>, maxSelections: Int) -> Unit,
    onDismiss: () -> Unit,
    isEditing: Boolean = false,
    initialQuestion: String = "",
    initialAnswers: List<String> = emptyList(),
    initialMaxSelections: Int = 1,
) {
    var question by remember(isEditing, initialQuestion) { mutableStateOf(initialQuestion) }
    var answers by remember(isEditing, initialAnswers) {
        mutableStateOf(if (initialAnswers.size >= 2) initialAnswers else listOf("", ""))
    }
    var allowMultipleAnswers by remember(isEditing, initialMaxSelections) {
        mutableStateOf(initialMaxSelections > 1)
    }

    val isValid = question.isNotBlank() && answers.count { it.isNotBlank() } >= 2

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg)
                .padding(bottom = Spacing.xxl)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (isEditing) "Edit Poll" else "Create Poll",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close")
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            // Question
            OutlinedTextField(
                value = question,
                onValueChange = { question = it },
                label = { Text("Question") },
                placeholder = { Text("Ask something...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 3,
                leadingIcon = { Icon(Icons.Default.Poll, null) }
            )

            Spacer(Modifier.height(Spacing.lg))

            Text(
                "Options (minimum 2)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(Spacing.sm))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.weight(1f, fill = false).heightIn(max = 250.dp)
            ) {
                itemsIndexed(answers) { index, answer ->
                    OutlinedTextField(
                        value = answer,
                        onValueChange = { newValue ->
                            answers = answers.toMutableList().apply { set(index, newValue) }
                        },
                        label = { Text("Option ${index + 1}") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        trailingIcon = {
                            if (answers.size > 2) {
                                IconButton(onClick = {
                                    answers = answers.toMutableList().apply { removeAt(index) }
                                }) {
                                    Icon(Icons.Default.Close, "Remove option")
                                }
                            }
                        }
                    )
                }

                if (answers.size < 10) {
                    item {
                        TextButton(
                            onClick = { answers = answers + "" },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(Spacing.sm))
                            Text("Add option")
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Allow multiple answers",
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = allowMultipleAnswers,
                    onCheckedChange = { allowMultipleAnswers = it }
                )
            }

            Spacer(Modifier.height(Spacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(Spacing.sm))
                Button(
                    onClick = {
                        val validAnswers = answers.filter { it.isNotBlank() }
                        if (question.isNotBlank() && validAnswers.size >= 2) {
                            val maxSelections = if (allowMultipleAnswers) validAnswers.size else 1
                            onCreatePoll(question.trim(), validAnswers.map { it.trim() }, maxSelections)
                            onDismiss()
                        }
                    },
                    enabled = isValid
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(if (isEditing) "Save Poll" else "Create Poll")
                }
            }
        }
    }
}