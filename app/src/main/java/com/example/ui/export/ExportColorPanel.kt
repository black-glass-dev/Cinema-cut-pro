package com.example.ui.export

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ExportColorPanel(modifier: Modifier = Modifier) {
    // Media3 export currently emits 8-bit H.264 SDR only; the UI must not suggest that HDR or
    // tone-mapping selectors are implemented merely because the settings model contains them.
    Column(modifier = modifier) {
        Text("Color Space & HDR", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("Output: 8-bit SDR · Rec.709", style = MaterialTheme.typography.bodyLarge)
        Text(
            "The export is always 8-bit SDR: HDR input is tone-mapped to SDR on Android 10+, and " +
                "verified greater-than-8-bit SDR video is converted to 8-bit. Log or unknown-transfer " +
                "sources are blocked. MotionCam Bayer RAW is developed to 8-bit SDR, not preserved " +
                "as 10-bit. HDR output, Rec.2020 output, and selectable tone mapping are unavailable.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
