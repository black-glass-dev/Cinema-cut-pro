package com.example

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real launcher and opens the Color workspace on a device. Pixel grading still
 * needs a representative imported clip and is covered by the recorded manual device checklist. */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launcherOpensEditorAndColorWorkspace() {
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Timeline").assertExists()
        composeRule.onNodeWithText("Color").performClick()
        composeRule.waitForIdle()

        val emptyWorkspaceMessage = composeRule
            .onAllNodesWithText("Select a clip to grade")
            .fetchSemanticsNodes()
        if (emptyWorkspaceMessage.isNotEmpty()) {
            composeRule.onNodeWithText("Select a clip to grade").assertExists()
        } else {
            composeRule.onNodeWithText("Wheels").assertExists()
        }
    }
}
