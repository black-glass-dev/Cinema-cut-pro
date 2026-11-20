package com.example.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.timeline.media.MediaAsset
import com.example.timeline.ui.TimelineViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaLibrarySheet(
    mediaAssets: List<MediaAsset>,
    proxyProgressMap: Map<String, Float>,
    launchers: com.example.timeline.ui.MediaImportLaunchers,
    timelineViewModel: TimelineViewModel,
    onRelinkRequest: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 500.dp)) {
            ProjectMediaStrip(
                mediaAssets = mediaAssets,
                proxyProgressMap = proxyProgressMap,
                launchers = launchers,
                timelineViewModel = timelineViewModel,
                onRelinkRequest = onRelinkRequest,
                onDismiss = onDismiss,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
