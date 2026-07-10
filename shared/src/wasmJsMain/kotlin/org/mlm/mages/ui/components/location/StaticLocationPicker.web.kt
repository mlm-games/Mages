package org.mlm.mages.ui.components.location

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
actual fun StaticLocationPicker(
    isSending: Boolean,
    onSendCurrentLocation: () -> Unit,
    onSendPickedLocation: (lat: Double, lon: Double) -> Unit,
    onCenterOnMyLocation: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        FilledIconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") }
        Spacer(Modifier.height(16.dp))
        Text("Share Location (Web stub — no map)")
        Spacer(Modifier.weight(1f))
        if (isSending) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            Button(onClick = onSendCurrentLocation, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.MyLocation, null)
                Spacer(Modifier.width(8.dp))
                Text("Send my current location")
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onSendPickedLocation(0.0, 0.0) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.LocationOn, null)
                Spacer(Modifier.width(8.dp))
                Text("Send this location (0,0)")
            }
        }
    }
}
