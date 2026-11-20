package com.example.timeline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectStorageRecentProjectsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun listProjectsReturnsSavedProjectsNewestFirst() {
        val storage = ProjectStorage(temporaryFolder.newFolder("projects"))
        val older = project("older", "River Trip")
        val newer = project("newer", "Product Showcase")
        storage.saveProject(older, metadata(older, modified = 100L))
        storage.saveProject(newer, metadata(newer, modified = 200L))

        val recent = storage.listProjects()

        assertEquals(listOf("newer", "older"), recent.map { it.first.id })
        assertEquals(listOf("Product Showcase", "River Trip"), recent.map { it.second.name })
    }

    @Test
    fun listProjectsIgnoresIncompleteProjectFolders() {
        val root = temporaryFolder.newFolder("projects")
        ProjectStorage(root)
        java.io.File(root, "unfinished-import").mkdirs()

        assertTrue(ProjectStorage(root).listProjects().isEmpty())
    }

    private fun project(id: String, name: String) = TimelineProject(
        id = id,
        settings = ProjectSettings(projectId = id, projectName = name)
    )

    private fun metadata(project: TimelineProject, modified: Long) = ProjectMetadata(
        id = project.id,
        name = project.name,
        createdDate = modified - 10L,
        modifiedDate = modified,
        appVersion = "test",
        schemaVersion = ProjectMigration.CURRENT_SCHEMA_VERSION
    )
}
