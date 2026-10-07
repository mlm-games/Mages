package org.mlm.mages.ui.components.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import mages.shared.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.mlm.mages.ui.SpaceSectionEntry
import org.mlm.mages.ui.theme.Spacing

private const val NO_SECTION_TAG = "__default__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceSectionsSheet(
    sections: List<SpaceSectionEntry>,
    isSaving: Boolean,
    onCreate: () -> Unit,
    onRename: (SpaceSectionEntry) -> Unit,
    onDelete: (SpaceSectionEntry) -> Unit,
    onMove: (SpaceSectionEntry, Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Text(
                stringResource(Res.string.sections),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.sm)
            )

            ActionListItem(
                icon = Icons.Default.Add,
                label = stringResource(Res.string.new_section),
                description = null,
                onClick = { onDismiss(); onCreate() }
            )

            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                itemsIndexed(
                    sections,
                    key = { _, section -> section.tag ?: NO_SECTION_TAG }
                ) { index, section ->
                    SectionSheetRow(
                        section = section,
                        canMoveUp = index > 0,
                        canMoveDown = index < sections.lastIndex,
                        enabled = !isSaving,
                        onRename = { onRename(section) },
                        onDelete = { onDelete(section) },
                        onMoveUp = { onMove(section, -1) },
                        onMoveDown = { onMove(section, 1) }
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

@Composable
private fun SectionSheetRow(
    section: SpaceSectionEntry,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    enabled: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    ListItem(
        headlineContent = { Text(section.name) },
        supportingContent = {
            val count = section.subspaces.size + section.rooms.size
            Text(stringResource(Res.string.rooms_in_count_n, count))
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                IconButton(onClick = onMoveUp, enabled = enabled && canMoveUp) {
                    Icon(
                        Icons.Default.ArrowUpward,
                        stringResource(Res.string.move_section_up)
                    )
                }
                IconButton(onClick = onMoveDown, enabled = enabled && canMoveDown) {
                    Icon(
                        Icons.Default.ArrowDownward,
                        stringResource(Res.string.move_section_down)
                    )
                }
                IconButton(onClick = onRename, enabled = enabled) {
                    Icon(Icons.Default.Edit, stringResource(Res.string.rename_section))
                }
                IconButton(onClick = onDelete, enabled = enabled) {
                    Icon(
                        Icons.Default.Delete,
                        stringResource(Res.string.delete_section),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onRename)
    )
}