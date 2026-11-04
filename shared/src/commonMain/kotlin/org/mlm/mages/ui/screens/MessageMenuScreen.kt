package org.mlm.mages.ui.screens

import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.annotations.SettingPlatform
import io.github.mlmgames.settings.core.platform.currentPlatform
import kotlinx.coroutines.launch
import mages.shared.generated.resources.Res
import mages.shared.generated.resources.back
import mages.shared.generated.resources.bookmark
import mages.shared.generated.resources.copy
import mages.shared.generated.resources.download
import mages.shared.generated.resources.edit
import mages.shared.generated.resources.forward
import mages.shared.generated.resources.mark_as_read_here
import mages.shared.generated.resources.message_info
import mages.shared.generated.resources.message_menu_order_hint
import mages.shared.generated.resources.move_down
import mages.shared.generated.resources.move_up
import mages.shared.generated.resources.pin
import mages.shared.generated.resources.reorder
import mages.shared.generated.resources.reply_action
import mages.shared.generated.resources.reply_in_thread
import mages.shared.generated.resources.reset
import mages.shared.generated.resources.select
import mages.shared.generated.resources.setting_message_menu_order
import mages.shared.generated.resources.share
import mages.shared.generated.resources.ic_arrow_back
import mages.shared.generated.resources.ic_bookmark
import mages.shared.generated.resources.ic_content_copy
import mages.shared.generated.resources.ic_deselect
import mages.shared.generated.resources.ic_done
import mages.shared.generated.resources.ic_drag_handle
import mages.shared.generated.resources.ic_edit
import mages.shared.generated.resources.ic_forward
import mages.shared.generated.resources.ic_gesture
import mages.shared.generated.resources.ic_info
import mages.shared.generated.resources.ic_push_pin
import mages.shared.generated.resources.ic_reply
import mages.shared.generated.resources.ic_share
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.mlm.mages.settings.AppSettings
import org.mlm.mages.settings.MessageMenuAction
import org.mlm.mages.settings.encodeMessageMenuOrder
import org.mlm.mages.settings.parseMessageMenuOrder
import org.mlm.mages.ui.theme.Spacing
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

private val menuRowHeight = 56.dp

@Composable
fun MessageMenuRoute(onBack: () -> Unit) {
    val settingsRepository: SettingsRepository<AppSettings> = koinInject()
    val settings by settingsRepository.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    MessageMenuScreen(
        order = parseMessageMenuOrder(settings.messageMenuOrder),
        onOrderChange = { order ->
            val encoded = encodeMessageMenuOrder(order)
            scope.launch { settingsRepository.update { it.copy(messageMenuOrder = encoded) } }
        },
        onReset = {
            scope.launch { settingsRepository.update { it.copy(messageMenuOrder = "") } }
        },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageMenuScreen(
    order: List<MessageMenuAction>,
    onOrderChange: (List<MessageMenuAction>) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    var items by remember(order) { mutableStateOf(order) }
    val currentItems by rememberUpdatedState(items)
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        items = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }

    fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target !in items.indices) return
        items = items.toMutableList().apply { add(target, removeAt(index)) }
        onOrderChange(items)
    }

    val moveUpLabel = stringResource(Res.string.move_up)
    val moveDownLabel = stringResource(Res.string.move_down)
    val reorderLabel = stringResource(Res.string.reorder)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(Res.string.setting_message_menu_order),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onReset) {
                        Text(stringResource(Res.string.reset))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = stringResource(Res.string.message_menu_order_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(items, key = { _, action -> action.name }) { index, action ->
                    ReorderableItem(reorderableState, key = action.name) { isDragging ->
                        val interactionSource = remember { MutableInteractionSource() }
                        Surface(
                            color = if (isDragging) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
                            shadowElevation = if (isDragging) 4.dp else 0.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(menuRowHeight)
                                .semantics {
                                    customActions = listOf(
                                        CustomAccessibilityAction(moveUpLabel) {
                                            move(index, -1)
                                            true
                                        },
                                        CustomAccessibilityAction(moveDownLabel) {
                                            move(index, 1)
                                            true
                                        }
                                    )
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = Spacing.lg),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    painter = action.icon(),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(Spacing.md))
                                Text(
                                    text = action.label(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {},
                                    interactionSource = interactionSource,
                                    modifier = Modifier
                                        .draggableHandle(
                                            interactionSource = interactionSource,
                                            onDragStopped = { onOrderChange(currentItems) },
                                        )
                                ) {
                                    Icon(
                                        painter = painterResource(Res.drawable.ic_drag_handle),
                                        contentDescription = reorderLabel,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageMenuAction.label(): String = when (this) {
    MessageMenuAction.Reply -> stringResource(Res.string.reply_action)
    MessageMenuAction.ReplyInThread -> stringResource(Res.string.reply_in_thread)
    MessageMenuAction.Edit -> stringResource(Res.string.edit)
    MessageMenuAction.Copy -> stringResource(Res.string.copy)
    MessageMenuAction.Forward -> stringResource(Res.string.forward)
    MessageMenuAction.Share -> if (currentPlatform == SettingPlatform.WEB) {
        stringResource(Res.string.download)
    } else {
        stringResource(Res.string.share)
    }
    MessageMenuAction.Pin -> stringResource(Res.string.pin)
    MessageMenuAction.Bookmark -> stringResource(Res.string.bookmark)
    MessageMenuAction.MarkRead -> stringResource(Res.string.mark_as_read_here)
    MessageMenuAction.Select -> stringResource(Res.string.select)
    MessageMenuAction.Info -> stringResource(Res.string.message_info)
}

@Composable
private fun MessageMenuAction.icon(): Painter = when (this) {
    MessageMenuAction.Reply -> painterResource(Res.drawable.ic_reply)
    MessageMenuAction.ReplyInThread -> painterResource(Res.drawable.ic_gesture)
    MessageMenuAction.Edit -> painterResource(Res.drawable.ic_edit)
    MessageMenuAction.Copy -> painterResource(Res.drawable.ic_content_copy)
    MessageMenuAction.Forward -> painterResource(Res.drawable.ic_forward)
    MessageMenuAction.Share -> painterResource(Res.drawable.ic_share)
    MessageMenuAction.Pin -> painterResource(Res.drawable.ic_push_pin)
    MessageMenuAction.Bookmark -> painterResource(Res.drawable.ic_bookmark)
    MessageMenuAction.MarkRead -> painterResource(Res.drawable.ic_done)
    MessageMenuAction.Select -> painterResource(Res.drawable.ic_deselect)
    MessageMenuAction.Info -> painterResource(Res.drawable.ic_info)
}
