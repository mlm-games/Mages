package org.mlm.mages.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.mlm.mages.matrix.ReactionSummary
import org.mlm.mages.ui.components.core.Avatar
import org.mlm.mages.ui.theme.Sizes

enum class ReactionChipStyle {
    Timeline,
    ThreadRoot,
}

@Composable
fun ReactionChipsRow(
    chips: List<ReactionSummary>,
    modifier: Modifier = Modifier,
    style: ReactionChipStyle = ReactionChipStyle.Timeline,
    maxVisible: Int? = null,
    avatarPathsByUserId: Map<String, String> = emptyMap(),
    onClick: ((String) -> Unit)? = null,
    onLongClick: ((String) -> Unit)? = null,
) {
    if (chips.isEmpty()) return

    val visibleChips = maxVisible?.let { chips.take(it) } ?: chips

    FlowRow(
        modifier = modifier.padding(top = 0.dp),
        horizontalArrangement = Arrangement.spacedBy((-2).dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        visibleChips.forEach { chip ->
            ReactionChip(
                chip = chip,
                avatarPathsByUserId = avatarPathsByUserId,
                onClick = onClick,
                onLongClick = onLongClick
            )
        }
    }
}

@Composable
private fun ReactionChip(
    chip: ReactionSummary,
    avatarPathsByUserId: Map<String, String>,
    onClick: ((String) -> Unit)?,
    onLongClick: ((String) -> Unit)?
) {
    val isSelected = chip.mine

    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    val contentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val userIdsWithAvatars = chip.userIds.map { userId ->
        userId to avatarPathsByUserId[userId]
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(3.dp, MaterialTheme.colorScheme.surface)
    ) {
        Surface(
            modifier = Modifier.combinedClickable(
                onClick = { onClick?.invoke(chip.key) },
                onLongClick = { onLongClick?.invoke(chip.key) }
            ),
            shape = RoundedCornerShape(12.dp),
            color = backgroundColor,
            contentColor = contentColor
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (userIdsWithAvatars.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                        userIdsWithAvatars.take(3).forEach { (userId, avatarPath) ->
                            Avatar(
                                name = userId,
                                avatarPath = avatarPath,
                                size = 20.dp
                            )
                        }
                    }
                }
                Text(
                    text = chip.key,
                    fontSize = 18.sp
                )
                if (chip.count > 1) {
                    val overflow = chip.count - 3
                    val displayCount = if (overflow > 0) "+$overflow" else chip.count.toString()
                    Text(
                        text = displayCount,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
