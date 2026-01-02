package org.mlm.mages.ui.components.message

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.mlm.mages.matrix.PollData
import org.mlm.mages.matrix.PollKind
import org.mlm.mages.matrix.PollOption

@Composable
fun PollBubble(
    poll: PollData,
    isMine: Boolean,
    onVote: (String) -> Unit,
    onEndPoll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val showResults = poll.isEnded || poll.kind == PollKind.Disclosed

    val contentColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = if (isMine) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.secondary
    val winnerColor = MaterialTheme.colorScheme.tertiary

    Column(modifier = modifier.widthIn(min = 220.dp, max = 300.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (poll.isEnded) {
                Icon(
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = "Ended",
                    tint = winnerColor,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = poll.question,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor
            )
        }

        if (poll.isEnded) {
            Text(
                text = "Poll ended",
                style = MaterialTheme.typography.labelSmall,
                color = winnerColor,
                modifier = Modifier.padding(top = 2.dp)
            )
        } else if (poll.kind == PollKind.Undisclosed) {
            Text(
                text = "Results hidden until ended",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        poll.options.forEachIndexed { index, option ->
            PollOptionItem(
                option = option,
                totalVotes = poll.totalVotes,
                showResults = showResults,
                enabled = !poll.isEnded,
                isMine = isMine,
                onClick = { onVote(option.id) }
            )
            if (index < poll.options.lastIndex) {
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${poll.totalVotes} vote${if (poll.totalVotes != 1L) "s" else ""}",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f)
            )

            if (isMine && !poll.isEnded) {
                Text(
                    text = "End Poll",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onEndPoll)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun PollOptionItem(
    option: PollOption,
    totalVotes: Long,
    showResults: Boolean,
    enabled: Boolean,
    isMine: Boolean,
    onClick: () -> Unit
) {
    val contentColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    val accentColor = if (isMine) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.secondary
    val winnerColor = MaterialTheme.colorScheme.tertiary

    val percentage = if (totalVotes > 0) option.votes.toFloat() / totalVotes.toFloat() else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = if (showResults) percentage else 0f,
        animationSpec = tween(300),
        label = "progress"
    )

    val borderColor = when {
        option.isWinner -> winnerColor.copy(alpha = 0.7f)
        option.isSelected -> accentColor.copy(alpha = 0.7f)
        else -> contentColor.copy(alpha = 0.3f)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(contentColor.copy(alpha = 0.06f))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        if (showResults && percentage > 0) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .background(
                        when {
                            option.isWinner -> winnerColor.copy(alpha = 0.25f)
                            else -> accentColor.copy(alpha = 0.2f)
                        }
                    )
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (option.isSelected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = when {
                    option.isWinner -> winnerColor
                    option.isSelected -> accentColor
                    else -> contentColor.copy(alpha = 0.5f)
                },
                modifier = Modifier.size(18.dp)
            )

            Spacer(Modifier.width(8.dp))

            Text(
                text = option.text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (option.isSelected || option.isWinner) FontWeight.Medium else FontWeight.Normal,
                color = contentColor,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )

            if (showResults) {
                Text(
                    text = "${(percentage * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = when {
                        option.isWinner -> winnerColor
                        else -> accentColor
                    }
                )
            }
        }
    }
}