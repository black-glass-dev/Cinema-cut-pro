package com.example.timeline.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineClipSerializationCompatibilityTest {
    @Test
    fun missingGroupLockFieldDefaultsToUnlockedForLegacyClipJson() {
        val legacyJson = """{"id":"clip","trackId":"track","mediaId":"media","name":"clip.mp4","sourceIn":0,"sourceOut":10,"timelineStart":0}"""

        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<TimelineClip>(legacyJson)

        assertFalse(decoded.isGroupLocked)
    }

    @Test
    fun groupLockFieldRoundTripsWhenPresent() {
        val clip = TimelineClip(
            id = "clip",
            trackId = "track",
            mediaId = "media",
            name = "clip.mp4",
            sourceIn = 0L,
            sourceOut = 10L,
            timelineStart = 0L,
            isGroupLocked = true
        )
        val json = Json { encodeDefaults = true }.encodeToString(clip)

        assertTrue(Json.decodeFromString<TimelineClip>(json).isGroupLocked)
    }
}
