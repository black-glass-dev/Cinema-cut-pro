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
fun ExportCodecPanel(modifier: Modifier = Modifier) {
    // The bounded Media3 Transformer backend only produces H.264 in MP4. Avoid showing selectable
    // MOV/MKV, HEVC, AV1, ProRes, or image-sequence options that cannot reach a real renderer.
    Column(modifier = modifier) {
        Text("Format & Codec", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("MP4 container · H.264 video", style = MaterialTheme.typography.bodyLarge)
        Text(
            "This is the only video format currently implemented by the Android export backend. " +
                "MotionCam RAW (.mcraw) is developed to 8-bit SDR before H.264 encoding when the device has a compatible encoder. " +
                "10-bit output, ProRes (all profiles), APV, HEVC, AV1, MOV, MKV, and image-sequence export are not available.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
