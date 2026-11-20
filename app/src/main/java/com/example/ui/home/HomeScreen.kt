package com.example.ui.home

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import com.example.R
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.timeline.core.ProjectMetadata
import com.example.timeline.core.TimelineProject
import com.example.ui.theme.CinemaColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    recentProjects: List<Pair<TimelineProject, ProjectMetadata>>,
    onCreateProject: () -> Unit,
    onImportProject: () -> Unit,
    onOpenProject: (String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var gridMode by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CinemaColors.Page)
            .verticalScroll(scrollState)
            .padding(horizontal = 22.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(42.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(CinemaColors.Ink),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.cinema_cut_reel),
                    contentDescription = null,
                    modifier = Modifier.size(27.dp)
                )
            }
            Text(
                "Cinema Cut Pro",
                modifier = Modifier.padding(start = 10.dp).weight(1f),
                color = CinemaColors.Ink,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = CinemaColors.Ink)
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("New Project", color = CinemaColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(9.dp))
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(CinemaColors.Ink).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(CinemaColors.Red),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                }
                Column(Modifier.padding(start = 10.dp)) {
                    Text("New Project", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Create or import a project", color = CinemaColors.Muted, fontSize = 12.sp)
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onCreateProject),
                color = CinemaColors.Red,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Text("Create Project", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth().height(38.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onImportProject),
                color = CinemaColors.Button,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Upload, null, tint = Color.White, modifier = Modifier.size(17.dp))
                    Text("Input Project", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
            Text(
                "Create a new project or import an existing one",
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                color = CinemaColors.Muted,
                fontSize = 10.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Recent Projects", modifier = Modifier.weight(1f), color = CinemaColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { gridMode = !gridMode }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Apps, contentDescription = if (gridMode) "Show projects as a list" else "Show projects as a grid", tint = CinemaColors.Ink)
            }
            IconButton(onClick = { showAll = !showAll }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.ChevronRight, contentDescription = if (showAll) "Show fewer projects" else "See all recent projects", tint = CinemaColors.Ink)
            }
        }
        Spacer(Modifier.height(4.dp))

        if (recentProjects.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your projects will appear here", color = CinemaColors.SecondaryText, fontSize = 13.sp)
                    TextButton(onClick = onCreateProject) { Text("Create your first project", color = CinemaColors.Red) }
                }
            }
        } else {
            val visibleProjects = if (showAll) recentProjects else recentProjects.take(3)
            if (gridMode) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 2
                ) {
                    visibleProjects.forEach { (project, metadata) ->
                        RecentProjectCard(
                            project = project,
                            metadata = metadata,
                            gridMode = true,
                            onClick = { onOpenProject(project.id) },
                            modifier = Modifier.fillMaxWidth(0.48f)
                        )
                    }
                }
            } else {
                visibleProjects.forEach { (project, metadata) ->
                    RecentProjectCard(
                        project = project,
                        metadata = metadata,
                        onClick = { onOpenProject(project.id) },
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentProjectCard(
    project: TimelineProject,
    metadata: ProjectMetadata,
    gridMode: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val sourceUri = project.mediaAssets.firstOrNull()?.let { asset ->
        asset.localOriginalUriString?.takeIf(String::isNotBlank) ?: asset.originalUriString
    }
    val thumbnail by produceState<Bitmap?>(null, project.id, sourceUri) {
        value = if (sourceUri == null) null else withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                val uri = Uri.parse(sourceUri)
                if (uri.scheme == "content") retriever.setDataSource(context, uri)
                else retriever.setDataSource(uri.path ?: sourceUri)
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
    Card(
        modifier = modifier.fillMaxWidth().height(if (gridMode) 150.dp else 56.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        if (gridMode) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(88.dp).background(CinemaColors.LightGray), contentAlignment = Alignment.Center) {
                    if (thumbnail != null) {
                        Image(thumbnail!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Image(painterResource(R.drawable.cinema_cut_reel), contentDescription = null, modifier = Modifier.size(32.dp))
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp)) {
                    Text(project.name, color = CinemaColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${project.settings.resolutionWidth}x${project.settings.resolutionHeight} · ${project.settings.getFpsRational()}fps · ${relativeTime(metadata.modifiedDate)}",
                        color = CinemaColors.Muted,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(112.dp).height(56.dp).background(CinemaColors.LightGray), contentAlignment = Alignment.Center) {
                    if (thumbnail != null) {
                        Image(thumbnail!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Image(painterResource(R.drawable.cinema_cut_reel), contentDescription = null, modifier = Modifier.size(30.dp))
                    }
                }
                Column(Modifier.weight(1f).padding(start = 11.dp, end = 6.dp)) {
                    Text(project.name, color = CinemaColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${project.settings.resolutionWidth}x${project.settings.resolutionHeight}  ·  ${project.settings.getFpsRational()}fps  ·  ${relativeTime(metadata.modifiedDate)}",
                        color = CinemaColors.Muted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(Modifier.width(44.dp).fillMaxSize().background(Color(0xFFF9F9FA)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.ArrowForward, contentDescription = "Open ${project.name}", tint = CinemaColors.SecondaryText, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

private fun relativeTime(timestamp: Long): String {
    val age = (System.currentTimeMillis() - timestamp).coerceAtLeast(0L)
    val days = TimeUnit.MILLISECONDS.toDays(age)
    return when {
        days == 0L -> "Today"
        days == 1L -> "Yesterday"
        days < 7L -> "$days days ago"
        else -> "${TimeUnit.MILLISECONDS.toDays(age)} days ago"
    }
}
