package org.mlm.mages.ui.components.location

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
actual fun StaticLocationViewer(
    lat: Double,
    lon: Double,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Static Location: $lat, $lon")
    }
}
