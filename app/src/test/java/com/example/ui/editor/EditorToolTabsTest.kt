package com.example.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorToolTabsTest {
    @Test
    fun standardEditorShowsOnlyTimelineAndColorSections() {
        assertEquals(listOf("Timeline", "Color"), editorSections)
    }
}
