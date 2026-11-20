package com.example.ui.export

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.timeline.core.TimelineProject
import com.example.timeline.core.TrackType
import com.example.timeline.export.model.ExportSettings
import com.example.timeline.media.MotionCamRawFormat

@Composable
fun ExportAudioPanel(
    settings: ExportSettings,
    onSettingsChanged: (ExportSettings) -> Unit,
    project: TimelineProject? = null,
    modifier: Modifier = Modifier
) {
    val activeVideoTracks = project?.tracks.orEmpty().filter { track ->
        track.type == TrackType.VIDEO && track.isVisible && track.clips.any { it.isEnabled }
    }
    val activeVideoTrack = activeVideoTracks.singleOrNull()
    val activeClips = activeVideoTrack?.clips?.filter { it.isEnabled }.orEmpty()
    val assetsByClip = activeClips.map { clip ->
        project?.mediaAssets?.firstOrNull { it.assetId == clip.mediaId }
    }
    val embeddedAudioFormats = assetsByClip.mapNotNull { asset ->
        asset?.metadata?.takeIf { it.hasAudio }?.audioStreams?.singleOrNull()
    }.map { it.sampleRate to it.channelCount }.distinct()
    val hasSeparateAudio = project?.tracks.orEmpty().any { track ->
        track.type == TrackType.AUDIO && !track.isMuted && track.clips.any { it.isEnabled }
    }
    val hasMcrawSource = assetsByClip.filterNotNull().any { MotionCamRawFormat.isMcrawAsset(it) }
    val allClipsHaveAudio = activeClips.isNotEmpty() && assetsByClip.all { it?.metadata?.hasAudio == true }
    val audioExportable = activeVideoTrack?.isMuted == false &&
        !hasMcrawSource && allClipsHaveAudio && embeddedAudioFormats.size == 1 &&
        embeddedAudioFormats.singleOrNull()?.let { (rate, channels) -> rate > 0 && channels in 1..2 } == true &&
        !hasSeparateAudio
    val audioSwitchEnabled = audioExportable || settings.includeAudio

    val status = when {
        project == null -> "AAC is the only audio codec supported by this export path."
        !settings.includeAudio && hasMcrawSource -> "MotionCam RAW embedded PCM is not connected to export; this will be a silent video."
        !settings.includeAudio -> "Audio will be omitted; the exported MP4 will be silent."
        hasMcrawSource -> "MotionCam RAW embedded PCM is not connected to this export path. Turn off Include audio to export its video."
        activeVideoTracks.size != 1 -> "Audio export requires exactly one visible video track. Turn off Include audio to export video only."
        activeVideoTrack?.isMuted == true -> "The video track is muted; the export will not include audio."
        activeClips.isEmpty() -> "No active video clips are available for audio export."
        assetsByClip.any { it == null } -> "One or more source clips are missing their media asset."
        hasSeparateAudio -> "Separate audio tracks and audio mixing are not supported. Turn off Include audio to export video only."
        assetsByClip.any { it?.metadata?.hasAudio == true } && !allClipsHaveAudio ->
            "Mixed audio and silent clips are not supported. Turn off Include audio to export a silent sequence."
        allClipsHaveAudio && assetsByClip.any { it?.metadata?.audioStreams?.size != 1 } ->
            "Exactly one embedded audio stream per clip is supported. Turn off Include audio to export video only."
        allClipsHaveAudio && embeddedAudioFormats.size > 1 ->
            "Source audio formats differ; sample-rate conversion and mixing are not implemented. Turn off Include audio to export video only."
        allClipsHaveAudio && embeddedAudioFormats.singleOrNull()?.second?.let { it !in 1..2 } == true ->
            "Only mono or stereo embedded audio is supported. Turn off Include audio to export video only."
        allClipsHaveAudio -> {
            val (sampleRate, channels) = embeddedAudioFormats.single()
            "AAC ${sampleRate} Hz · ${if (channels == 1) "mono" else "stereo"}; source sample rate and channel count are preserved."
        }
        else -> "No embedded audio is available. Turn off Include audio to export a silent video."
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Audio Settings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Include source audio", style = MaterialTheme.typography.bodyMedium)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = settings.includeAudio,
                enabled = audioSwitchEnabled,
                onCheckedChange = { enabled -> onSettingsChanged(settings.copy(includeAudio = enabled)) }
            )
        }

        if (settings.includeAudio && audioExportable) {
            Column {
                Text("AAC bitrate", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val bitrates = listOf(128_000, 192_000, 256_000, 320_000)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    bitrates.forEach { bitrate ->
                        FilterChip(
                            selected = settings.audioBitrate == bitrate,
                            onClick = { onSettingsChanged(settings.copy(audioBitrate = bitrate)) },
                            label = { Text("${bitrate / 1000} kbps") }
                        )
                    }
                }
            }
        }
    }
}
