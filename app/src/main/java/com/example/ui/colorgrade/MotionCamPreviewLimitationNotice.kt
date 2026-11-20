package com.example.ui.colorgrade

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun MotionCamPreviewLimitationNotice(modifier: Modifier = Modifier) {
    var showDetails by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MotionCam RAW playback and grade refresh are performance-limited; some effects are not shown.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showDetails = true }) {
                Text("Details")
            }
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("MotionCam RAW preview") },
            text = {
                Text(
                    "To keep playback and grade changes responsive, MotionCam playback and grade-refresh previews are rendered at up to 480 pixels on their longest edge; a paused source load may render a larger still frame. " +
                        "Exposure, temperature/tint, primary Lift/Gamma/Gain/Offset, three-zone color balance, brightness, " +
                        "contrast, and saturation are previewed. Curves, HSL, LUTs, multi-layer grades, and other advanced " +
                        "effects are not rendered in this RAW preview; those settings remain saved in the project. " +
                        "The H.264/MP4 export can develop .mcraw to 8-bit SDR, but it is not a full ISP or 10-bit RAW export and embedded RAW audio is omitted."
                )
            },
            confirmButton = {
                TextButton(onClick = { showDetails = false }) { Text("Got it") }
            }
        )
    }
}
