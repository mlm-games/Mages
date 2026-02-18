package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mlm.mages.ui.theme.Spacing

@Composable
fun EmojiPickerSheet(
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(emojiCategories.first()) }

    // Flat search results across all categories
    val searchResults = remember(query) {
        if (query.isBlank()) emptyList()
        else emojiCategories.flatMap { it.emojis }.filter { emoji ->
            // Simple substring match on the unicode codepoints — good enough for emoji search
            // since users will typically type partial emoji names; we match against the raw
            // characters directly (useful for copy-paste searching). Category names not relevant here.
            emoji.contains(query)
        }
    }

    val displayEmojis = if (query.isNotBlank()) searchResults else selectedCategory.emojis
    val gridState = rememberLazyGridState()

    // Reset grid scroll when category changes
    LaunchedEffect(selectedCategory) { gridState.scrollToItem(0) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // Search field
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                placeholder = { Text("Search emoji") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
            )

            // Category tabs (hidden while searching)
            if (query.isBlank()) {
                ScrollableTabRow(
                    selectedTabIndex = emojiCategories.indexOf(selectedCategory),
                    edgePadding = Spacing.md,
                    divider = {},
                ) {
                    emojiCategories.forEach { category ->
                        Tab(
                            selected = category == selectedCategory,
                            onClick = { selectedCategory = category },
                            text = { Text(category.name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            // Emoji grid
            if (displayEmojis.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No emoji found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 48.dp),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    contentPadding = PaddingValues(
                        start = Spacing.md,
                        end = Spacing.md,
                        top = Spacing.sm,
                        bottom = Spacing.xl,
                    ),
                ) {
                    items(displayEmojis) { emoji ->
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clickable { onEmojiSelected(emoji); onDismiss() },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(emoji, fontSize = 24.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
