package com.example.timeline.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.timeline.core.*
import com.example.timeline.core.transform.ClipTransform
import com.example.timeline.export.validation.TimelineExportAutoFixer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID

import android.net.Uri
import androidx.lifecycle.asFlow
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.timeline.media.MediaAsset
import com.example.timeline.media.MediaAnalyzer
import com.example.timeline.media.AndroidMediaAnalyzer
import com.example.timeline.media.ImportWorkManager
import com.example.timeline.media.ProxyGenerationWorker
import com.example.timeline.media.PreviewStatus
import com.example.timeline.media.ProxyStatus
import com.example.timeline.media.MediaErrorStatus
import com.example.timeline.media.ProxyInfo
import com.example.timeline.media.ProxyGenerationRecovery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TimelineViewModel(context: Context) : ViewModel() {
    // Picked files must update the shared project/history serially; parallel analysis used to let
    // two selections each publish from the same stale project snapshot and lose one imported clip.
    private val mediaImportMutex = Mutex()
    private val historyManager = TimelineHistoryManager()
    private val mediaAnalyzer: MediaAnalyzer = AndroidMediaAnalyzer(context)
    private val importWorkManager = ImportWorkManager(context)
    private val workManager = WorkManager.getInstance(context)
        
    private val storage = ProjectStorage(File(context.filesDir, "timeline_projects"))
    private val versionManager = ProjectVersionManager(storage)
    private val _recentProjects = MutableStateFlow<List<Pair<TimelineProject, ProjectMetadata>>>(emptyList())
    val recentProjects: StateFlow<List<Pair<TimelineProject, ProjectMetadata>>> = _recentProjects.asStateFlow()

    private val _proxyProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val proxyProgress: StateFlow<Map<String, Float>> = _proxyProgress.asStateFlow()
    private val activeProxyJobs = mutableMapOf<String, UUID>()
    private val proxyObservationJobs = mutableMapOf<String, Job>()
    private val automaticProxyRequests = mutableSetOf<String>()
    private val cadenceReanalysisJobs = mutableMapOf<String, Job>()
    private val autosaveManager = AutosaveManager(
        scope = viewModelScope,
        versionManager = versionManager,
        storage = storage,
        getMetadata = { p -> 
            ProjectMetadata(
                id = p.id,
                name = p.name,
                createdDate = System.currentTimeMillis(),
                modifiedDate = System.currentTimeMillis(),
                appVersion = "1.0",
                schemaVersion = ProjectMigration.CURRENT_SCHEMA_VERSION
            )
        }
    )

    private val contextRef = context.applicationContext

    private val _uiState = MutableStateFlow(TimelineUiState())
    val uiState: StateFlow<TimelineUiState> = _uiState.asStateFlow()

    private val _playheadFrame = MutableStateFlow(0L)
    val playheadFrame: StateFlow<Long> = _playheadFrame.asStateFlow()
    private var lastPlaybackUiPlayheadPublishMs = -1L

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    fun togglePlay() {
        _isPlaying.value = !_isPlaying.value
    }

    fun pausePlay() {
        _isPlaying.value = false
    }

    fun syncPlayheadFromPlayer(timeMs: Long, clipTimelineStart: Long, clipSourceIn: Long) {
        val project = _uiState.value.project
        val currentSourceFrame = TimelineFrameMath.framesFromMilliseconds(
            timeMs,
            project.settings.getFpsRational()
        ) ?: return
        val relativeFrame = try {
            Math.subtractExact(currentSourceFrame, clipSourceIn)
        } catch (_: ArithmeticException) {
            return
        }
        val timelineFrame = try {
            Math.addExact(clipTimelineStart, relativeFrame)
        } catch (_: ArithmeticException) {
            return
        }
        _playheadFrame.value = timelineFrame.coerceAtLeast(0L)
    }

    fun getActiveVideoClipAndAsset(): Pair<TimelineClip, MediaAsset>? {
        val state = _uiState.value
        val playhead = _playheadFrame.value
        
        // Find topmost video track with a clip at playhead
        for (track in state.project.tracks.filter { it.type == TrackType.VIDEO }.reversed()) {
            val clip = track.clips.find { it.timelineStart <= playhead && it.timelineEnd > playhead }
            if (clip != null) {
                val asset = state.project.mediaAssets.find { it.assetId == clip.mediaId }
                if (asset != null) return Pair(clip, asset)
            }
        }
        return null
    }

    fun markAssetPlaybackFailed(assetId: String) {
        val stateNow = _uiState.value
        val updatedAssets = stateNow.project.mediaAssets.map { m ->
            if (m.assetId == assetId && m.previewStatus == PreviewStatus.NOT_TESTED) {
                m.copy(previewStatus = PreviewStatus.DIRECT_FAILED_RUNTIME)
            } else m
        }
        updateState(stateNow.project.copy(mediaAssets = updatedAssets))
    }

    /** Records a preview failure separately; it does not undo successful proxy generation. */
    fun markAssetProxyPlaybackFailed(assetId: String) {
        val stateNow = _uiState.value
        val updatedAssets = stateNow.project.mediaAssets.map { asset ->
            if (asset.assetId == assetId &&
                asset.proxyStatus == ProxyStatus.READY &&
                asset.proxyPlaybackFailure == null
            ) {
                asset.copy(
                    proxyPlaybackFailure = "Proxy playback failed; the original media will be tried."
                )
            } else asset
        }
        if (updatedAssets != stateNow.project.mediaAssets) {
            updateState(stateNow.project.copy(mediaAssets = updatedAssets))
        }
    }

    fun refreshRecentProjects() {
        viewModelScope.launch(Dispatchers.IO) {
            _recentProjects.value = storage.listProjects().take(20)
        }
    }

    fun openRecentProject(projectId: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = storage.loadProject(projectId)?.first
            withContext(Dispatchers.Main.immediate) {
                if (loaded != null) loadProject(loaded)
                onResult(loaded != null)
            }
        }
    }

    /** Imports a project JSON document, cloning its ID so it never overwrites an existing project. */
    fun importProject(uri: Uri, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val content = contextRef.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("The selected project file could not be opened.")
                val source = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    .decodeFromString<TimelineProject>(content)
                require(source.settings.projectName.isNotBlank()) { "The project file has no project name." }
                val id = UUID.randomUUID().toString()
                source.copy(
                    id = id,
                    settings = source.settings.copy(projectId = id),
                    mediaAssets = source.mediaAssets.map { it.copy(projectId = id) }
                )
            }
            withContext(Dispatchers.Main.immediate) {
                result.onSuccess { imported ->
                    loadProject(imported)
                    refreshRecentProjects()
                    onResult(null)
                }.onFailure { error ->
                    onResult(error.message?.takeIf(String::isNotBlank) ?: "Could not import this project file.")
                }
            }
        }
    }

    fun loadProject(project: TimelineProject) {
        val recoveredProject = project.copy(
            mediaAssets = project.mediaAssets.map { asset ->
                when {
                    ProxyGenerationRecovery.wasInterrupted(asset.proxyStatus, asset.proxyWorkId) -> asset.copy(
                        proxyStatus = ProxyStatus.FAILED,
                        proxyWorkId = null,
                        proxyPlaybackFailure = null,
                        errorMessage = ProxyGenerationRecovery.INTERRUPTED_MESSAGE
                    )
                    asset.proxyStatus != ProxyStatus.GENERATING && asset.proxyWorkId != null ->
                        asset.copy(proxyWorkId = null)
                    else -> asset
                }
            }
        )
        historyManager.clear()
        TimelineCoreV2Adapter.clearHistory(_uiState.value.project.id)
        _playheadFrame.value = 0L
        proxyObservationJobs.values.forEach { it.cancel() }
        proxyObservationJobs.clear()
        activeProxyJobs.clear()
        _proxyProgress.value = emptyMap()
        _uiState.update {
            it.copy(
                project = recoveredProject,
                showProjectSetup = false,
                selectedClipId = null,
                canUndo = false,
                canRedo = false
            )
        }
        autosaveManager.markDirty(recoveredProject)
        recoveredProject.mediaAssets.forEach { asset ->
            ProxyGenerationRecovery.workIdFor(asset.proxyStatus, asset.proxyWorkId)?.let { workId ->
                observeProxyJob(asset, workId)
            }
        }
        reanalyzeUnverifiedVideoCadence(recoveredProject)
    }

    /** Re-probes legacy projects whose schema predates the persisted timestamp-cadence result. */
    private fun reanalyzeUnverifiedVideoCadence(project: TimelineProject) {
        project.mediaAssets
            .filter { it.metadata.hasVideo && !it.metadata.variableFrameRateDetectionComplete }
            .forEach { asset ->
                val key = "${project.id}:${asset.assetId}"
                if (cadenceReanalysisJobs[key]?.isActive == true) return@forEach
                val sourceUriString = asset.localOriginalUriString?.takeIf { it.isNotBlank() }
                    ?: asset.originalUriString.takeIf { it.isNotBlank() }
                    ?: return@forEach
                val sourceUri = runCatching { Uri.parse(sourceUriString) }.getOrNull() ?: return@forEach

                cadenceReanalysisJobs[key] = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val analyzedAsset = mediaAnalyzer.analyze(asset, sourceUri)
                        if (analyzedAsset.metadata.variableFrameRateDetectionComplete) {
                            withContext(Dispatchers.Main.immediate) {
                                val currentProject = _uiState.value.project
                                if (currentProject.id != project.id) return@withContext
                                val updatedAssets = currentProject.mediaAssets.map { current ->
                                    if (current.assetId == asset.assetId) current.copy(
                                        metadata = current.metadata.copy(
                                            isVariableFrameRate = analyzedAsset.metadata.isVariableFrameRate,
                                            variableFrameRateDetectionComplete = true,
                                            estimatedFrameRate = analyzedAsset.metadata.estimatedFrameRate
                                        )
                                    ) else current
                                }
                                updateState(
                                    currentProject.copy(mediaAssets = updatedAssets),
                                    markDirty = false
                                )
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (com.example.BuildConfig.DEBUG) error.printStackTrace()
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main.immediate) {
                            cadenceReanalysisJobs.remove(key)
                        }
                    }
                }
            }
    }

    fun handlePickedUris(uris: List<Uri>) {
        uris.forEach { uri -> 
            enqueueMediaAnalysis(uri) 
        }
    }

    fun enqueueMediaAnalysis(uri: Uri) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            mediaImportMutex.withLock {
                try {
                    val hasPermission = com.example.timeline.media.ImportPermissionManager.persistPermission(contextRef, uri)
                    val projectId = _uiState.value.project.id
                    var initialAsset = com.example.timeline.media.DocumentMediaReader.createAnalyzingAsset(contextRef, uri, projectId)?.copy(
                        persistedUriPermission = hasPermission,
                        previewStatus = PreviewStatus.NOT_TESTED
                    )
                
                    if (initialAsset != null) {
                        val currentProject = _uiState.value.project
                    
                        val isVideo = initialAsset.assetType == com.example.timeline.media.MediaAssetType.VIDEO ||
                            initialAsset.mimeType.startsWith("video/")
                        val prefix = if (isVideo) "Video " else "Audio "
                        val currentCount = currentProject.mediaAssets.count { it.displayName.startsWith(prefix) }
                        val newName = "$prefix${currentCount + 1}"
                    
                        val updatedMetadata = initialAsset.metadata.copy(originalFilename = initialAsset.displayName)
                        initialAsset = initialAsset.copy(displayName = newName, metadata = updatedMetadata)
                    
                    
                        // Prevent duplicate import of same URI at same time
                        if (currentProject.mediaAssets.none { it.originalUriString == uri.toString() && it.mediaErrorStatus == MediaErrorStatus.NONE && it.mediaAvailabilityStatus != com.example.timeline.media.MediaAvailabilityStatus.MISSING_NEEDS_RELINK }) {
                            val p2 = currentProject.copy(mediaAssets = currentProject.mediaAssets + initialAsset)
                            updateState(p2, markDirty = false)
                        
                            val localPath = copyMediaToLocalStore(
                                uri,
                                initialAsset.assetId,
                                initialAsset.displayName,
                                projectId,
                                initialAsset.metadata.originalFilename
                            )
                        
                            initialAsset = initialAsset.copy(
                                localOriginalUriString = localPath,
                                mediaAvailabilityStatus = if (localPath != null) com.example.timeline.media.MediaAvailabilityStatus.AVAILABLE_LOCAL_COPY else com.example.timeline.media.MediaAvailabilityStatus.AVAILABLE_ORIGINAL_URI,
                                previewStatus = PreviewStatus.NOT_TESTED
                            )
                            updateState(_uiState.value.project.copy(mediaAssets = _uiState.value.project.mediaAssets.map { if (it.assetId == initialAsset.assetId) initialAsset else it }), markDirty = false)

                            val uriToAnalyze = if (localPath != null) Uri.parse(localPath) else uri
                            val finalAsset = mediaAnalyzer.analyze(initialAsset, uriToAnalyze)
                            val stateNow = _uiState.value
                        
                            val updatedList = stateNow.project.mediaAssets.map { if (it.assetId == finalAsset.assetId) finalAsset else it }
                            val pFinal = stateNow.project.copy(mediaAssets = updatedList)
                        
                            val cmd = SnapshotCommand("Import Media", currentProject, pFinal, emptyList())
                            historyManager.pushCommand(cmd)
                            updateState(pFinal)
                        
                            if (finalAsset.mediaErrorStatus == MediaErrorStatus.NONE && finalAsset.mediaAvailabilityStatus != com.example.timeline.media.MediaAvailabilityStatus.MISSING_NEEDS_RELINK) {
                                addClipFromMedia(finalAsset)
                            }
                        
                            if (finalAsset.proxyStatus == ProxyStatus.REQUIRED) {
                                generateProxy(finalAsset.assetId)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                }
            }
        }
    }
    
    private suspend fun copyMediaToLocalStore(
        uri: Uri,
        assetId: String,
        displayName: String,
        projectId: String,
        sourceFileName: String? = null
    ): String? {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var outputFile: File? = null
            try {
                val mediaDir = File(contextRef.filesDir, "timeline_projects/$projectId/media/originals")
                if (!mediaDir.exists() && !mediaDir.mkdirs()) return@withContext null

                val mimeType = contextRef.contentResolver.getType(uri)
                val extension = android.webkit.MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mimeType)
                    ?.lowercase()
                    ?.takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
                    ?: (sourceFileName ?: displayName).substringAfterLast('.', "")
                        .lowercase()
                        .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
                    ?: "bin"
                val safeName = displayName.replace(Regex("[^a-zA-Z0-9.-]"), "_")
                val targetFile = File(mediaDir, "${assetId}_${safeName}.$extension")
                outputFile = targetFile

                if (!targetFile.isFile || targetFile.length() <= 0L) {
                    targetFile.delete()
                    val input = contextRef.contentResolver.openInputStream(uri)
                        ?: return@withContext null
                    input.use { source ->
                        targetFile.outputStream().use { destination -> source.copyTo(destination) }
                    }
                }

                if (!targetFile.isFile || targetFile.length() <= 0L) {
                    targetFile.delete()
                    return@withContext null
                }
                "file://${targetFile.absolutePath}"
            } catch (e: Exception) {
                outputFile?.delete()
                if (com.example.BuildConfig.DEBUG) e.printStackTrace()
                null
            }
        }
    }
    
    private fun proxyDimensions(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) return sourceWidth to sourceHeight
        val maxSide = 1280
        if (sourceWidth <= maxSide && sourceHeight <= maxSide) return sourceWidth to sourceHeight

        return if (sourceWidth >= sourceHeight) {
            val scaledHeight = (sourceHeight.toFloat() / sourceWidth * maxSide).toInt().let { height ->
                if (height % 2 == 0) height else height - 1
            }.coerceAtLeast(2)
            maxSide to scaledHeight
        } else {
            val scaledWidth = (sourceWidth.toFloat() / sourceHeight * maxSide).toInt().let { width ->
                if (width % 2 == 0) width else width - 1
            }.coerceAtLeast(2)
            scaledWidth to maxSide
        }
    }

    fun generateProxyForAsset(asset: MediaAsset) {
        val request = try {
            importWorkManager.createProxyRequest(asset)
        } catch (error: Exception) {
            markProxyGenerationFailed(asset.assetId, null, error.message ?: "Could not prepare proxy generation.")
            return
        }
        val workId = request.id

        // Record the WorkManager id in project state before enqueue. On reload, this lets the
        // ViewModel reconnect to the request instead of relying on a live-screen observer.
        val currentProject = _uiState.value.project
        val withWorkId = currentProject.copy(
            mediaAssets = currentProject.mediaAssets.map { current ->
                if (current.assetId == asset.assetId && current.proxyStatus == ProxyStatus.GENERATING) {
                    current.copy(proxyWorkId = workId.toString())
                } else current
            }
        )
        updateState(withWorkId)

        try {
            importWorkManager.enqueueProxyRequest(request)
        } catch (error: Exception) {
            markProxyGenerationFailed(
                asset.assetId,
                workId,
                error.message ?: "Could not enqueue proxy generation."
            )
            return
        }
        observeProxyJob(asset, workId)
    }

    private fun observeProxyJob(asset: MediaAsset, workId: UUID) {
        proxyObservationJobs.remove(asset.assetId)?.cancel()
        activeProxyJobs[asset.assetId] = workId
        _proxyProgress.value = _proxyProgress.value.toMutableMap().apply {
            if (!containsKey(asset.assetId)) this[asset.assetId] = 0f
        }

        val observerJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                workManager.getWorkInfoByIdFlow(workId).collect { workInfo ->
                    val isTerminal = workInfo?.state == WorkInfo.State.SUCCEEDED ||
                        workInfo?.state == WorkInfo.State.FAILED ||
                        workInfo?.state == WorkInfo.State.CANCELLED
                    // A delayed observation from an older request must never overwrite the state of a
                    // newer retry for the same asset.
                    if (activeProxyJobs[asset.assetId] != workId) {
                        if (isTerminal) kotlinx.coroutines.currentCoroutineContext().cancel()
                        return@collect
                    }

                    if (workInfo == null) {
                        markProxyGenerationFailed(
                            asset.assetId,
                            workId,
                            ProxyGenerationRecovery.MISSING_WORK_MESSAGE
                        )
                        finishProxyObservation(asset.assetId, workId)
                        kotlinx.coroutines.currentCoroutineContext().cancel()
                        return@collect
                    }

                    val progress = workInfo.progress.getInt(ProxyGenerationWorker.KEY_PROGRESS, 0)
                    _proxyProgress.value = _proxyProgress.value.toMutableMap().apply {
                        this[asset.assetId] = progress.coerceIn(0, 100) / 100f
                    }

                    val stateNow = _uiState.value
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val outputUri = workInfo.outputData.getString(ProxyGenerationWorker.KEY_OUTPUT_URI)
                            val outputFile = outputUri?.let { uriString ->
                                runCatching { Uri.parse(uriString) }.getOrNull()
                                    ?.takeIf { it.scheme == "file" }
                                    ?.path
                                    ?.let(::File)
                                    ?.takeIf { it.isFile && it.length() > 0L }
                            }
                            val updatedAssets = stateNow.project.mediaAssets.map { current ->
                                if (current.assetId != asset.assetId) current
                                else if (outputUri == null || outputFile == null) current.copy(
                                    proxyStatus = ProxyStatus.FAILED,
                                    proxyWorkId = null,
                                    proxyPlaybackFailure = null,
                                    errorMessage = "Proxy worker reported success, but its output file is missing or empty."
                                ) else {
                                    val (proxyWidth, proxyHeight) = proxyDimensions(current.metadata.width, current.metadata.height)
                                    current.copy(
                                        proxyStatus = ProxyStatus.READY,
                                        proxyWorkId = null,
                                        proxyPlaybackFailure = null,
                                        errorMessage = null,
                                        proxyInfo = ProxyInfo(
                                            uriString = outputUri,
                                            width = proxyWidth,
                                            height = proxyHeight,
                                            frameRate = current.metadata.estimatedFrameRate?.fpsAsFloat ?: 30f,
                                            isProxyGenerated = true,
                                            proxyFormat = "mp4",
                                            proxyMimeType = "video/mp4"
                                        )
                                    )
                                }
                            }
                            updateState(stateNow.project.copy(mediaAssets = updatedAssets))
                        }
                        WorkInfo.State.FAILED -> {
                            val errorMsg = workInfo.outputData.getString(ProxyGenerationWorker.KEY_ERROR_MSG)
                                ?: workInfo.progress.getString(ProxyGenerationWorker.KEY_ERROR_MSG)
                                ?: "Proxy generation failed."
                            val updatedAssets = stateNow.project.mediaAssets.map { current ->
                                if (current.assetId == asset.assetId) current.copy(
                                    proxyStatus = ProxyStatus.FAILED,
                                    proxyWorkId = null,
                                    proxyPlaybackFailure = null,
                                    errorMessage = errorMsg
                                ) else current
                            }
                            updateState(stateNow.project.copy(mediaAssets = updatedAssets))
                        }
                        WorkInfo.State.CANCELLED -> {
                            val restoreStatus = asset.proxyStatus.takeUnless { it == ProxyStatus.GENERATING }
                                ?: if (stateNow.project.mediaAssets.firstOrNull { it.assetId == asset.assetId }?.proxyInfo != null) {
                                    ProxyStatus.READY
                                } else {
                                    ProxyStatus.NONE
                                }
                            val updatedAssets = stateNow.project.mediaAssets.map { current ->
                                if (current.assetId == asset.assetId) current.copy(
                                    proxyStatus = restoreStatus,
                                    proxyWorkId = null,
                                    proxyPlaybackFailure = asset.proxyPlaybackFailure,
                                    errorMessage = asset.errorMessage
                                ) else current
                            }
                            updateState(stateNow.project.copy(mediaAssets = updatedAssets))
                        }
                        else -> Unit
                    }

                    if (isTerminal) {
                        finishProxyObservation(asset.assetId, workId)
                        kotlinx.coroutines.currentCoroutineContext().cancel()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                markProxyGenerationFailed(
                    asset.assetId,
                    workId,
                    error.message ?: "Could not observe proxy generation."
                )
                finishProxyObservation(asset.assetId, workId)
            }
        }
        proxyObservationJobs[asset.assetId] = observerJob
        observerJob.start()
    }

    private fun markProxyGenerationFailed(assetId: String, workId: UUID?, message: String) {
        val stateNow = _uiState.value
        val workIdString = workId?.toString()
        val updatedAssets = stateNow.project.mediaAssets.map { current ->
            val belongsToRequest = workIdString == null || current.proxyWorkId == workIdString
            if (current.assetId == assetId && current.proxyStatus == ProxyStatus.GENERATING && belongsToRequest) {
                current.copy(
                    proxyStatus = ProxyStatus.FAILED,
                    proxyWorkId = null,
                    proxyPlaybackFailure = null,
                    errorMessage = message.take(700)
                )
            } else current
        }
        if (updatedAssets != stateNow.project.mediaAssets) {
            updateState(stateNow.project.copy(mediaAssets = updatedAssets))
        }
    }

    private fun finishProxyObservation(assetId: String, workId: UUID) {
        if (activeProxyJobs[assetId] == workId) {
            activeProxyJobs.remove(assetId)
            proxyObservationJobs.remove(assetId)
            _proxyProgress.value = _proxyProgress.value - assetId
        }
    }
    
    fun retryMediaAsset(assetId: String) {
        val asset = _uiState.value.project.mediaAssets.find { it.assetId == assetId } ?: return
        
        var isMissing = true
        var targetUriToAnalyze: Uri? = null
        if (asset.localOriginalUriString != null) {
            val file = java.io.File(Uri.parse(asset.localOriginalUriString).path ?: "")
            if (file.exists() && file.length() > 0) {
                isMissing = false
                targetUriToAnalyze = Uri.parse(asset.localOriginalUriString)
            }
        }
        
        if (isMissing) {
            val uri = Uri.parse(asset.originalUriString)
            if (uri.scheme == "file") {
                 if (java.io.File(uri.path ?: "").exists()) {
                     isMissing = false
                     targetUriToAnalyze = uri
                 }
            } else {
                 try {
                     val cursor = contextRef.contentResolver.query(uri, null, null, null, null)
                     if (cursor != null && cursor.moveToFirst()) {
                         isMissing = false
                         targetUriToAnalyze = uri
                     }
                     cursor?.close()
                 } catch (e: Exception) {}
            }
        }
        
        if (isMissing) {
            val pFinal = _uiState.value.project.copy(
                mediaAssets = _uiState.value.project.mediaAssets.map { 
                    if (it.assetId == assetId) it.copy(mediaAvailabilityStatus = com.example.timeline.media.MediaAvailabilityStatus.MISSING_NEEDS_RELINK, errorMessage = "Media file is missing or unreadable.")
                    else it 
                }
            )
            updateState(pFinal)
            return
        }
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val pFinal = _uiState.value.project.copy(
                mediaAssets = _uiState.value.project.mediaAssets.map {
                    if (it.assetId == assetId) it.copy(previewStatus = PreviewStatus.NOT_TESTED) else it
                }
            )
            updateState(pFinal, markDirty = false)
            
            val finalAsset = mediaAnalyzer.analyze(asset, targetUriToAnalyze!!)
            val stateNow = _uiState.value
            updateState(stateNow.project.copy(mediaAssets = stateNow.project.mediaAssets.map { if (it.assetId == assetId) finalAsset else it }))
            
            if (finalAsset.proxyStatus == ProxyStatus.REQUIRED || finalAsset.proxyStatus == ProxyStatus.RECOMMENDED) {
                // generateProxyForAsset(finalAsset)
                // Optional to auto-gen here. Let's just track status.
            }
        }
    }
    
    /** Auto-proxy requests are one-shot so cancelling a recommendation does not restart it. */
    fun generateRecommendedProxy(assetId: String) {
        val projectId = _uiState.value.project.id
        if (automaticProxyRequests.add("$projectId:$assetId")) generateProxy(assetId)
    }

    fun generateProxy(assetId: String) {
        val asset = _uiState.value.project.mediaAssets.find { it.assetId == assetId } ?: return
        if (asset.proxyStatus == ProxyStatus.GENERATING ||
            (asset.proxyStatus == ProxyStatus.READY && asset.proxyPlaybackFailure == null)
        ) return
        val pFinal = _uiState.value.project.copy(
            mediaAssets = _uiState.value.project.mediaAssets.map {
                if (it.assetId == assetId) it.copy(
                    proxyStatus = ProxyStatus.GENERATING,
                    proxyWorkId = null,
                    proxyPlaybackFailure = null
                ) else it
            }
        )
        updateState(pFinal)
        generateProxyForAsset(asset)
    }
    
    fun cancelProxy(assetId: String) {
        activeProxyJobs[assetId]?.let { workId ->
            workManager.cancelWorkById(workId)
        }
    }
    
    fun addClipFromMedia(mediaAsset: MediaAsset) {
        addClipFromMediaWithMode(mediaAsset, "active")
    }

    fun addClipFromMediaWithMode(mediaAsset: MediaAsset, mode: String) {
        val state = _uiState.value
        val selectedClip = state.project.tracks.asSequence()
            .flatMap { it.clips.asSequence() }
            .firstOrNull { it.id == state.selectedClipId }
        val selectedTrackId = selectedClip?.trackId ?: state.project.tracks.firstOrNull()?.id
        val plan = com.example.timeline.engine.edit.AddToTimelineController.createClipInsertionPlan(
            project = state.project,
            mediaAsset = mediaAsset,
            playheadFrame = _playheadFrame.value,
            mode = mode,
            selectedTrackId = selectedTrackId
        ) ?: return

        // Position a newly-created empty track for explicit above/below insertion modes.
        var baseProject = plan.baseProject
        if ((mode == "above" || mode == "below") && selectedTrackId != null) {
            val tracks = baseProject.tracks.toMutableList()
            val newTrack = tracks.firstOrNull { it.id == plan.clip.trackId } ?: return
            tracks.remove(newTrack)
            val targetIndex = tracks.indexOfFirst { it.id == selectedTrackId }
            val insertIndex = if (targetIndex == -1) tracks.size
                else if (mode == "above") targetIndex else targetIndex + 1
            tracks.add(insertIndex.coerceIn(0, tracks.size), newTrack)
            baseProject = baseProject.copy(tracks = tracks)
        }

        val baseChanged = !TimelineCoreV2Adapter.hasSameNativeState(state.project, baseProject)
        val outcome = commitNativeTimelineEdits(
            projectForEdit = baseProject,
            commands = listOf(plan.command),
            commandName = "Add Clip",
            affectedClipIds = listOf(plan.clip.id),
            clearHistoryForBaseChange = baseChanged
        )
        if (outcome is CoreV2EditOutcome.Applied && outcome.project != baseProject) {
            _playheadFrame.value = plan.clip.timelineStart
            _uiState.update {
                it.copy(
                    selectedClipId = plan.clip.id,
                    selectedClipIds = setOf(plan.clip.id)
                )
            }
        }
    }

    private fun updateState(newProject: TimelineProject, markDirty: Boolean = true) {
        val previousProject = _uiState.value.project
        if (previousProject.id != newProject.id) {
            TimelineCoreV2Adapter.clearHistory(previousProject.id)
            historyManager.clear()
        } else if (historyManager.containsNativeTimelineCommands() &&
            !TimelineCoreV2Adapter.hasSameNativeState(previousProject, newProject)
        ) {
            // A non-native operation changed fields represented in the native project protocol.
            // Invalidate only the native journal entries; metadata-only snapshot commands remain valid.
            TimelineCoreV2Adapter.clearHistory(newProject.id)
            historyManager.clearNativeTimelineCommands()
        }
        publishProjectState(newProject, markDirty)
    }

    private fun publishNativeProjectState(newProject: TimelineProject, markDirty: Boolean = true) {
        publishProjectState(newProject, markDirty)
    }

    private fun publishProjectState(newProject: TimelineProject, markDirty: Boolean) {
        _uiState.update {
            it.copy(
                project = newProject,
                canUndo = historyManager.canUndo,
                canRedo = historyManager.canRedo
            )
        }
        if (markDirty) autosaveManager.markDirty(newProject)
    }

    /** Applies one validated native transaction and records it only in the native journal. */
    private fun commitNativeTimelineEdits(
        projectForEdit: TimelineProject,
        commands: List<CoreV2EditCommand>,
        commandName: String,
        affectedClipIds: List<String>,
        clearHistoryForBaseChange: Boolean = false
    ): CoreV2EditOutcome? {
        val currentProject = _uiState.value.project
        val mustResetHistoryAfterCommit = clearHistoryForBaseChange ||
            !TimelineCoreV2Adapter.hasSameNativeState(currentProject, projectForEdit)

        return when (val outcome = TimelineCoreV2Adapter.commitEdits(projectForEdit, commands)) {
            is CoreV2EditOutcome.Applied -> {
                // Native commit starts a fresh journal when its base hash changed. Mirror that
                // transition in the Kotlin UI stack only after the edit has succeeded.
                if (mustResetHistoryAfterCommit) historyManager.clearNativeTimelineCommands()
                if (outcome.project != projectForEdit) {
                    historyManager.pushCommand(
                        NativeTimelineHistoryCommand(
                            commandName = commandName,
                            affectedClipIds = outcome.changedClipIds.ifEmpty { affectedClipIds }.distinct()
                        )
                    )
                    publishNativeProjectState(outcome.project)
                }
                outcome
            }
            is CoreV2EditOutcome.Rejected -> outcome
        }
    }
    
    /** Publishes a sampled position to non-viewport panels while the player itself stays frame-accurate. */
    fun setPlaybackPlayheadFrame(frame: Long) {
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (shouldPublishPlaybackPlayhead(lastPlaybackUiPlayheadPublishMs, nowMs)) {
            lastPlaybackUiPlayheadPublishMs = nowMs
            _playheadFrame.value = frame.coerceAtLeast(0L)
        }
    }

    fun setPlayheadFrame(frame: Long) {
        lastPlaybackUiPlayheadPublishMs = -1L
        _playheadFrame.value = frame
    }
    
    fun updateClipTransform(clipId: String, transform: com.example.timeline.core.transform.ClipTransform) {
        val state = _uiState.value
        val newProject = state.project.copy(
            tracks = state.project.tracks.map { track ->
                track.copy(clips = track.clips.map { clip ->
                    if (clip.id == clipId) clip.copy(transform = transform) else clip
                })
            }
        )
        val cmd = SnapshotCommand("Transform Clip", state.project, newProject, listOf(clipId))
        historyManager.pushCommand(cmd)
        updateState(newProject)
    }
    
    fun updateClipAdjustments(clipId: String, adjustments: com.example.model.adjustments.AdjustmentStack) {
        val state = _uiState.value
        val newProject = state.project.copy(
            tracks = state.project.tracks.map { track ->
                track.copy(clips = track.clips.map { clip ->
                    if (clip.id == clipId) clip.copy(adjustments = adjustments) else clip
                })
            }
        )
        val cmd = SnapshotCommand("Adjust Clip", state.project, newProject, listOf(clipId))
        historyManager.pushCommand(cmd)
        updateState(newProject)
    }


    fun selectClip(clipId: String?) {
        _uiState.update { 
            it.copy(
                selectedClipId = clipId,
                selectedClipIds = clipId?.let { setOf(it) } ?: emptySet()
            ) 
        }
    }

    fun updateSelection(clipIds: Set<String>) {
        _uiState.update { 
            it.copy(
                selectedClipId = clipIds.firstOrNull(),
                selectedClipIds = clipIds
            ) 
        }
    }

    private fun nativeCommandsFor(
        command: TimelineCommand,
        project: TimelineProject
    ): List<CoreV2EditCommand>? = when (command) {
        is TrimCommand -> listOf(
            CoreV2EditCommand(
                kind = if (command.isStart) CoreV2EditKind.TRIM_START else CoreV2EditKind.TRIM_END,
                clipId = command.clipId,
                targetFrame = command.newFrame,
                ripple = when (command.mode) {
                    TrimMode.NORMAL -> CoreV2RippleScope.NONE
                    TrimMode.RIPPLE -> CoreV2RippleScope.SAME_TRACK
                    TrimMode.RIPPLE_ALL_TRACKS -> CoreV2RippleScope.ALL_TRACKS
                }
            )
        )
        is RippleTrimCommand -> listOf(
            CoreV2EditCommand(
                kind = if (command.isStart) CoreV2EditKind.TRIM_START else CoreV2EditKind.TRIM_END,
                clipId = command.clipId,
                targetFrame = command.newFrame,
                ripple = if (command.rippleAllTracks) CoreV2RippleScope.ALL_TRACKS
                    else CoreV2RippleScope.SAME_TRACK
            )
        )
        is SlipCommand -> listOf(
            CoreV2EditCommand(
                kind = CoreV2EditKind.SLIP,
                clipId = command.clipId,
                deltaFrames = command.deltaFrames
            )
        )
        is SlideCommand -> listOf(
            CoreV2EditCommand(
                kind = CoreV2EditKind.SLIDE,
                clipId = command.clipId,
                deltaFrames = command.deltaFrames
            )
        )
        is RollEditCommand -> listOf(
            CoreV2EditCommand(
                kind = CoreV2EditKind.ROLL,
                clipId = command.leftClipId,
                otherClipId = command.rightClipId,
                deltaFrames = command.deltaFrames
            )
        )
        is RippleDeleteCommand -> listOf(
            CoreV2EditCommand(
                kind = CoreV2EditKind.DELETE,
                clipIds = command.clipIds,
                ripple = if (command.rippleAllTracks) CoreV2RippleScope.ALL_TRACKS
                    else CoreV2RippleScope.SAME_TRACK
            )
        )
        is MoveClipCommand -> listOf(
            CoreV2EditCommand(
                kind = CoreV2EditKind.MOVE,
                clipId = command.clipId,
                targetTrackId = command.trackId,
                targetFrame = command.newStartFrame
            )
        )
        is InsertClipCommand -> listOf(
            CoreV2EditCommand(
                kind = CoreV2EditKind.INSERT,
                targetTrackId = command.trackId,
                insertedClip = command.clip.copy(trackId = command.trackId)
            )
        )
        is SplitClipCommand -> command.nativeEditCommands(project)
        else -> null
    }

    fun executeCommand(command: TimelineCommand) {
        val project = _uiState.value.project
        val nativeCommands = nativeCommandsFor(command, project)
        if (nativeCommands != null) {
            if (nativeCommands.isNotEmpty()) {
                commitNativeTimelineEdits(
                    projectForEdit = project,
                    commands = nativeCommands,
                    commandName = command.commandName,
                    affectedClipIds = command.affectedClipIds
                )
            }
            return
        }

        val result = command.execute(project)
        if (result.isSuccess && TimelineCoreV2Adapter.hasSameNativeState(project, result.project)) {
            historyManager.pushCommand(command)
            updateState(result.project)
        }
    }

    /** Commits the same command batch that produced the visible native drag preview. */
    fun commitTimelineEdit(commands: List<CoreV2EditCommand>, commandName: String = "Timeline Edit") {
        if (commands.isEmpty()) return
        val project = _uiState.value.project
        val affectedIds = commands.flatMap { command ->
            command.clipIds + listOfNotNull(command.clipId, command.otherClipId, command.insertedClip?.id)
        }.distinct()
        commitNativeTimelineEdits(project, commands, commandName, affectedIds)
    }

    /** Applies the safe, export-specific timeline repair as one native undoable transaction. */
    fun fixTimelineForExport(): Boolean {
        val project = _uiState.value.project
        val commands = TimelineExportAutoFixer.compactVisibleVideoTrackCommands(project) ?: return false
        val affectedIds = commands.flatMap { edit ->
            edit.clipIds + listOfNotNull(edit.clipId, edit.otherClipId)
        }.distinct()
        val outcome = commitNativeTimelineEdits(
            projectForEdit = project,
            commands = commands,
            commandName = "Fix Timeline for Export",
            affectedClipIds = affectedIds
        )
        return outcome is CoreV2EditOutcome.Applied && outcome.project != project
    }

    fun zoomIn() {
        setZoom(_uiState.value.pixelsPerFrame * 1.5f)
    }

    fun zoomOut() {
        setZoom(_uiState.value.pixelsPerFrame / 1.5f)
    }

    fun relinkMedia(assetId: String, uri: android.net.Uri) {
        val state = _uiState.value
        val asset = state.project.mediaAssets.find { it.assetId == assetId } ?: return
        
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val copied = copyMediaToLocalStore(
                uri,
                assetId,
                asset.displayName,
                state.project.id,
                asset.metadata.originalFilename
            )
            if (copied != null) {
                val updatedAsset = asset.copy(
                    localOriginalUriString = copied,
                    mediaAvailabilityStatus = com.example.timeline.media.MediaAvailabilityStatus.AVAILABLE_LOCAL_COPY
                )
                val updatedAssets = state.project.mediaAssets.map { if (it.assetId == assetId) updatedAsset else it }
                updateState(state.project.copy(mediaAssets = updatedAssets))
                enqueueMediaAnalysis(android.net.Uri.parse(copied))
            }
        }
    }

    fun deleteSelectedClip(ripple: Boolean = false, linkedSelectionEnabled: Boolean = false) {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: return
        
        val controller = LinkedSelectionController()
        val clipsToDelete = if (linkedSelectionEnabled) controller.getLinkedClips(clipId, state.project, true) else setOf(clipId)
        
        if (clipsToDelete.isEmpty()) return
        val ids = clipsToDelete.sorted()
        val outcome = commitNativeTimelineEdits(
            projectForEdit = state.project,
            commands = listOf(
                CoreV2EditCommand(
                    kind = CoreV2EditKind.DELETE,
                    clipIds = ids,
                    ripple = if (ripple) CoreV2RippleScope.SAME_TRACK else CoreV2RippleScope.NONE
                )
            ),
            commandName = if (ripple) "Ripple Delete" else "Delete Clip",
            affectedClipIds = ids
        )
        if (outcome is CoreV2EditOutcome.Applied && outcome.project != state.project) {
            selectClip(null)
        }
    }
    
    fun addMarkerAtPlayhead() {
        val state = _uiState.value
        val marker = TimelineMarker(frame = _playheadFrame.value, name = "Marker")
        val p2 = CoreMarkerEngine.addProjectMarker(state.project, marker)
        val cmd = SnapshotCommand("Add Marker", state.project, p2, emptyList())
        historyManager.pushCommand(cmd)
        updateState(p2)
    }

    fun splitAtPlayhead() {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: return
        val command = SplitClipCommand(clipId, _playheadFrame.value, splitLinkedAudio = false)
        val commands = command.nativeEditCommands(state.project)
        if (commands.isEmpty()) return
        val outcome = commitNativeTimelineEdits(
            projectForEdit = state.project,
            commands = commands,
            commandName = command.commandName,
            affectedClipIds = commands.flatMap { edit ->
                edit.clipIds + listOfNotNull(edit.clipId, edit.otherClipId)
            }.distinct()
        )
        if (outcome is CoreV2EditOutcome.Applied && outcome.project != state.project) selectClip(null)
    }

    /** Splits the selected video at the playhead and inserts a two-second still, rippling later clips. */
    suspend fun splitAndInsertFreezeFrame(frameBitmap: android.graphics.Bitmap): Boolean {
        val initialState = _uiState.value
        val selectedClipId = initialState.selectedClipId ?: return false
        val selected = findClipAndTrack(initialState.project, selectedClipId) ?: return false
        val track = selected.first
        val clip = selected.second
        val frame = _playheadFrame.value
        if (track.type != TrackType.VIDEO || track.isLocked || clip.isLocked || !clip.isEnabled ||
            frame <= clip.timelineStart || frame >= clip.timelineEnd
        ) return false

        val frameRate = initialState.project.settings.getFpsRational()
        val freezeDurationFrames = TimelineFrameMath.framesFromMilliseconds(2_000L, frameRate)
            ?.takeIf { it > 0L } ?: return false
        try {
            Math.addExact(frame, freezeDurationFrames)
        } catch (_: ArithmeticException) {
            return false
        }
        val freezeDurationUs = TimelineFrameMath.microsecondsFromFrames(freezeDurationFrames, frameRate)
            ?.takeIf { it > 0L } ?: return false
        val freezeId = UUID.randomUUID().toString()
        val assetId = UUID.randomUUID().toString()
        val freezeFile = File(contextRef.filesDir, "freeze_frames/${initialState.project.id}/$freezeId.png")
        val sourceWidth = frameBitmap.width.coerceAtLeast(1)
        val sourceHeight = frameBitmap.height.coerceAtLeast(1)
        val scale = minOf(1f, 1920f / maxOf(sourceWidth, sourceHeight).toFloat())
        val freezeWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
        val freezeHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
        val storedBitmap = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(
                frameBitmap,
                freezeWidth,
                freezeHeight,
                true
            )
        } else frameBitmap

        val fileCreated = withContext(Dispatchers.IO) {
            runCatching {
                freezeFile.parentFile?.mkdirs()
                freezeFile.outputStream().use { output ->
                    check(storedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                }
                freezeFile.isFile && freezeFile.length() > 0L
            }.getOrDefault(false)
        }
        if (storedBitmap !== frameBitmap) storedBitmap.recycle()
        if (!fileCreated) {
            freezeFile.delete()
            return false
        }

        try {
            val currentState = _uiState.value
            if (currentState.project.id != initialState.project.id ||
                currentState.selectedClipId != selectedClipId || _playheadFrame.value != frame
            ) return false

            val freezeAsset = com.example.timeline.media.MediaAsset(
                assetId = assetId,
                projectId = initialState.project.id,
                displayName = "Freeze frame.png",
                originalUriString = Uri.fromFile(freezeFile).toString(),
                persistedUriPermission = false,
                mimeType = "image/png",
                fileSizeBytes = freezeFile.length(),
                importedAt = System.currentTimeMillis(),
                modifiedAt = System.currentTimeMillis(),
                assetType = com.example.timeline.media.MediaAssetType.IMAGE,
                metadata = com.example.timeline.media.MediaMetadata(
                    durationUs = freezeDurationUs,
                    durationFramesInSourceRate = freezeDurationFrames,
                    containerFormat = "PNG",
                    bitrate = 0L,
                    width = freezeWidth,
                    height = freezeHeight,
                    rotationDegrees = 0,
                    pixelAspectRatio = 1f,
                    videoStreams = emptyList(),
                    audioStreams = emptyList(),
                    hasVideo = false,
                    hasAudio = false,
                    isVariableFrameRate = false,
                    estimatedFrameRate = null,
                    exactFrameRate = null,
                    timecodeStart = null,
                    creationTime = System.currentTimeMillis(),
                    variableFrameRateDetectionComplete = true
                )
            )
            val freezeClip = TimelineClip(
                id = freezeId,
                trackId = track.id,
                mediaId = assetId,
                name = "Freeze frame",
                sourceIn = 0L,
                sourceOut = freezeDurationFrames,
                timelineStart = frame
            )
            val splitCommands = SplitClipCommand(
                clipId = clip.id,
                splitFrame = frame,
                splitLinkedAudio = true
            ).nativeEditCommands(currentState.project)
            if (splitCommands.isEmpty()) return false
            val commands = splitCommands + CoreV2EditCommand(
                kind = CoreV2EditKind.INSERT,
                targetTrackId = track.id,
                insertedClip = freezeClip,
                ripple = CoreV2RippleScope.ALL_TRACKS
            )
            val projectWithFreezeAsset = currentState.project.copy(
                mediaAssets = currentState.project.mediaAssets + freezeAsset
            )
            val outcome = commitNativeTimelineEdits(
                projectForEdit = projectWithFreezeAsset,
                commands = commands,
                commandName = "Split and Insert Freeze Frame",
                affectedClipIds = listOf(clip.id, freezeId)
            )
            if (outcome !is CoreV2EditOutcome.Applied || outcome.project == currentState.project) return false
            _playheadFrame.value = frame
            _uiState.update { it.copy(selectedClipId = freezeId, selectedClipIds = setOf(freezeId)) }
            return true
        } finally {
            if (_uiState.value.project.mediaAssets.none { it.assetId == assetId }) freezeFile.delete()
        }
    }

    /** Reorder a clip to the before/after position indicated by the drop frame. */
    fun reorderClipAtFrame(clipId: String, dropFrame: Long) {
        val commands = TimelineClipReorder.commands(_uiState.value.project, clipId, dropFrame)
        if (commands.isNotEmpty()) commitTimelineEdit(commands, "Reorder Clip")
    }

    fun moveSelectedClip(newStartFrame: Long) {
        val clipId = _uiState.value.selectedClipId ?: return
        moveClip(clipId, newStartFrame)
    }

    fun moveClip(clipId: String, newStartFrame: Long) =
        moveClipGroup(clipId, newStartFrame, setOf(clipId))

    fun moveClipGroup(clipId: String, newStartFrame: Long, clipIds: Set<String>) {
        val project = _uiState.value.project
        val clipAndTrack = findClipAndTrack(project, clipId) ?: return
        commitNativeTimelineEdits(
            projectForEdit = project,
            commands = listOf(
                CoreV2EditCommand(
                    kind = CoreV2EditKind.MOVE,
                    clipId = clipId,
                    targetTrackId = clipAndTrack.first.id,
                    targetFrame = newStartFrame,
                    clipIds = (clipIds + clipId).sorted()
                )
            ),
            commandName = if (clipIds.size > 1) "Move Linked Clips" else "Move Clip",
            affectedClipIds = (clipIds + clipId).sorted()
        )
    }

    fun trimClipStart(clipId: String, newStartFrame: Long, linkedSelectionEnabled: Boolean = false) {
        commitLinkedTrim(
            clipId = clipId,
            targetFrame = newStartFrame,
            trimStart = true,
            linkedSelectionEnabled = linkedSelectionEnabled
        )
    }

    fun trimSelectedClipStart(newStartFrame: Long) {
        val cid = _uiState.value.selectedClipId ?: return
        trimClipStart(cid, newStartFrame)
    }

    fun trimClipEnd(clipId: String, newEndFrame: Long, linkedSelectionEnabled: Boolean = false) {
        commitLinkedTrim(
            clipId = clipId,
            targetFrame = newEndFrame,
            trimStart = false,
            linkedSelectionEnabled = linkedSelectionEnabled
        )
    }

    private fun commitLinkedTrim(
        clipId: String,
        targetFrame: Long,
        trimStart: Boolean,
        linkedSelectionEnabled: Boolean
    ) {
        val project = _uiState.value.project
        val primary = findClipAndTrack(project, clipId)?.second ?: return
        val primaryEdge = if (trimStart) primary.timelineStart else try {
            Math.addExact(primary.timelineStart, primary.duration)
        } catch (_: ArithmeticException) {
            return
        }
        val delta = try {
            Math.subtractExact(targetFrame, primaryEdge)
        } catch (_: ArithmeticException) {
            return
        }
        val clipIds = if (linkedSelectionEnabled) {
            LinkedSelectionController().getLinkedClips(clipId, project, true)
        } else setOf(clipId)
        val commands = mutableListOf<CoreV2EditCommand>()
        for (id in clipIds.sorted()) {
            val clip = findClipAndTrack(project, id)?.second ?: return
            val edge = if (trimStart) clip.timelineStart else try {
                Math.addExact(clip.timelineStart, clip.duration)
            } catch (_: ArithmeticException) {
                return
            }
            val target = try {
                Math.addExact(edge, delta)
            } catch (_: ArithmeticException) {
                return
            }
            commands += CoreV2EditCommand(
                kind = if (trimStart) CoreV2EditKind.TRIM_START else CoreV2EditKind.TRIM_END,
                clipId = id,
                targetFrame = target
            )
        }
        commitNativeTimelineEdits(
            projectForEdit = project,
            commands = commands,
            commandName = if (clipIds.size > 1) "Trim Linked Clips" else if (trimStart) "Trim Start" else "Trim End",
            affectedClipIds = clipIds.sorted()
        )
    }

    fun trimSelectedClipEnd(newEndFrame: Long) {
        val cid = _uiState.value.selectedClipId ?: return
        trimClipEnd(cid, newEndFrame)
    }

    fun undo() {
        if (!historyManager.canUndo) return
        val command = historyManager.nextUndoCommand
        val res = historyManager.undo(_uiState.value.project)
        if (res.isSuccess) {
            if (command is NativeTimelineHistoryCommand) publishNativeProjectState(res.project)
            else updateState(res.project)
        }
    }

    fun redo() {
        if (!historyManager.canRedo) return
        val command = historyManager.nextRedoCommand
        val res = historyManager.redo(_uiState.value.project)
        if (res.isSuccess) {
            if (command is NativeTimelineHistoryCommand) publishNativeProjectState(res.project)
            else updateState(res.project)
        }
    }

    fun pushHistoryCommand(cmd: com.example.timeline.core.TimelineCommand) {
        val project = _uiState.value.project
        val result = cmd.execute(project)
        if (!result.isSuccess || !TimelineCoreV2Adapter.hasSameNativeState(project, result.project)) return
        historyManager.pushCommand(cmd)
        updateState(result.project)
    }

    fun updateStateWithoutHistory(project: TimelineProject) {
        updateState(project, markDirty = false)
        // Ensure UI stays fluid without triggering history logic unnecessarily.
    }

    fun renameTrack(trackId: String, newName: String) {
        val state = _uiState.value
        val result = com.example.timeline.core.CoreTimelineEngine.renameTrack(state.project, trackId, newName)
        if (result.isSuccess) {
            val cmd = SnapshotCommand("Rename Layer", state.project, result.project, emptyList())
            historyManager.pushCommand(cmd)
            updateState(result.project)
        }
    }

    fun deleteTrack(trackId: String) {
        val state = _uiState.value
        val result = com.example.timeline.core.CoreTimelineEngine.removeTrack(state.project, trackId)
        if (result.isSuccess) {
            val cmd = SnapshotCommand("Delete Layer", state.project, result.project, emptyList())
            historyManager.pushCommand(cmd)
            updateState(result.project)
        }
    }

    fun moveTrack(trackId: String, newIndex: Int) {
        val state = _uiState.value
        val result = com.example.timeline.core.CoreTimelineEngine.reorderTrack(state.project, trackId, newIndex)
        if (result.isSuccess) {
            val cmd = SnapshotCommand("Reorder Layer", state.project, result.project, emptyList())
            historyManager.pushCommand(cmd)
            updateState(result.project)
        }
    }

    fun addTrack(type: com.example.timeline.core.TrackType = com.example.timeline.core.TrackType.VIDEO) {
        val state = _uiState.value
        val newTrack = com.example.timeline.core.TimelineTrack(
            id = java.util.UUID.randomUUID().toString(),
            name = "Track ${state.project.tracks.size + 1}",
            type = type,
            clips = emptyList()
        )
        val result = com.example.timeline.core.CoreTimelineEngine.addTrack(state.project, newTrack)
        if (result.isSuccess) {
            val cmd = SnapshotCommand("Add Layer", state.project, result.project, emptyList())
            historyManager.pushCommand(cmd)
            updateState(result.project)
        }
    }

    fun createNewProject(settings: com.example.timeline.core.ProjectSettings) {
        val previousProject = _uiState.value.project
        TimelineCoreV2Adapter.clearHistory(previousProject.id)
        historyManager.clear()
        _playheadFrame.value = 0L
        val projectId = settings.projectId.ifBlank { java.util.UUID.randomUUID().toString() }
        val normalizedSettings = settings.copy(projectId = projectId)
        val newProject = TimelineProject(
            id = projectId,
            settings = normalizedSettings,
            tracks = emptyList(),
            mediaAssets = emptyList()
        )
        _uiState.update { 
            it.copy(
                project = newProject,
                showProjectSetup = false
            )
        }
        autosaveManager.markDirty(newProject)
    }

    fun forceAutosaveProject() {
        autosaveManager.forceAutosave(_uiState.value.project)
    }

    fun forceSaveProject() {
        autosaveManager.markDirty(_uiState.value.project)
    }

    fun toggleDebugPanel() {
        _uiState.update { it.copy(showDebugPanel = !it.showDebugPanel) }
    }

    private fun findClipAndTrack(project: TimelineProject, clipId: String): Pair<TimelineTrack, TimelineClip>? {
        for (track in project.tracks) {
            val clip = track.clips.find { it.id == clipId }
            if (clip != null) return Pair(track, clip)
        }
        return null
    }
    fun setZoom(pixelsPerFrame: Float) { _uiState.update { it.copy(pixelsPerFrame = pixelsPerFrame.coerceIn(0.1f, 50f)) } }

    fun updateSnapSettings(settings: TimelineSnapSettings) {
        _uiState.update { it.copy(snapSettings = settings) }
    }

    fun slipEdit(clipId: String, deltaFrames: Long) {
        executeCommand(com.example.timeline.core.SlipCommand(clipId, deltaFrames))
    }

    fun slideEdit(clipId: String, deltaFrames: Long) {
        executeCommand(com.example.timeline.core.SlideCommand(clipId, deltaFrames))
    }

    fun nudgeSelectedClip(dir: Int) {
        val cid = _uiState.value.selectedClipId ?: return
        val pair = findClipAndTrack(_uiState.value.project, cid) ?: return
        val newStartFrame = try {
            Math.addExact(pair.second.timelineStart, dir.toLong())
        } catch (_: ArithmeticException) {
            return
        }
        if (newStartFrame < 0L) return
        moveSelectedClip(newStartFrame)
    }

    fun moveSelectedClipUpTrack() {
        val clipId = _uiState.value.selectedClipId ?: return
        val project = _uiState.value.project
        val (sourceTrack, clip) = findClipAndTrack(project, clipId) ?: return
        val trackIndex = project.tracks.indexOf(sourceTrack)
        if (trackIndex > 0) {
            commitNativeTimelineEdits(
                projectForEdit = project,
                commands = listOf(
                    CoreV2EditCommand(
                        kind = CoreV2EditKind.MOVE,
                        clipId = clipId,
                        targetTrackId = project.tracks[trackIndex - 1].id,
                        targetFrame = clip.timelineStart
                    )
                ),
                commandName = "Move Clip to Track",
                affectedClipIds = listOf(clipId)
            )
        }
    }

    fun moveSelectedClipDownTrack() {
        val clipId = _uiState.value.selectedClipId ?: return
        val project = _uiState.value.project
        val (sourceTrack, clip) = findClipAndTrack(project, clipId) ?: return
        val trackIndex = project.tracks.indexOf(sourceTrack)
        if (trackIndex < project.tracks.size - 1) {
            commitNativeTimelineEdits(
                projectForEdit = project,
                commands = listOf(
                    CoreV2EditCommand(
                        kind = CoreV2EditKind.MOVE,
                        clipId = clipId,
                        targetTrackId = project.tracks[trackIndex + 1].id,
                        targetFrame = clip.timelineStart
                    )
                ),
                commandName = "Move Clip to Track",
                affectedClipIds = listOf(clipId)
            )
        }
    }

    fun trimSelectedClipToPlayhead(start: Boolean) {
        val clipId = _uiState.value.selectedClipId ?: return
        val command = TimelinePlayheadCommands.trimAtPlayhead(
            clipId = clipId,
            playheadFrame = _playheadFrame.value.coerceAtLeast(0L),
            start = start
        ) ?: return
        commitTimelineEdit(
            commands = listOf(command),
            commandName = if (start) "Trim Start to Playhead" else "Trim End to Playhead"
        )
    }

    fun extendEditToPlayhead() {
        // Extend selected clip to playhead
        trimSelectedClipToPlayhead(start = false)
    }

    /** Changes clip/group protection through the native edit journal so undo/redo stays authoritative. */
    fun setGroupLocked(clipId: String, locked: Boolean) {
        val project = _uiState.value.project
        val target = TimelineGroupLockController.targetFor(project, clipId) ?: return
        val commandName = when {
            target.linkedGroupId != null && locked -> "Lock Linked Group"
            target.linkedGroupId != null -> "Unlock Linked Group"
            locked -> "Lock Clip"
            else -> "Unlock Clip"
        }
        commitNativeTimelineEdits(
            projectForEdit = project,
            commands = listOf(
                CoreV2EditCommand(
                    kind = CoreV2EditKind.SET_GROUP_LOCK,
                    clipId = target.clipId,
                    groupLocked = locked
                )
            ),
            commandName = commandName,
            affectedClipIds = target.memberClipIds
        )
    }

    fun lockTrack(id: String, locked: Boolean) {
        updateState(com.example.timeline.core.CoreTimelineEngine.lockTrack(_uiState.value.project, id, locked).project)
    }
    fun enableTrack(id: String, enabled: Boolean) {
        updateState(com.example.timeline.core.CoreTimelineEngine.setTrackEnabled(_uiState.value.project, id, enabled).project)
    }
    fun muteTrack(id: String, muted: Boolean) {
        updateState(com.example.timeline.core.CoreTimelineEngine.setTrackMute(_uiState.value.project, id, muted).project)
    }
    fun soloTrack(id: String, solo: Boolean) {
        updateState(com.example.timeline.core.CoreTimelineEngine.setTrackSolo(_uiState.value.project, id, solo).project)
    }
}
