package org.mlm.mages.ui.components.core

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.mlm.mages.ui.theme.Spacing

@Composable
fun LoadMoreButton(
    isLoading: Boolean,
    onClick: () -> Unit,
    text: String = "Load more",
    loadingText: String = "Loading...",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.lg),
        contentAlignment = Alignment.Center
    ) {
        OutlinedButton(
            onClick = onClick,
            enabled = !isLoading
        ) {
            if (isLoading) {
                LoadingIndicator(
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isLoading) loadingText else text)
        }
    }
}