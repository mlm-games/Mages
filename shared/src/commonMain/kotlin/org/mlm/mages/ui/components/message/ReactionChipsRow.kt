package org.mlm.mages.ui.components.message

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
    showAvatars: Boolean = false,
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
                showAvatars = showAvatars,
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
    showAvatars: Boolean = true,
    onClick: ((String) -> Unit)?,
    onLongClick: ((String) -> Unit)?
) {
    val isSelected = chip.mine

    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    val outlineColor = if (isSelected) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest
    }

    Surface(
        modifier = Modifier.combinedClickable(
            onClick = { onClick?.invoke(chip.key) },
            onLongClick = { onLongClick?.invoke(chip.key) }
        ),
        shape = RoundedCornerShape(percent = 50),
        color = backgroundColor,
        border = BorderStroke(1.dp, outlineColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = chip.key,
                fontSize = 16.sp,
                lineHeight = 16.sp
            )

            if (showAvatars && chip.userIds.isNotEmpty()) {
                val maxAvatars = 5
                val userIdsToShow = chip.userIds.take(maxAvatars)

                Row(
                    horizontalArrangement = Arrangement.spacedBy((-6).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    userIdsToShow.forEach { userId ->
                        Avatar(
                            name = userId,
                            avatarPath = avatarPathsByUserId[userId],
                            size = 20.dp,
//                            modifier = Modifier.border(1.5.dp, outlineColor.copy(alpha = 0.9f), RoundedCornerShape(percent = 50))
                        )
                    }
                }

                if (chip.count > userIdsToShow.size) {
                    Text(
                        text = "+${chip.count - userIdsToShow.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
            else {
                Text("${chip.count}",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 13.sp)
            }
        }
    }
}
