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
import mages.shared.generated.resources.*
import org.koin.compose.koinInject
import org.mlm.mages.emoji.RecentEmojiStore
import org.mlm.mages.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import mages.shared.generated.resources.Res

@Composable
fun EmojiPickerSheet(
    onDismiss: () -> Unit,
    onEmojiSelected: (String) -> Unit,
) {
    val recentStore: RecentEmojiStore = koinInject()
    val recent by recentStore.recent.collectAsState()

    val categories = remember(recent) {
        if (recent.isEmpty()) {
            emojiCategories
        } else {
            listOf(EmojiCategory(Res.string.recent, recent.map { EmojiEntry(it.emoji) })) + emojiCategories
        }
    }

    var selectedCategory by remember(categories) { mutableStateOf(categories.first()) }
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
                selectedTabIndex = categories.indexOf(selectedCategory),
                edgePadding = Spacing.md,
                divider = {},
            ) {
                categories.forEach { category ->
                    Tab(
                        selected = category == selectedCategory,
                        onClick = { selectedCategory = category },
                        text = { Text(stringResource(category.name), style = MaterialTheme.typography.labelSmall) }
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
                items(selectedCategory.emojis) { entry ->
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { onEmojiSelected(entry.emoji); onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(entry.emoji, fontSize = 24.sp, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
