package com.example.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.example.timeline.core.ProjectSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorTopBar(
    settings: ProjectSettings?,
    onExportClick: () -> Unit,
    onImportClick: () -> Unit,
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to projects", tint = MaterialTheme.colorScheme.onSurface)
            }
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.cinema_cut_reel),
                    contentDescription = null,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "CINEMA CUT PRO",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = settings?.projectName ?: "Untitled",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }
            }
        },
        actions = {
            Button(
                onClick = onImportClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shape = RoundedCornerShape(22.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.padding(end = 6.dp).height(36.dp)
            ) {
                Text("Input", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Button(
                onClick = onExportClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(22.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                modifier = Modifier.padding(end = 8.dp).height(36.dp)
            ) {
                Text("Export", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        modifier = modifier
    )
}
