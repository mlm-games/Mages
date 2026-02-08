package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.SearchHit
import org.mlm.mages.ui.theme.Spacing
import org.mlm.mages.ui.util.formatTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomSearchSheet(
    query: String,
    isSearching: Boolean,
    results: List<SearchHit>,
    hasSearched: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onResultClick: (SearchHit) -> Unit,
    onLoadMore: () -> Unit,
    hasMore: Boolean,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.9f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xxl)
        ) {
            // Search field
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .focusRequester(focusRequester),
                placeholder = { Text("Search in this room...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Default.Clear, "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { onSearch() }
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                )
            )

            Spacer(Modifier.height(Spacing.md))

            when {
                isSearching && results.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }

                hasSearched && results.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.SearchOff,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(Spacing.md))
                            Text(
                                "No results found",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                results.isNotEmpty() -> {
                    Text(
                        text = "${results.size} results",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.lg)
                    )

                    Spacer(Modifier.height(Spacing.sm))

                    LazyColumn(
                        modifier = Modifier.weight(1f)
                    ) {
                        items(results, key = { it.eventId }) { hit ->
                            ListItem(
                                headlineContent = {
                                    Text(
                                        text = hit.body,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                overlineContent = {
                                    Text(
                                        text = hit.sender.substringAfter('@').substringBefore(':'),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                },
                                supportingContent = if (hit.timestampMs > 0U) {
                                    { Text(formatTime(hit.timestampMs.toLong())) }
                                } else null,
                                modifier = Modifier.clickable { onResultClick(hit) }
                            )
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = Spacing.lg),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }

                        if (hasMore) {
                            item {
                                TextButton(
                                    onClick = onLoadMore,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(Spacing.md),
                                    enabled = !isSearching
                                ) {
                                    if (isSearching) {
                                        LoadingIndicator(
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(Spacing.sm))
                                    }
                                    Text("Load more")
                                }
                            }
                        }
                    }
                }

                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Enter at least 2 characters to search",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}