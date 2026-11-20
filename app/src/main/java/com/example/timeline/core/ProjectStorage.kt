package com.example.timeline.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ProjectStorage(private val rootDir: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    init {
        rootDir.mkdirs()
    }

    private fun getProjectDir(projectId: String): File {
        val dir = File(rootDir, projectId)
        dir.mkdirs()
        return dir
    }

    fun saveProject(project: TimelineProject, metadata: ProjectMetadata) {
        val dir = getProjectDir(project.id)
        
        val metaFile = File(dir, "metadata.json")
        val metaTemp = File(dir, "metadata.json.tmp")
        metaTemp.writeText(json.encodeToString(metadata))
        if (metaFile.exists()) metaFile.delete()
        metaTemp.renameTo(metaFile)

        val projectFile = File(dir, "project.json")
        val projectTemp = File(dir, "project.json.tmp")
        projectTemp.writeText(json.encodeToString(project))
        if (projectFile.exists()) projectFile.delete()
        projectTemp.renameTo(projectFile)
    }

    fun listProjects(): List<Pair<TimelineProject, ProjectMetadata>> {
        val directories = rootDir.listFiles { file -> file.isDirectory } ?: return emptyList()
        return directories.mapNotNull { directory ->
            loadProject(directory.name)
        }.sortedByDescending { (_, metadata) -> metadata.modifiedDate }
    }

    fun loadProject(projectId: String): Pair<TimelineProject, ProjectMetadata>? {
        val dir = getProjectDir(projectId)
        val metaFile = File(dir, "metadata.json")
        val projectFile = File(dir, "project.json")

        if (!metaFile.exists() || !projectFile.exists()) return null

        return try {
            val metaDataString = metaFile.readText()
            val metadata = json.decodeFromString<ProjectMetadata>(metaDataString)
            
            var projectString = projectFile.readText()
            projectString = ProjectMigration.migrateProjectIfNeeded(projectString, metadata.schemaVersion)
            
            val project = json.decodeFromString<TimelineProject>(projectString)
            Pair(project, metadata)
        } catch (e: Exception) {
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            null
        }
    }

    fun saveVersion(project: TimelineProject, version: ProjectVersion) {
        val dir = getProjectDir(version.projectId)
        val versionsDir = File(dir, "versions")
        versionsDir.mkdirs()
        
        val versionFile = File(versionsDir, "${version.versionId}.json")
        val versionTemp = File(versionsDir, "${version.versionId}.json.tmp")
        versionTemp.writeText(json.encodeToString(project))
        if (versionFile.exists()) versionFile.delete()
        versionTemp.renameTo(versionFile)
        
        val vMetaFile = File(versionsDir, "${version.versionId}_meta.json")
        val vMetaTemp = File(versionsDir, "${version.versionId}_meta.json.tmp")
        vMetaTemp.writeText(json.encodeToString(version))
        if (vMetaFile.exists()) vMetaFile.delete()
        vMetaTemp.renameTo(vMetaFile)
    }

    fun loadVersionContent(projectId: String, versionId: String): TimelineProject? {
        val dir = getProjectDir(projectId)
        val versionsDir = File(dir, "versions")
        val versionFile = File(versionsDir, "$versionId.json")
        if (!versionFile.exists()) return null
        return try {
            val projectString = versionFile.readText()
            json.decodeFromString<TimelineProject>(projectString)
        } catch(e: Exception) {
            if (com.example.BuildConfig.DEBUG) e.printStackTrace()
            null
        }
    }

    fun listVersions(projectId: String): List<ProjectVersion> {
        val dir = getProjectDir(projectId)
        val versionsDir = File(dir, "versions")
        if (!versionsDir.exists()) return emptyList()

        val metaFiles = versionsDir.listFiles { _, name -> name.endsWith("_meta.json") } ?: return emptyList()
        return metaFiles.mapNotNull {
            try {
                json.decodeFromString<ProjectVersion>(it.readText())
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.timestamp }
    }
}
