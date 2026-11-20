package com.example.ui.editor

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal val editorSections = listOf("Timeline", "Color")

@Composable
fun EditorToolTabs(
    selectedTab: String,
    onTabSelected: (String) -> Unit
) {
    ScrollableTabRow(
        selectedTabIndex = editorSections.indexOf(selectedTab).coerceAtLeast(0),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        edgePadding = 8.dp,
        divider = { androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
    ) {
        editorSections.forEach { title ->
            Tab(
                selected = selectedTab == title,
                onClick = { onTabSelected(title) },
                text = { Text(title, fontWeight = FontWeight.Medium) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
