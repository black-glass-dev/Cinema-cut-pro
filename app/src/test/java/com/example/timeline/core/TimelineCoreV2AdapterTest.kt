package com.example.timeline.core

import com.example.timeline.engine.native.v2.NativeTimelineCoreV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineCoreV2AdapterTest {
    @Test
    fun testGroupIdAndGroupLockAreSerializedIntoNativeClipPayload() {
        val linkedGroupId = "av-pair-1"
        val video = TimelineClip(
            id = "video",
            trackId = "video-track",
            mediaId = "video-media",
            name = "video.mp4",
            sourceIn = 0L,
            sourceOut = 100L,
            timelineStart = 0L,
            metadata = mapOf("linkedGroupId" to linkedGroupId),
            isGroupLocked = true
        )
        val audio = TimelineClip(
            id = "audio",
            trackId = "audio-track",
            mediaId = "audio-media",
            name = "audio.wav",
            sourceIn = 0L,
            sourceOut = 100L,
            timelineStart = 0L,
            metadata = mapOf("linkedGroupId" to linkedGroupId),
            isGroupLocked = true
        )
        val ungrouped = TimelineClip(
            id = "still",
            trackId = "audio-track",
            mediaId = "still-media",
            name = "still.png",
            sourceIn = 0L,
            sourceOut = 100L,
            timelineStart = 100L
        )
        val project = TimelineProject(
            id = "group-project",
            settings = ProjectSettings(projectId = "group-project", projectName = "Linked group"),
            tracks = listOf(
                TimelineTrack("video-track", "Video", TrackType.VIDEO, clips = listOf(video)),
                TimelineTrack("audio-track", "Audio", TrackType.AUDIO, clips = listOf(audio, ungrouped))
            )
        )

        val packed = TimelineCoreV2Adapter.packProject(project, emptyList())
        val linkedGroupNumericId = packed.groupIds.idFor(linkedGroupId)

        assertTrue(linkedGroupNumericId != 0L)
        assertEquals(linkedGroupNumericId, packed.clipData[8])
        assertEquals(linkedGroupNumericId, packed.clipData[17])
        assertTrue(packed.clipData[7] and 2L != 0L)
        assertTrue(packed.clipData[16] and 2L != 0L)
        assertEquals(0L, packed.clipData[26])
        assertEquals(0L, packed.clipData[25] and 2L)
    }

    @Test
    fun testGroupLockCommandPacksExplicitBooleanForNativeCore() {
        val clip = TimelineClip(
            id = "clip",
            trackId = "track",
            mediaId = "media",
            name = "clip.mp4",
            sourceIn = 0L,
            sourceOut = 100L,
            timelineStart = 0L,
            metadata = mapOf("linkedGroupId" to "linked-1")
        )
        val project = TimelineProject(
            id = "lock-project",
            settings = ProjectSettings(projectId = "lock-project", projectName = "Group lock"),
            tracks = listOf(TimelineTrack("track", "Video", TrackType.VIDEO, clips = listOf(clip)))
        )

        val lockPayload = TimelineCoreV2Adapter.packProject(
            project,
            listOf(
                CoreV2EditCommand(
                    kind = CoreV2EditKind.SET_GROUP_LOCK,
                    clipId = clip.id,
                    groupLocked = true
                )
            )
        ).commandData
        val unlockPayload = TimelineCoreV2Adapter.packProject(
            project,
            listOf(
                CoreV2EditCommand(
                    kind = CoreV2EditKind.SET_GROUP_LOCK,
                    clipId = clip.id,
                    groupLocked = false
                )
            )
        ).commandData

        assertEquals(9L, lockPayload[1])
        assertEquals(1L, lockPayload[6])
        assertEquals(0L, unlockPayload[6])
    }

    @Test
    fun testINV15_NativeUnavailableRejectsPreviewCommitAndSnapWithoutFallback() {
        NativeTimelineCoreV2.markUnavailable()
        val clip = TimelineClip(
            id = "clip",
            trackId = "track",
            mediaId = "media",
            name = "clip.mp4",
            sourceIn = 0L,
            sourceOut = 100L,
            timelineStart = 0L
        )
        val project = TimelineProject(
            id = "project",
            settings = ProjectSettings(projectId = "project", projectName = "Core v2 unavailable"),
            tracks = listOf(TimelineTrack("track", "Video", TrackType.VIDEO, clips = listOf(clip)))
        )
        val before = project
        val command = CoreV2EditCommand(
            kind = CoreV2EditKind.MOVE,
            clipId = clip.id,
            targetTrackId = clip.trackId,
            targetFrame = 10L
        )

        val preview = TimelineCoreV2Adapter.previewEdit(project, command)
        val commit = TimelineCoreV2Adapter.commitEdit(project, command)
        val snap = TimelineCoreV2Adapter.snap(project, inputFrame = 1L, playheadFrame = 0L, thresholdFrames = 2L)

        assertTrue(preview is CoreV2EditOutcome.Rejected)
        assertEquals("TL_E_NATIVE_UNAVAILABLE", (preview as CoreV2EditOutcome.Rejected).errorCode)
        assertTrue(commit is CoreV2EditOutcome.Rejected)
        assertEquals("TL_E_NATIVE_UNAVAILABLE", (commit as CoreV2EditOutcome.Rejected).errorCode)
        assertTrue(snap is CoreV2SnapOutcome.Rejected)
        assertEquals("TL_E_NATIVE_UNAVAILABLE", (snap as CoreV2SnapOutcome.Rejected).errorCode)
        assertEquals(before, project)
        assertEquals(0L, project.tracks.single().clips.single().timelineStart)
    }
}
