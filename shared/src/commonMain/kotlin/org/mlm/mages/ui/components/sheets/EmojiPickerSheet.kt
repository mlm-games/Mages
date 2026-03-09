package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
    var selectedCategory by remember { mutableStateOf(emojiCategories.first()) }
    val gridState = rememberLazyGridState()

    // Reset grid scroll when category changes
    LaunchedEffect(selectedCategory) { gridState.scrollToItem(0) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            SecondaryScrollableTabRow(
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

            // Emoji grid
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
                items(selectedCategory.emojis) { emoji ->
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
