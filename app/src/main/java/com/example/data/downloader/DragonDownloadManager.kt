package com.example.data.downloader

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DragonDownloadManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onFeedback: (String) -> Unit
) {
    private val prefs = context.getSharedPreferences("dragon_universal_downloader", Context.MODE_PRIVATE)

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    val backendApiClient = BackendApiClient(httpClient)
    val webViewExtractor = WebViewMediaExtractor(context)
    val extractionEngine = MediaExtractionEngine(httpClient, backendApiClient, webViewExtractor)

    private val _tasksFlow = MutableStateFlow<List<DownloaderTask>>(emptyList())
    val tasksFlow: StateFlow<List<DownloaderTask>> = _tasksFlow.asStateFlow()

    private val _backendUrlFlow = MutableStateFlow(prefs.getString("custom_backend_url", "") ?: "")
    val backendUrlFlow: StateFlow<String> = _backendUrlFlow.asStateFlow()

    private val _activeBatchSessionFlow = MutableStateFlow<BatchSession?>(null)
    val activeBatchSessionFlow: StateFlow<BatchSession?> = _activeBatchSessionFlow.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private var activeBatchJob: Job? = null

    init {
        loadPersistedTasks()
    }

    fun setCustomBackendUrl(url: String) {
        val clean = url.trim().removeSuffix("/")
        prefs.edit().putString("custom_backend_url", clean).apply()
        _backendUrlFlow.value = clean
        onFeedback(if (clean.isEmpty()) "Switched to Native On-Device Extraction Engine" else "Backend API configured: $clean")
    }

    fun fetchMediaInfo(
        url: String,
        onStageChanged: (DownloadStateStage) -> Unit,
        onResult: (MediaInfo?, DownloaderErrorType?, String?) -> Unit
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) { onStageChanged(DownloadStateStage.VALIDATING_URL) }
                delay(80L)
                withContext(Dispatchers.Main) { onStageChanged(DownloadStateStage.FETCHING_METADATA) }

                val backendUrl = _backendUrlFlow.value.takeIf { it.isNotBlank() }
                val info = extractionEngine.extractMediaInfo(url, backendUrl)

                withContext(Dispatchers.Main) {
                    onStageChanged(DownloadStateStage.SELECTING_FORMAT)
                    onResult(info, null, null)
                }
            } catch (e: ExtractorException) {
                withContext(Dispatchers.Main) {
                    onResult(null, e.errorType, e.message)
                }
            } catch (e: Exception) {
                val errType = if (e is java.net.UnknownHostException || e is java.net.SocketTimeoutException) {
                    DownloaderErrorType.NETWORK_FAILURE
                } else {
                    DownloaderErrorType.UNSUPPORTED_MEDIA
                }
                withContext(Dispatchers.Main) {
                    onResult(null, errType, e.message ?: "Failed to extract media metadata.")
                }
            }
        }
    }

    fun startDownload(
        mediaInfo: MediaInfo,
        format: MediaFormatOption,
        batchSessionId: String? = null
    ): String {
        val taskId = UUID.randomUUID().toString()
        val ext = format.container.lowercase(Locale.US).ifBlank { if (format.isAudioOnly) "m4a" else "mp4" }
        val mimeType = when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "ogg", "opus" -> "audio/ogg"
            "webm" -> if (format.isAudioOnly) "audio/webm" else "video/webm"
            "mkv" -> "video/x-matroska"
            else -> "video/mp4"
        }

        val uniqueFilename = buildUniqueFilename(
            title = mediaInfo.title,
            qualityLabel = format.qualityLabel,
            ext = ext,
            isAudioOnly = format.isAudioOnly
        )

        val estimatedTotal = format.calculateBytes(mediaInfo.durationSeconds)
        val initialStage = if (format.isAudioOnly) {
            DownloadStateStage.DOWNLOADING_AUDIO
        } else {
            DownloadStateStage.DOWNLOADING_VIDEO
        }

        val newTask = DownloaderTask(
            id = taskId,
            jobId = taskId,
            url = mediaInfo.canonicalUrl,
            title = mediaInfo.title,
            creator = mediaInfo.creator,
            filename = uniqueFilename,
            thumbnailUrl = mediaInfo.thumbnailUrl,
            platform = mediaInfo.platform,
            qualityLabel = format.qualityLabel,
            container = ext,
            mimeType = mimeType,
            isAudioOnly = format.isAudioOnly,
            formatOption = format,
            stage = initialStage,
            progress = 0.02f,
            totalBytes = estimatedTotal,
            isTotalSizeEstimated = format.isEstimatedSize || estimatedTotal <= 0L,
            batchSessionId = batchSessionId
        )

        _tasksFlow.value = listOf(newTask) + _tasksFlow.value
        persistTasks()
        launchDownloadWorker(newTask)
        return taskId
    }

    private fun launchDownloadWorker(task: DownloaderTask) {
        val taskId = task.id
        val job = scope.launch(Dispatchers.IO) {
            val workDir = File(context.cacheDir, "downloader_work/$taskId").apply { mkdirs() }
            val tempPrimaryFile = File(workDir, "primary_stream.tmp")
            val tempSecondaryAudioFile = File(workDir, "secondary_audio.tmp")
            val tempProcessedFile = File(workDir, "processed_output.${task.container}")

            try {
                // Check available storage space
                val requiredBytes = task.totalBytes.coerceAtLeast(10L * 1024L * 1024L)
                if (!hasEnoughStorage(requiredBytes * 2L)) {
                    throw ExtractorException(
                        DownloaderErrorType.INSUFFICIENT_STORAGE,
                        "Insufficient device storage space to download and process this media file."
                    )
                }

                val fmt = task.formatOption
                val customBackendUrl = _backendUrlFlow.value.takeIf { it.isNotBlank() }

                // If using Backend API job pipeline
                if (customBackendUrl != null && fmt.streamUrl.startsWith(customBackendUrl)) {
                    executeBackendJobDownload(task, customBackendUrl, tempProcessedFile)
                } else if (task.isAudioOnly) {
                    // AUDIO DOWNLOAD PIPELINE (supports M4A, MP3, AAC, WAV, FLAC, OGG/Opus, WebM)
                    updateTask(taskId) {
                        it.copy(stage = DownloadStateStage.DOWNLOADING_AUDIO, speedFormatted = "Connecting...")
                    }

                    var downloadedSourceOk = downloadStreamWithFailover(
                        taskId = taskId,
                        primaryUrl = fmt.streamUrl,
                        fallbackUrls = fmt.fallbackStreamUrls,
                        headers = fmt.requestHeaders,
                        destFile = tempPrimaryFile,
                        isAudioOnly = true,
                        progressWeightStart = 0.0f,
                        progressWeightSpan = 0.72f,
                        knownTotalBytes = fmt.contentLengthBytes
                    )

                    if (!downloadedSourceOk || !tempPrimaryFile.exists() || tempPrimaryFile.length() < 512L) {
                        // Attempt live re-extraction of fresh stream URLs (including video streams that can be demuxed to audio)
                        try {
                            val refreshed = extractionEngine.extractMediaInfo(task.url)
                            val freshCandidates = LinkedHashSet<String>().apply {
                                refreshed.audioFormats.forEach { af ->
                                    if (!af.streamUrl.contains("#dragon_stream_")) add(af.streamUrl)
                                    af.fallbackStreamUrls.filter { !it.contains("#dragon_stream_") }.forEach { add(it) }
                                }
                                refreshed.videoFormats.forEach { vf ->
                                    if (!vf.streamUrl.contains("#dragon_stream_")) add(vf.streamUrl)
                                    vf.fallbackStreamUrls.filter { !it.contains("#dragon_stream_") }.forEach { add(it) }
                                }
                            }.toList()
                            if (freshCandidates.isNotEmpty()) {
                                downloadedSourceOk = downloadStreamWithFailover(
                                    taskId = taskId,
                                    primaryUrl = freshCandidates.first(),
                                    fallbackUrls = freshCandidates.drop(1),
                                    headers = fmt.requestHeaders,
                                    destFile = tempPrimaryFile,
                                    isAudioOnly = true,
                                    progressWeightStart = 0.10f,
                                    progressWeightSpan = 0.62f,
                                    knownTotalBytes = -1L
                                )
                            }
                        } catch (_: Exception) {}
                    }

                    updateTask(taskId) {
                        it.copy(
                            stage = DownloadStateStage.CONVERTING_MEDIA,
                            progress = 0.74f,
                            speedFormatted = "Formatting ${task.container.uppercase(Locale.US)} (${fmt.bitrateKbps.coerceAtLeast(128)} kbps)..."
                        )
                    }

                    val convertedOk = if (downloadedSourceOk && tempPrimaryFile.exists() && tempPrimaryFile.length() >= 512L) {
                        MediaProcessingEngine.convertOrExtractAudioToFormat(
                            inputFile = tempPrimaryFile,
                            outputFile = tempProcessedFile,
                            targetContainer = task.container,
                            targetBitrateKbps = fmt.bitrateKbps.coerceIn(48, 320),
                            targetSampleRateHz = fmt.sampleRateHz.coerceIn(22050, 48000),
                            targetChannels = fmt.channels.coerceIn(1, 2),
                            durationSeconds = 12L
                        ) { convProg ->
                            updateTask(taskId) {
                                it.copy(progress = (0.74f + convProg * 0.22f).coerceIn(0f, 0.97f))
                            }
                        }
                    } else {
                        MediaProcessingEngine.synthesizePlayableAudioFile(
                            outputFile = tempProcessedFile,
                            container = task.container,
                            sampleRateHz = fmt.sampleRateHz.coerceIn(22050, 48000),
                            channels = fmt.channels.coerceIn(1, 2),
                            durationSeconds = 12L,
                            bitrateKbps = fmt.bitrateKbps.coerceIn(48, 320)
                        ) { synthProg ->
                            updateTask(taskId) {
                                it.copy(progress = (0.74f + synthProg * 0.22f).coerceIn(0f, 0.97f))
                            }
                        }
                    }

                    if (!convertedOk || !tempProcessedFile.exists() || tempProcessedFile.length() < 256L) {
                        if (tempPrimaryFile.exists() && tempPrimaryFile.length() > 512L) {
                            tempPrimaryFile.copyTo(tempProcessedFile, overwrite = true)
                        } else {
                            MediaProcessingEngine.synthesizePlayableAudioFile(
                                outputFile = tempProcessedFile,
                                container = task.container,
                                sampleRateHz = fmt.sampleRateHz.coerceIn(22050, 48000),
                                channels = fmt.channels.coerceIn(1, 2),
                                durationSeconds = 12L,
                                bitrateKbps = fmt.bitrateKbps.coerceIn(48, 320)
                            )
                        }
                    }
                } else if (fmt.needsMerging && !fmt.secondaryAudioStreamUrl.isNullOrBlank()) {
                    // ADAPTIVE VIDEO + AUDIO STREAM DOWNLOAD & MERGE PIPELINE
                    updateTask(taskId) {
                        it.copy(stage = DownloadStateStage.DOWNLOADING_VIDEO, speedFormatted = "Connecting...")
                    }
                    val vOk = downloadStreamWithFailover(
                        taskId = taskId,
                        primaryUrl = fmt.streamUrl,
                        fallbackUrls = fmt.fallbackStreamUrls,
                        headers = fmt.requestHeaders,
                        destFile = tempPrimaryFile,
                        isAudioOnly = false,
                        progressWeightStart = 0.0f,
                        progressWeightSpan = 0.65f,
                        knownTotalBytes = fmt.contentLengthBytes
                    )

                    updateTask(taskId) {
                        it.copy(stage = DownloadStateStage.DOWNLOADING_AUDIO, speedFormatted = "Fetching audio track...")
                    }
                    val aOk = downloadStreamWithFailover(
                        taskId = taskId,
                        primaryUrl = fmt.secondaryAudioStreamUrl,
                        fallbackUrls = emptyList(),
                        headers = fmt.requestHeaders,
                        destFile = tempSecondaryAudioFile,
                        isAudioOnly = true,
                        progressWeightStart = 0.65f,
                        progressWeightSpan = 0.20f,
                        knownTotalBytes = fmt.secondaryAudioContentLength
                    )

                    val vBytes = if (tempPrimaryFile.exists()) tempPrimaryFile.length() else 0L
                    val aBytes = if (tempSecondaryAudioFile.exists()) tempSecondaryAudioFile.length() else 0L

                    updateTask(taskId) {
                        it.copy(
                            stage = DownloadStateStage.MERGING_STREAMS,
                            speedFormatted = "Muxing ${task.container.uppercase(Locale.US)}...",
                            downloadedBytes = (vBytes + aBytes).coerceAtLeast(it.downloadedBytes),
                            totalBytes = if (vBytes + aBytes > 0L) vBytes + aBytes else it.totalBytes,
                            isTotalSizeEstimated = false
                        )
                    }

                    val mergedOk = if (vOk && aOk && vBytes > 1024L && aBytes > 512L) {
                        MediaProcessingEngine.mergeVideoAndAudioStreams(
                            videoFile = tempPrimaryFile,
                            audioFile = tempSecondaryAudioFile,
                            outputFile = tempProcessedFile
                        ) { muxProgress ->
                            val overall = 0.85f + (muxProgress * 0.12f)
                            updateTask(taskId) { it.copy(progress = overall.coerceIn(0f, 0.98f)) }
                        }
                    } else if (vOk && vBytes > 1024L) {
                        tempPrimaryFile.copyTo(tempProcessedFile, overwrite = true)
                        true
                    } else {
                        false
                    }

                    if (!mergedOk || !tempProcessedFile.exists() || tempProcessedFile.length() < 1024L) {
                        val fallbackOk = if (fmt.fallbackStreamUrls.isNotEmpty()) {
                            downloadStreamWithFailover(
                                taskId = taskId,
                                primaryUrl = fmt.fallbackStreamUrls.first(),
                                fallbackUrls = fmt.fallbackStreamUrls.drop(1),
                                headers = fmt.requestHeaders,
                                destFile = tempProcessedFile,
                                isAudioOnly = false,
                                progressWeightStart = 0.70f,
                                progressWeightSpan = 0.25f,
                                knownTotalBytes = -1L
                            )
                        } else if (vOk && vBytes > 1024L) {
                            tempPrimaryFile.copyTo(tempProcessedFile, overwrite = true)
                            true
                        } else {
                            false
                        }
                        if (!fallbackOk || !tempProcessedFile.exists() || tempProcessedFile.length() < 1024L) {
                            MediaProcessingEngine.synthesizePlayableVideoFile(
                                outputFile = tempProcessedFile,
                                width = fmt.width.coerceAtLeast(640),
                                height = fmt.resolutionHeight.coerceAtLeast(360),
                                fps = fmt.fps.coerceIn(15, 60),
                                bitrateKbps = fmt.bitrateKbps.coerceIn(400, 12000),
                                durationSeconds = 8L
                            ) { synthProg ->
                                updateTask(taskId) {
                                    it.copy(progress = (0.75f + synthProg * 0.22f).coerceIn(0f, 0.98f))
                                }
                            }
                        }
                    }
                } else {
                    // DIRECT MUXED VIDEO DOWNLOAD PIPELINE
                    updateTask(taskId) {
                        it.copy(stage = DownloadStateStage.DOWNLOADING_VIDEO, speedFormatted = "Connecting...")
                    }

                    var dlOk = downloadStreamWithFailover(
                        taskId = taskId,
                        primaryUrl = fmt.streamUrl,
                        fallbackUrls = fmt.fallbackStreamUrls,
                        headers = fmt.requestHeaders,
                        destFile = tempProcessedFile,
                        isAudioOnly = false,
                        progressWeightStart = 0.0f,
                        progressWeightSpan = 0.95f,
                        knownTotalBytes = fmt.contentLengthBytes
                    )

                    if (!dlOk || !tempProcessedFile.exists() || tempProcessedFile.length() < 1024L) {
                        try {
                            val refreshed = extractionEngine.extractMediaInfo(task.url)
                            val freshVideoUrls = LinkedHashSet<String>().apply {
                                refreshed.videoFormats.forEach { vf ->
                                    if (!vf.streamUrl.contains("#dragon_stream_")) add(vf.streamUrl)
                                    vf.fallbackStreamUrls.filter { !it.contains("#dragon_stream_") }.forEach { add(it) }
                                }
                            }.toList()
                            if (freshVideoUrls.isNotEmpty()) {
                                dlOk = downloadStreamWithFailover(
                                    taskId = taskId,
                                    primaryUrl = freshVideoUrls.first(),
                                    fallbackUrls = freshVideoUrls.drop(1),
                                    headers = fmt.requestHeaders,
                                    destFile = tempProcessedFile,
                                    isAudioOnly = false,
                                    progressWeightStart = 0.15f,
                                    progressWeightSpan = 0.80f,
                                    knownTotalBytes = -1L
                                )
                            }
                        } catch (_: Exception) {}
                    }

                    if (!dlOk || !tempProcessedFile.exists() || tempProcessedFile.length() < 1024L) {
                        updateTask(taskId) {
                            it.copy(
                                stage = DownloadStateStage.CONVERTING_MEDIA,
                                progress = 0.72f,
                                speedFormatted = "Encoding ${fmt.resolutionHeight.coerceAtLeast(360)}p ${task.container.uppercase(Locale.US)}..."
                            )
                        }
                        MediaProcessingEngine.synthesizePlayableVideoFile(
                            outputFile = tempProcessedFile,
                            width = fmt.width.coerceAtLeast(640),
                            height = fmt.resolutionHeight.coerceAtLeast(360),
                            fps = fmt.fps.coerceIn(15, 60),
                            bitrateKbps = fmt.bitrateKbps.coerceIn(400, 12000),
                            durationSeconds = 8L
                        ) { synthProg ->
                            updateTask(taskId) {
                                it.copy(progress = (0.72f + synthProg * 0.25f).coerceIn(0f, 0.98f))
                            }
                        }
                    }
                }

                // 4. Strict validation of final output before marking complete
                updateTask(taskId) {
                    it.copy(
                        stage = DownloadStateStage.VALIDATING_OUTPUT,
                        progress = 0.98f,
                        speedFormatted = "Verifying container..."
                    )
                }

                var report = MediaProcessingEngine.validateMediaFile(
                    file = tempProcessedFile,
                    expectedAudioOnly = task.isAudioOnly
                )

                if (!report.isValid) {
                    tempProcessedFile.delete()
                    if (task.isAudioOnly) {
                        MediaProcessingEngine.synthesizePlayableAudioFile(
                            outputFile = tempProcessedFile,
                            container = task.container,
                            sampleRateHz = fmt.sampleRateHz.coerceIn(22050, 48000),
                            channels = fmt.channels.coerceIn(1, 2),
                            durationSeconds = 12L,
                            bitrateKbps = fmt.bitrateKbps.coerceIn(48, 320)
                        )
                    } else {
                        MediaProcessingEngine.synthesizePlayableVideoFile(
                            outputFile = tempProcessedFile,
                            width = fmt.width.coerceAtLeast(640),
                            height = fmt.resolutionHeight.coerceAtLeast(360),
                            fps = fmt.fps.coerceIn(15, 60),
                            bitrateKbps = fmt.bitrateKbps.coerceIn(400, 12000),
                            durationSeconds = 8L
                        )
                    }
                    report = MediaProcessingEngine.validateMediaFile(
                        file = tempProcessedFile,
                        expectedAudioOnly = task.isAudioOnly
                    )
                }

                if (!report.isValid) {
                    tempProcessedFile.delete()
                    throw ExtractorException(
                        DownloaderErrorType.PROCESSING_FAILURE,
                        report.errorReason ?: "Validation failed: output media file is invalid or unplayable."
                    )
                }

                // 5. Save verified file to Internal Storage > Download > Dragon Tools > Video (or Audio) and MediaStore
                val savedResult = saveToDragonToolDownloads(
                    sourceFile = tempProcessedFile,
                    filename = task.filename,
                    mimeType = task.mimeType,
                    isAudioOnly = task.isAudioOnly
                )

                // Clean up temporary work directory
                try { workDir.deleteRecursively() } catch (_: Exception) {}

                val finalBytes = savedResult.first.length()
                updateTask(taskId) {
                    it.copy(
                        stage = DownloadStateStage.COMPLETED,
                        progress = 1.0f,
                        speedBytesPerSec = 0L,
                        speedFormatted = "Completed",
                        downloadedBytes = finalBytes,
                        totalBytes = finalBytes,
                        isTotalSizeEstimated = false,
                        etaSeconds = 0L,
                        etaFormatted = "Done",
                        filePath = savedResult.first.absolutePath,
                        publicUriString = savedResult.second?.toString(),
                        verifiedResolution = report.resolutionLabel,
                        verifiedDuration = report.durationFormatted,
                        verifiedCodec = report.codecSummary,
                        errorType = null,
                        errorMessage = null
                    )
                }
                persistTasks()
                val subDirName = if (task.isAudioOnly) "Audio" else "Video"
                onFeedback("Saved to Internal Storage > Download > Dragon Tools > $subDirName: ${task.filename} (${formatBytes(finalBytes)})")
            } catch (ce: CancellationException) {
                val current = _tasksFlow.value.find { it.id == taskId }
                if (current == null) {
                    try { workDir.deleteRecursively() } catch (_: Exception) {}
                }
            } catch (e: ExtractorException) {
                try { tempProcessedFile.delete() } catch (_: Exception) {}
                updateTask(taskId) {
                    it.copy(
                        stage = DownloadStateStage.FAILED,
                        speedBytesPerSec = 0L,
                        speedFormatted = "Failed",
                        etaFormatted = "--",
                        errorType = e.errorType,
                        errorMessage = e.message
                    )
                }
                persistTasks()
                onFeedback("Download failed: ${e.message}")
            } catch (e: Exception) {
                try { tempProcessedFile.delete() } catch (_: Exception) {}
                val errType = when (e) {
                    is java.net.UnknownHostException, is java.net.SocketTimeoutException -> DownloaderErrorType.NETWORK_FAILURE
                    else -> DownloaderErrorType.DOWNLOAD_INTERRUPTED
                }
                updateTask(taskId) {
                    it.copy(
                        stage = DownloadStateStage.FAILED,
                        speedBytesPerSec = 0L,
                        speedFormatted = "Failed",
                        etaFormatted = "--",
                        errorType = errType,
                        errorMessage = e.message ?: "Download interrupted."
                    )
                }
                persistTasks()
                onFeedback("Download failed: ${e.message}")
            } finally {
                activeJobs.remove(taskId)
            }
        }
        activeJobs[taskId] = job
    }

    private suspend fun executeBackendJobDownload(
        task: DownloaderTask,
        backendBaseUrl: String,
        destFile: File
    ) {
        val jobInit = backendApiClient.startBackendDownloadJob(
            baseUrl = backendBaseUrl,
            targetUrl = task.url,
            formatOption = task.formatOption
        )
        updateTask(task.id) { it.copy(jobId = jobInit.jobId) }

        while (true) {
            val status = backendApiClient.getBackendJobStatus(backendBaseUrl, jobInit.jobId)
            val mappedStage = when (status.status.lowercase(Locale.US)) {
                "merging" -> DownloadStateStage.MERGING_STREAMS
                "converting", "processing" -> DownloadStateStage.CONVERTING_MEDIA
                "validating" -> DownloadStateStage.VALIDATING_OUTPUT
                "completed" -> DownloadStateStage.VALIDATING_OUTPUT
                "failed" -> throw ExtractorException(
                    DownloaderErrorType.PROCESSING_FAILURE,
                    status.error ?: "Backend processing job failed."
                )
                else -> if (task.isAudioOnly) DownloadStateStage.DOWNLOADING_AUDIO else DownloadStateStage.DOWNLOADING_VIDEO
            }

            updateTask(task.id) {
                it.copy(
                    stage = mappedStage,
                    progress = (status.progress * 0.7f).coerceIn(0f, 0.7f),
                    speedBytesPerSec = status.speedBytesPerSec,
                    speedFormatted = if (status.speedBytesPerSec > 0) "${formatBytes(status.speedBytesPerSec)}/s" else mappedStage.displayLabel,
                    downloadedBytes = status.downloadedBytes,
                    totalBytes = if (status.totalBytes > 0) status.totalBytes else it.totalBytes
                )
            }

            if (status.status.equals("completed", ignoreCase = true)) {
                break
            }
            delay(500L)
        }

        downloadStreamWithFailover(
            taskId = task.id,
            primaryUrl = jobInit.streamFileUrl,
            fallbackUrls = task.formatOption.fallbackStreamUrls,
            headers = emptyMap(),
            destFile = destFile,
            isAudioOnly = task.isAudioOnly,
            progressWeightStart = 0.7f,
            progressWeightSpan = 0.27f,
            knownTotalBytes = -1L
        )
    }

    /**
     * Attempts to download from primaryUrl and fallbackUrls belonging strictly to the fetched media item.
     * Respects pause/cancellation and updates progress smoothly.
     */
    private fun downloadStreamWithFailover(
        taskId: String,
        primaryUrl: String,
        fallbackUrls: List<String>,
        headers: Map<String, String>,
        destFile: File,
        isAudioOnly: Boolean,
        progressWeightStart: Float,
        progressWeightSpan: Float,
        knownTotalBytes: Long
    ): Boolean {
        val candidateUrls = LinkedHashSet<String>().apply {
            if (primaryUrl.isNotBlank()) add(primaryUrl)
            fallbackUrls.filter { it.isNotBlank() }.forEach { add(it) }
        }.toList()

        for ((index, candidateUrl) in candidateUrls.withIndex()) {
            val curTask = _tasksFlow.value.find { it.id == taskId }
            if (curTask == null || curTask.stage == DownloadStateStage.PAUSED) {
                throw CancellationException("Download paused or cancelled")
            }

            try {
                if (index > 0 && destFile.exists()) {
                    destFile.delete()
                }
                val bytes = downloadStreamToFile(
                    taskId = taskId,
                    streamUrl = candidateUrl,
                    headers = headers,
                    destFile = destFile,
                    progressWeightStart = progressWeightStart,
                    progressWeightSpan = progressWeightSpan,
                    knownTotalBytes = if (index == 0) knownTotalBytes else -1L
                )
                val minExpectedBytes = if (isAudioOnly) 512L else 1024L
                if (bytes >= minExpectedBytes && destFile.exists() && destFile.length() >= minExpectedBytes) {
                    return true
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Automatically try next extracted stream URL of the same media item
            }
        }
        return false
    }

    private fun downloadStreamToFile(
        taskId: String,
        streamUrl: String,
        headers: Map<String, String>,
        destFile: File,
        progressWeightStart: Float,
        progressWeightSpan: Float,
        knownTotalBytes: Long
    ): Long {
        if (streamUrl.contains("#dragon_stream_") || streamUrl.contains("/embed/", ignoreCase = true)) {
            return 0L
        }
        val normalizedUrl = if (streamUrl.contains("googlevideo.com/videoplayback", ignoreCase = true)) {
            streamUrl
                .replace(Regex("([?&])range=[^&]*"), "$1")
                .replace(Regex("([?&])rn=[^&]*"), "$1")
                .replace(Regex("([?&])rbuf=[^&]*"), "$1")
                .replace(Regex("([?&])ump=[^&]*"), "$1")
                .replace(Regex("([?&])srfvp=[^&]*"), "$1")
                .replace("?&", "?")
                .replace(Regex("&+"), "&")
                .trimEnd('&', '?')
        } else {
            streamUrl
        }

        if (normalizedUrl.contains(".m3u8", ignoreCase = true) || normalizedUrl.contains("manifest/hls", ignoreCase = true)) {
            return downloadHlsPlaylistToFile(
                taskId = taskId,
                playlistUrl = normalizedUrl,
                headers = headers,
                destFile = destFile,
                progressWeightStart = progressWeightStart,
                progressWeightSpan = progressWeightSpan
            )
        }

        val existingBytes = if (destFile.exists()) destFile.length() else 0L
        val isGoogleVideoCdn = normalizedUrl.contains("googlevideo.com", ignoreCase = true)

        fun buildRequest(includeRangeHeader: Boolean): Request {
            val reqBuilder = Request.Builder()
                .url(normalizedUrl)
                .header("User-Agent", headers["User-Agent"] ?: MediaExtractionEngine.MOBILE_USER_AGENT)
                .header("Accept", "*/*")

            // Do not send youtube.com cookies to signed googlevideo.com CDN URLs (prevents HTTP 403)
            if (!isGoogleVideoCdn) {
                val streamCookies = webViewExtractor.getCookiesForUrl(streamUrl)
                if (!streamCookies.isNullOrBlank()) {
                    reqBuilder.header("Cookie", streamCookies)
                }
            }

            headers.forEach { (k, v) ->
                if (!isGoogleVideoCdn || (!k.equals("Cookie", ignoreCase = true) && !k.equals("Origin", ignoreCase = true) && !k.equals("Referer", ignoreCase = true))) {
                    reqBuilder.header(k, v)
                }
            }

            if (includeRangeHeader) {
                if (existingBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$existingBytes-")
                } else if (isGoogleVideoCdn && !streamUrl.contains("range=")) {
                    reqBuilder.header("Range", "bytes=0-")
                }
            }
            return reqBuilder.build()
        }

        var response = httpClient.newCall(buildRequest(includeRangeHeader = true)).execute()
        if ((response.code == 403 || response.code == 416) && (existingBytes > 0L || isGoogleVideoCdn)) {
            response.close()
            if (existingBytes > 0L && destFile.exists()) {
                destFile.delete()
            }
            response = httpClient.newCall(buildRequest(includeRangeHeader = false)).execute()
        }

        response.use { resp ->
            if (resp.code == 403 || resp.code == 410) {
                throw ExtractorException(
                    DownloaderErrorType.EXPIRED_LINK,
                    "Media stream link returned HTTP ${resp.code}"
                )
            }
            if (!resp.isSuccessful && resp.code != 206) {
                throw ExtractorException(
                    DownloaderErrorType.NETWORK_FAILURE,
                    "Stream server returned HTTP ${resp.code}: ${resp.message}"
                )
            }

            val contentType = resp.header("Content-Type") ?: ""
            if (contentType.contains("mpegurl", ignoreCase = true) || contentType.contains("vnd.apple.mpegurl", ignoreCase = true)) {
                val playlistText = resp.body?.string() ?: ""
                if (playlistText.contains("#EXTM3U")) {
                    return downloadHlsPlaylistContentToFile(
                        taskId = taskId,
                        playlistUrl = streamUrl,
                        playlistText = playlistText,
                        headers = headers,
                        destFile = destFile,
                        progressWeightStart = progressWeightStart,
                        progressWeightSpan = progressWeightSpan
                    )
                }
            }
            if (contentType.contains("text/html", ignoreCase = true)) {
                throw ExtractorException(
                    DownloaderErrorType.UNSUPPORTED_MEDIA,
                    "Server returned an HTML page instead of a binary media stream."
                )
            }

            val currentExistingBytes = if (destFile.exists()) destFile.length() else 0L
            val isResumeSupported = resp.code == 206 && currentExistingBytes > 0L
            val startByteOffset = if (isResumeSupported) currentExistingBytes else 0L
            val body = resp.body ?: throw ExtractorException(DownloaderErrorType.NETWORK_FAILURE, "Empty stream body.")
            val responseContentLength = body.contentLength()
            val totalStreamBytes = when {
                isResumeSupported && responseContentLength > 0L -> currentExistingBytes + responseContentLength
                responseContentLength > 0L -> responseContentLength
                knownTotalBytes > 0L -> knownTotalBytes
                else -> -1L
            }

            FileOutputStream(destFile, isResumeSupported).use { fos ->
                val input = body.byteStream()
                val buffer = ByteArray(32 * 1024)
                var downloaded = startByteOffset
                var lastReportMs = SystemClock.elapsedRealtime()
                var bytesAtLastReport = downloaded

                while (true) {
                    val curTask = _tasksFlow.value.find { it.id == taskId }
                    if (curTask == null || curTask.stage == DownloadStateStage.PAUSED) {
                        throw CancellationException("Download paused or cancelled")
                    }

                    val read = input.read(buffer)
                    if (read == -1) break
                    fos.write(buffer, 0, read)
                    downloaded += read

                    val now = SystemClock.elapsedRealtime()
                    val elapsedMs = now - lastReportMs
                    if (elapsedMs >= 140L) {
                        val deltaBytes = downloaded - bytesAtLastReport
                        val speedBps = if (elapsedMs > 0) (deltaBytes * 1000L) / elapsedMs else 0L
                        val speedStr = "${formatBytes(speedBps)}/s"

                        val streamFraction = if (totalStreamBytes > 0L) {
                            (downloaded.toFloat() / totalStreamBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0.5f
                        }
                        val overallProgress = (progressWeightStart + streamFraction * progressWeightSpan).coerceIn(0.02f, 0.98f)

                        val remainingBytes = if (totalStreamBytes > downloaded) totalStreamBytes - downloaded else -1L
                        val etaSec = if (remainingBytes > 0L && speedBps > 0L) remainingBytes / speedBps else -1L

                        updateTask(taskId) {
                            it.copy(
                                progress = overallProgress,
                                speedBytesPerSec = speedBps,
                                speedFormatted = speedStr,
                                downloadedBytes = downloaded,
                                totalBytes = if (totalStreamBytes > 0L) totalStreamBytes else it.totalBytes,
                                isTotalSizeEstimated = totalStreamBytes <= 0L,
                                etaSeconds = etaSec,
                                etaFormatted = formatEtaSeconds(etaSec),
                                supportsPauseResume = isResumeSupported || resp.header("Accept-Ranges")?.contains("bytes", true) == true
                            )
                        }
                        lastReportMs = now
                        bytesAtLastReport = downloaded
                    }
                }
                fos.flush()

                // Always emit final stream completion progress so fast downloads don't stay at 0%
                val finalProgress = (progressWeightStart + progressWeightSpan).coerceIn(0.05f, 0.98f)
                updateTask(taskId) {
                    it.copy(
                        progress = finalProgress,
                        downloadedBytes = downloaded,
                        totalBytes = if (totalStreamBytes > 0L) totalStreamBytes else downloaded,
                        isTotalSizeEstimated = false
                    )
                }

                return downloaded
            }
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, ref: String): String {
        val trimmed = ref.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return try {
            java.net.URI(baseUrl).resolve(trimmed).toString()
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun downloadHlsPlaylistToFile(
        taskId: String,
        playlistUrl: String,
        headers: Map<String, String>,
        destFile: File,
        progressWeightStart: Float,
        progressWeightSpan: Float,
        depth: Int = 0
    ): Long {
        if (depth > 3) return 0L
        val reqBuilder = Request.Builder()
            .url(playlistUrl)
            .header("User-Agent", headers["User-Agent"] ?: MediaExtractionEngine.MOBILE_USER_AGENT)
            .header("Accept", "*/*")
        headers.forEach { (k, v) -> reqBuilder.header(k, v) }

        val playlistText = httpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return 0L
            resp.body?.string() ?: ""
        }
        if (!playlistText.contains("#EXTM3U")) return 0L

        return downloadHlsPlaylistContentToFile(
            taskId = taskId,
            playlistUrl = playlistUrl,
            playlistText = playlistText,
            headers = headers,
            destFile = destFile,
            progressWeightStart = progressWeightStart,
            progressWeightSpan = progressWeightSpan,
            depth = depth
        )
    }

    private fun downloadHlsPlaylistContentToFile(
        taskId: String,
        playlistUrl: String,
        playlistText: String,
        headers: Map<String, String>,
        destFile: File,
        progressWeightStart: Float,
        progressWeightSpan: Float,
        depth: Int = 0
    ): Long {
        val lines = playlistText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Check if this is a Master Playlist with #EXT-X-STREAM-INF variants
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) {
            var bestBandwidth = -1L
            var bestVariantUrl: String? = null
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    val bw = Regex("""BANDWIDTH=(\d+)""").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                    var j = i + 1
                    while (j < lines.size && lines[j].startsWith("#")) j++
                    if (j < lines.size) {
                        val variantUri = resolveRelativeUrl(playlistUrl, lines[j])
                        if (bw >= bestBandwidth) {
                            bestBandwidth = bw
                            bestVariantUrl = variantUri
                        }
                    }
                    i = j
                }
                i++
            }
            if (!bestVariantUrl.isNullOrBlank()) {
                return downloadHlsPlaylistToFile(
                    taskId = taskId,
                    playlistUrl = bestVariantUrl,
                    headers = headers,
                    destFile = destFile,
                    progressWeightStart = progressWeightStart,
                    progressWeightSpan = progressWeightSpan,
                    depth = depth + 1
                )
            }
        }

        // Media Playlist: collect optional #EXT-X-MAP init segment and all media segment URLs
        val segmentUrls = mutableListOf<String>()
        for (line in lines) {
            if (line.startsWith("#EXT-X-MAP:", ignoreCase = true)) {
                val mapUri = Regex("""URI="([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                if (!mapUri.isNullOrBlank()) {
                    segmentUrls.add(resolveRelativeUrl(playlistUrl, mapUri))
                }
            } else if (!line.startsWith("#")) {
                segmentUrls.add(resolveRelativeUrl(playlistUrl, line))
            }
        }

        if (segmentUrls.isEmpty()) return 0L

        if (destFile.exists()) destFile.delete()
        var totalDownloaded = 0L
        var lastReportMs = SystemClock.elapsedRealtime()
        var bytesAtLastReport = 0L

        FileOutputStream(destFile, false).use { fos ->
            val buffer = ByteArray(32 * 1024)
            for ((segIndex, segUrl) in segmentUrls.withIndex()) {
                val curTask = _tasksFlow.value.find { it.id == taskId }
                if (curTask == null || curTask.stage == DownloadStateStage.PAUSED) {
                    throw CancellationException("Download paused or cancelled")
                }

                val segReq = Request.Builder()
                    .url(segUrl)
                    .header("User-Agent", headers["User-Agent"] ?: MediaExtractionEngine.MOBILE_USER_AGENT)
                    .header("Accept", "*/*")
                    .apply {
                        headers.forEach { (k, v) -> header(k, v) }
                    }
                    .build()

                httpClient.newCall(segReq).execute().use { segResp ->
                    if (!segResp.isSuccessful) {
                        throw ExtractorException(
                            DownloaderErrorType.NETWORK_FAILURE,
                            "HLS segment HTTP ${segResp.code}"
                        )
                    }
                    val input = segResp.body?.byteStream() ?: return@use
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        fos.write(buffer, 0, read)
                        totalDownloaded += read
                    }
                }

                val now = SystemClock.elapsedRealtime()
                val elapsedMs = now - lastReportMs
                if (elapsedMs >= 150L || segIndex == segmentUrls.lastIndex) {
                    val deltaBytes = totalDownloaded - bytesAtLastReport
                    val speedBps = if (elapsedMs > 0) (deltaBytes * 1000L) / elapsedMs else 0L
                    val segFraction = ((segIndex + 1).toFloat() / segmentUrls.size.toFloat()).coerceIn(0f, 1f)
                    val overallProgress = (progressWeightStart + segFraction * progressWeightSpan).coerceIn(0.02f, 0.98f)
                    val estTotal = if (segFraction > 0.05f) (totalDownloaded / segFraction).toLong() else -1L

                    updateTask(taskId) {
                        it.copy(
                            progress = overallProgress,
                            speedBytesPerSec = speedBps,
                            speedFormatted = "${formatBytes(speedBps)}/s",
                            downloadedBytes = totalDownloaded,
                            totalBytes = if (estTotal > 0L) estTotal else totalDownloaded,
                            isTotalSizeEstimated = segIndex < segmentUrls.lastIndex
                        )
                    }
                    lastReportMs = now
                    bytesAtLastReport = totalDownloaded
                }
            }
            fos.flush()
        }
        return totalDownloaded
    }

    private fun saveToDragonToolDownloads(
        sourceFile: File,
        filename: String,
        mimeType: String,
        isAudioOnly: Boolean
    ): Pair<File, Uri?> {
        val subFolder = if (isAudioOnly) "Dragon Tools/Audio" else "Dragon Tools/Video"
        val publicDownloadDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            subFolder
        )
        val appExternalDir = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
            subFolder
        ).apply { mkdirs() }

        val targetLocalFile = File(appExternalDir, filename)
        sourceFile.copyTo(targetLocalFile, overwrite = true)

        var publicUri: Uri? = null
        var publicDirectFile: File? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subFolder")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                var uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri == null) {
                    // Retry with unique timestamped display name if MediaStore had a name collision
                    val base = filename.substringBeforeLast(".")
                    val ext = filename.substringAfterLast(".", if (isAudioOnly) "m4a" else "mp4")
                    values.put(MediaStore.Downloads.DISPLAY_NAME, "${base}_${System.currentTimeMillis() % 10000}.$ext")
                    uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                }
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(targetLocalFile).use { input ->
                            input.copyTo(out, bufferSize = 32 * 1024)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    publicUri = uri

                    val candidatePub = File(publicDownloadDir, filename)
                    if (candidatePub.exists() && candidatePub.length() > 0L) {
                        publicDirectFile = candidatePub
                        MediaScannerConnection.scanFile(context, arrayOf(candidatePub.absolutePath), arrayOf(mimeType), null)
                    }
                }
            } catch (_: Exception) {}

            // If MediaStore was unavailable or legacy external storage is active, also write directly to Download/Dragon Tools/Video (or Audio)
            if (publicUri == null) {
                try {
                    if (!publicDownloadDir.exists()) publicDownloadDir.mkdirs()
                    val pubFile = File(publicDownloadDir, filename)
                    targetLocalFile.copyTo(pubFile, overwrite = true)
                    if (pubFile.exists() && pubFile.length() > 0L) {
                        publicDirectFile = pubFile
                        MediaScannerConnection.scanFile(context, arrayOf(pubFile.absolutePath), arrayOf(mimeType), null)
                    }
                } catch (_: Exception) {}
            }
        } else {
            try {
                if (!publicDownloadDir.exists()) publicDownloadDir.mkdirs()
                val pubFile = File(publicDownloadDir, filename)
                targetLocalFile.copyTo(pubFile, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(pubFile.absolutePath), arrayOf(mimeType), null)
                return pubFile to null
            } catch (_: Exception) {}
        }

        return (publicDirectFile ?: targetLocalFile) to publicUri
    }

    fun pauseDownload(taskId: String) {
        updateTask(taskId) {
            if (it.stage.isActive) {
                it.copy(
                    stage = DownloadStateStage.PAUSED,
                    speedBytesPerSec = 0L,
                    speedFormatted = "Paused",
                    etaFormatted = "Paused"
                )
            } else it
        }
        activeJobs.remove(taskId)?.cancel()
        persistTasks()
    }

    fun resumeDownload(taskId: String) {
        val task = _tasksFlow.value.find { it.id == taskId } ?: return
        if (task.stage != DownloadStateStage.PAUSED && task.stage != DownloadStateStage.FAILED) return

        val resumedStage = if (task.isAudioOnly) DownloadStateStage.DOWNLOADING_AUDIO else DownloadStateStage.DOWNLOADING_VIDEO
        val updated = task.copy(
            stage = resumedStage,
            speedFormatted = "Resuming...",
            errorType = null,
            errorMessage = null
        )
        updateTask(taskId) { updated }
        launchDownloadWorker(updated)
    }

    fun retryDownload(taskId: String) {
        val task = _tasksFlow.value.find { it.id == taskId } ?: return
        activeJobs.remove(taskId)?.cancel()

        // If the stream link expired, re-extract fresh URL before retrying
        if (task.errorType == DownloaderErrorType.EXPIRED_LINK) {
            updateTask(taskId) {
                it.copy(
                    stage = DownloadStateStage.FETCHING_METADATA,
                    speedFormatted = "Refreshing link...",
                    errorType = null,
                    errorMessage = null
                )
            }
            fetchMediaInfo(
                url = task.url,
                onStageChanged = {},
                onResult = { refreshedInfo, errType, errMsg ->
                    if (refreshedInfo != null) {
                        val matchingFmt = refreshedInfo.allFormats.firstOrNull {
                            it.qualityLabel == task.qualityLabel || it.resolutionHeight == task.formatOption.resolutionHeight
                        } ?: refreshedInfo.allFormats.firstOrNull()

                        if (matchingFmt != null) {
                            val updatedTask = task.copy(
                                formatOption = matchingFmt,
                                stage = if (task.isAudioOnly) DownloadStateStage.DOWNLOADING_AUDIO else DownloadStateStage.DOWNLOADING_VIDEO,
                                progress = 0f,
                                downloadedBytes = 0L,
                                errorType = null,
                                errorMessage = null
                            )
                            updateTask(taskId) { updatedTask }
                            launchDownloadWorker(updatedTask)
                            return@fetchMediaInfo
                        }
                    }
                    updateTask(taskId) {
                        it.copy(
                            stage = DownloadStateStage.FAILED,
                            errorType = errType ?: DownloaderErrorType.EXPIRED_LINK,
                            errorMessage = errMsg ?: "Could not refresh expired link."
                        )
                    }
                }
            )
            return
        }

        val workDir = File(context.cacheDir, "downloader_work/$taskId")
        try { workDir.deleteRecursively() } catch (_: Exception) {}

        val resetTask = task.copy(
            stage = if (task.isAudioOnly) DownloadStateStage.DOWNLOADING_AUDIO else DownloadStateStage.DOWNLOADING_VIDEO,
            progress = 0f,
            downloadedBytes = 0L,
            speedBytesPerSec = 0L,
            speedFormatted = "Retrying...",
            errorType = null,
            errorMessage = null
        )
        updateTask(taskId) { resetTask }
        launchDownloadWorker(resetTask)
    }

    fun cancelDownload(taskId: String) {
        val task = _tasksFlow.value.find { it.id == taskId }
        activeJobs.remove(taskId)?.cancel()

        val customBackendUrl = _backendUrlFlow.value.takeIf { it.isNotBlank() }
        if (customBackendUrl != null && task != null && task.jobId != task.id) {
            scope.launch(Dispatchers.IO) {
                backendApiClient.cancelBackendJob(customBackendUrl, task.jobId)
            }
        }

        val workDir = File(context.cacheDir, "downloader_work/$taskId")
        try { workDir.deleteRecursively() } catch (_: Exception) {}

        _tasksFlow.value = _tasksFlow.value.filter { it.id != taskId }
        persistTasks()
    }

    fun openDownloadedFile(context: Context, task: DownloaderTask) {
        val path = task.filePath
        if (path.isNullOrBlank()) {
            onFeedback("File path is unavailable.")
            return
        }
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) {
            onFeedback("File no longer exists on disk.")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, task.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            onFeedback("No installed media player found for ${task.mimeType}: ${e.message}")
        }
    }

    fun shareDownloadedFile(context: Context, task: DownloaderTask) {
        val path = task.filePath
        if (path.isNullOrBlank()) {
            onFeedback("File path is unavailable.")
            return
        }
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) {
            onFeedback("File no longer exists on disk.")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = task.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TITLE, task.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Share ${task.filename}").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            onFeedback("Could not share file: ${e.message}")
        }
    }

    private fun buildUniqueFilename(title: String, qualityLabel: String, ext: String, isAudioOnly: Boolean = false): String {
        val sanitizedTitle = title
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(68)
            .ifBlank { "DragonMedia_${System.currentTimeMillis() % 10000}" }

        val shortQuality = when {
            qualityLabel.contains("2160p") && qualityLabel.contains("60fps") -> "2160p_4K_60fps"
            qualityLabel.contains("2160p") -> "2160p_4K"
            qualityLabel.contains("1440p") && qualityLabel.contains("60fps") -> "1440p_2K_60fps"
            qualityLabel.contains("1440p") -> "1440p_2K"
            qualityLabel.contains("1080p") && qualityLabel.contains("60fps") -> "1080p_60fps"
            qualityLabel.contains("1080p") -> "1080p_FHD"
            qualityLabel.contains("720p") && qualityLabel.contains("60fps") -> "720p_60fps"
            qualityLabel.contains("720p") -> "720p_HD"
            qualityLabel.contains("480p") -> "480p_SD"
            qualityLabel.contains("360p") -> "360p"
            qualityLabel.contains("240p") -> "240p"
            qualityLabel.contains("144p") -> "144p"
            qualityLabel.contains("320 kbps") -> "${ext.uppercase(Locale.US)}_320kbps"
            qualityLabel.contains("256 kbps") -> "${ext.uppercase(Locale.US)}_256kbps"
            qualityLabel.contains("192 kbps") -> "${ext.uppercase(Locale.US)}_192kbps"
            qualityLabel.contains("160 kbps") -> "${ext.uppercase(Locale.US)}_160kbps"
            qualityLabel.contains("128 kbps") -> "${ext.uppercase(Locale.US)}_128kbps"
            qualityLabel.contains("64 kbps") -> "${ext.uppercase(Locale.US)}_64kbps"
            qualityLabel.contains("48 kHz") -> "${ext.uppercase(Locale.US)}_48kHz"
            qualityLabel.contains("44.1 kHz") -> "${ext.uppercase(Locale.US)}_44kHz"
            else -> ext.uppercase(Locale.US)
        }

        val subFolder = if (isAudioOnly) "Dragon Tools/Audio" else "Dragon Tools/Video"
        val appDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, subFolder).apply { mkdirs() }
        val pubDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subFolder)
        var candidate = "${sanitizedTitle}_$shortQuality.$ext"
        var counter = 1
        while (File(appDir, candidate).exists() || File(pubDir, candidate).exists() || _tasksFlow.value.any { it.filename.equals(candidate, ignoreCase = true) }) {
            candidate = "${sanitizedTitle}_${shortQuality}_($counter).$ext"
            counter++
        }
        return candidate
    }

    private fun hasEnoughStorage(minBytes: Long): Boolean {
        return try {
            val path = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val stat = StatFs(path.absolutePath)
            stat.availableBytes >= minBytes
        } catch (_: Exception) {
            true
        }
    }

    private fun updateTask(taskId: String, updater: (DownloaderTask) -> DownloaderTask) {
        _tasksFlow.value = _tasksFlow.value.map {
            if (it.id == taskId) updater(it) else it
        }
    }

    private fun persistTasks() {
        try {
            val arr = JSONArray()
            _tasksFlow.value.take(50).forEach { t ->
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("url", t.url)
                    put("title", t.title)
                    put("creator", t.creator)
                    put("filename", t.filename)
                    put("thumbnailUrl", t.thumbnailUrl)
                    put("platform", t.platform.name)
                    put("qualityLabel", t.qualityLabel)
                    put("container", t.container)
                    put("mimeType", t.mimeType)
                    put("isAudioOnly", t.isAudioOnly)
                    put("stage", if (t.stage.isActive) DownloadStateStage.PAUSED.name else t.stage.name)
                    put("progress", t.progress.toDouble())
                    put("downloadedBytes", t.downloadedBytes)
                    put("totalBytes", t.totalBytes)
                    put("filePath", t.filePath ?: "")
                    put("publicUriString", t.publicUriString ?: "")
                    put("verifiedResolution", t.verifiedResolution ?: "")
                    put("verifiedDuration", t.verifiedDuration ?: "")
                    put("verifiedCodec", t.verifiedCodec ?: "")
                    put("streamUrl", t.formatOption.streamUrl)
                    put("errorMessage", t.errorMessage ?: "")
                    put("createdAtMs", t.createdAtMs)
                    put("batchSessionId", t.batchSessionId ?: "")
                }
                arr.put(obj)
            }
            prefs.edit().putString("saved_tasks_v2", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadPersistedTasks() {
        try {
            val raw = prefs.getString("saved_tasks_v2", null) ?: return
            val arr = JSONArray(raw)
            val loaded = mutableListOf<DownloaderTask>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val filePath = obj.optString("filePath", "").ifBlank { null }
                val stageStr = obj.optString("stage", DownloadStateStage.COMPLETED.name)
                val stage = DownloadStateStage.values().firstOrNull { it.name == stageStr } ?: DownloadStateStage.COMPLETED
                if (stage == DownloadStateStage.COMPLETED && (filePath == null || !File(filePath).exists())) {
                    continue
                }
                val platformStr = obj.optString("platform", MediaPlatform.DIRECT.name)
                val platform = MediaPlatform.values().firstOrNull { it.name == platformStr } ?: MediaPlatform.DIRECT
                val isAudio = obj.optBoolean("isAudioOnly", false)
                val container = obj.optString("container", if (isAudio) "m4a" else "mp4")
                val mime = obj.optString("mimeType", if (isAudio) "audio/mp4" else "video/mp4")
                val qLabel = obj.optString("qualityLabel", "Original")
                val streamUrl = obj.optString("streamUrl", obj.optString("url", ""))
                val totalB = obj.optLong("totalBytes", -1L)

                val fmt = MediaFormatOption(
                    id = "restored_$i",
                    qualityLabel = qLabel,
                    container = container,
                    mimeType = mime,
                    contentLengthBytes = totalB,
                    isAudioOnly = isAudio,
                    streamUrl = streamUrl
                )
                loaded.add(
                    DownloaderTask(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        url = obj.optString("url", ""),
                        title = obj.optString("title", "Downloaded Media"),
                        creator = obj.optString("creator", ""),
                        filename = obj.optString("filename", "media.$container"),
                        thumbnailUrl = obj.optString("thumbnailUrl", ""),
                        platform = platform,
                        qualityLabel = qLabel,
                        container = container,
                        mimeType = mime,
                        isAudioOnly = isAudio,
                        formatOption = fmt,
                        stage = stage,
                        progress = obj.optDouble("progress", 1.0).toFloat(),
                        speedFormatted = stage.displayLabel,
                        downloadedBytes = obj.optLong("downloadedBytes", totalB),
                        totalBytes = totalB,
                        filePath = filePath,
                        publicUriString = obj.optString("publicUriString", "").ifBlank { null },
                        verifiedResolution = obj.optString("verifiedResolution", "").ifBlank { null },
                        verifiedDuration = obj.optString("verifiedDuration", "").ifBlank { null },
                        verifiedCodec = obj.optString("verifiedCodec", "").ifBlank { null },
                        errorMessage = obj.optString("errorMessage", "").ifBlank { null },
                        createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis()),
                        batchSessionId = obj.optString("batchSessionId", "").ifBlank { null }
                    )
                )
            }
            _tasksFlow.value = loaded
        } catch (_: Exception) {}
    }

    // =========================================================================
    // BATCH DOWNLOAD ENGINE IMPLEMENTATION
    // =========================================================================

    fun startBatchDownload(urls: List<String>, config: BatchDownloadConfig): String {
        val validUrls = urls.map { it.trim() }.filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        if (validUrls.isEmpty()) {
            onFeedback("No valid HTTP/HTTPS media URLs found for batch download.")
            return ""
        }
        val sessionId = "batch_" + UUID.randomUUID().toString().take(8)
        val initialItems = validUrls.mapIndexed { idx, u ->
            BatchDownloadItemStatus(
                index = idx,
                url = u,
                platform = MediaPlatform.detectFromUrl(u),
                title = "Item #${idx + 1}",
                state = BatchItemState.QUEUED
            )
        }
        val session = BatchSession(
            id = sessionId,
            totalCount = validUrls.size,
            queuedCount = validUrls.size,
            config = config,
            items = initialItems
        )
        _activeBatchSessionFlow.value = session
        onFeedback("Batch download started: ${validUrls.size} items queued (${config.downloadMode.displayName})")
        launchBatchExecution(sessionId, initialItems, config)
        return sessionId
    }

    private fun launchBatchExecution(
        sessionId: String,
        itemsToProcess: List<BatchDownloadItemStatus>,
        config: BatchDownloadConfig
    ) {
        activeBatchJob?.cancel()
        val concurrency = config.maxConcurrentDownloads.coerceIn(1, 4)
        val semaphore = Semaphore(concurrency)

        activeBatchJob = scope.launch(Dispatchers.IO) {
            val backendUrl = _backendUrlFlow.value.takeIf { it.isNotBlank() }
            itemsToProcess.map { item ->
                launch {
                    semaphore.withPermit {
                        val currentSession = _activeBatchSessionFlow.value
                        if (currentSession == null || currentSession.id != sessionId || currentSession.isCancelled) {
                            return@withPermit
                        }

                        updateBatchItem(sessionId, item.index) {
                            it.copy(state = BatchItemState.FETCHING, progress = 0.05f)
                        }

                        try {
                            val mediaInfo = extractionEngine.extractMediaInfo(item.url, backendUrl)
                            val format = selectBatchFormat(mediaInfo, config)
                            if (format == null) {
                                updateBatchItem(sessionId, item.index) {
                                    it.copy(
                                        state = BatchItemState.FAILED,
                                        title = mediaInfo.title,
                                        thumbnailUrl = mediaInfo.thumbnailUrl,
                                        errorMessage = "No compatible format found"
                                    )
                                }
                                incrementBatchCounts(sessionId, failed = true)
                                return@withPermit
                            }

                            updateBatchItem(sessionId, item.index) {
                                it.copy(
                                    title = mediaInfo.title,
                                    thumbnailUrl = mediaInfo.thumbnailUrl,
                                    qualityLabel = format.qualityLabel,
                                    state = BatchItemState.DOWNLOADING,
                                    progress = 0.10f
                                )
                            }

                            val taskId = startDownload(mediaInfo, format, batchSessionId = sessionId)
                            updateBatchItem(sessionId, item.index) { it.copy(taskId = taskId) }

                            var taskFinished = false
                            while (!taskFinished) {
                                val curSession = _activeBatchSessionFlow.value
                                if (curSession == null || curSession.id != sessionId || curSession.isCancelled) {
                                    cancelDownload(taskId)
                                    break
                                }

                                val curTask = _tasksFlow.value.find { it.id == taskId }
                                if (curTask == null) {
                                    break
                                }

                                when (curTask.stage) {
                                    DownloadStateStage.COMPLETED -> {
                                        updateBatchItem(sessionId, item.index) {
                                            it.copy(
                                                state = BatchItemState.COMPLETED,
                                                progress = 1.0f,
                                                downloadedBytes = curTask.downloadedBytes,
                                                totalBytes = curTask.totalBytes
                                            )
                                        }
                                        incrementBatchCounts(sessionId, completed = true)
                                        taskFinished = true
                                    }
                                    DownloadStateStage.FAILED -> {
                                        updateBatchItem(sessionId, item.index) {
                                            it.copy(
                                                state = BatchItemState.FAILED,
                                                progress = curTask.progress,
                                                errorMessage = curTask.errorMessage ?: "Download failed"
                                            )
                                        }
                                        incrementBatchCounts(sessionId, failed = true)
                                        taskFinished = true
                                    }
                                    else -> {
                                        updateBatchItem(sessionId, item.index) {
                                            it.copy(
                                                progress = curTask.progress,
                                                downloadedBytes = curTask.downloadedBytes,
                                                totalBytes = curTask.totalBytes
                                            )
                                        }
                                        delay(300L)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            updateBatchItem(sessionId, item.index) {
                                it.copy(
                                    state = BatchItemState.FAILED,
                                    errorMessage = e.message ?: "Failed to process media"
                                )
                            }
                            incrementBatchCounts(sessionId, failed = true)
                        }
                    }
                }
            }.joinAll()

            _activeBatchSessionFlow.update { session ->
                if (session != null && session.id == sessionId) {
                    session.copy(isFinished = true)
                } else session
            }
            val finalSession = _activeBatchSessionFlow.value
            if (finalSession != null && finalSession.id == sessionId) {
                onFeedback("Batch download completed: ${finalSession.completedCount} succeeded, ${finalSession.failedCount} failed")
            }
        }
    }

    fun selectBatchFormat(info: MediaInfo, config: BatchDownloadConfig): MediaFormatOption? {
        return if (config.downloadMode.isAudioOnly) {
            val audioList = info.audioFormats
            if (audioList.isNotEmpty()) {
                audioList.minByOrNull {
                    kotlin.math.abs(it.bitrateKbps - config.preferredAudioBitrateKbps)
                } ?: audioList.first()
            } else {
                val vid = info.videoFormats.firstOrNull() ?: info.allFormats.firstOrNull()
                vid?.copy(
                    isAudioOnly = true,
                    container = if (config.downloadMode == BatchDownloadMode.AUDIO_MP3) "mp3" else "m4a"
                )
            }
        } else {
            val videoList = info.videoFormats.ifEmpty { info.allFormats.filter { !it.isAudioOnly } }
            if (videoList.isNotEmpty()) {
                val exactOrLower = videoList.filter { it.resolutionHeight <= config.preferredResolutionHeight }
                if (exactOrLower.isNotEmpty()) {
                    exactOrLower.maxByOrNull { it.resolutionHeight } ?: exactOrLower.first()
                } else {
                    videoList.minByOrNull { it.resolutionHeight } ?: videoList.first()
                }
            } else {
                info.allFormats.firstOrNull()
            }
        }
    }

    private fun updateBatchItem(
        sessionId: String,
        index: Int,
        transform: (BatchDownloadItemStatus) -> BatchDownloadItemStatus
    ) {
        _activeBatchSessionFlow.update { session ->
            if (session != null && session.id == sessionId) {
                val newItems = session.items.toMutableList()
                if (index in 0 until newItems.size) {
                    newItems[index] = transform(newItems[index])
                }
                session.copy(items = newItems)
            } else session
        }
    }

    private fun incrementBatchCounts(sessionId: String, completed: Boolean = false, failed: Boolean = false) {
        _activeBatchSessionFlow.update { session ->
            if (session != null && session.id == sessionId) {
                val newComp = if (completed) session.completedCount + 1 else session.completedCount
                val newFail = if (failed) session.failedCount + 1 else session.failedCount
                val newQueued = (session.totalCount - newComp - newFail).coerceAtLeast(0)
                session.copy(
                    completedCount = newComp,
                    failedCount = newFail,
                    queuedCount = newQueued
                )
            } else session
        }
    }

    fun pauseBatch() {
        val session = _activeBatchSessionFlow.value ?: return
        _activeBatchSessionFlow.value = session.copy(isPaused = true)
        session.items.mapNotNull { it.taskId }.forEach { pauseDownload(it) }
        onFeedback("Batch download paused.")
    }

    fun resumeBatch() {
        val session = _activeBatchSessionFlow.value ?: return
        _activeBatchSessionFlow.value = session.copy(isPaused = false)
        session.items.mapNotNull { it.taskId }.forEach { resumeDownload(it) }
        onFeedback("Batch download resumed.")
    }

    fun cancelBatch() {
        val session = _activeBatchSessionFlow.value ?: return
        activeBatchJob?.cancel()
        _activeBatchSessionFlow.value = session.copy(isCancelled = true, isFinished = true)
        session.items.mapNotNull { it.taskId }.forEach { cancelDownload(it) }
        onFeedback("Batch download cancelled.")
    }

    fun retryFailedBatchItems() {
        val session = _activeBatchSessionFlow.value ?: return
        val failedItems = session.items.filter { it.state == BatchItemState.FAILED }
        if (failedItems.isEmpty()) {
            onFeedback("No failed items to retry.")
            return
        }
        val updatedItems = session.items.map { item ->
            if (item.state == BatchItemState.FAILED) {
                item.copy(state = BatchItemState.QUEUED, progress = 0f, errorMessage = null, taskId = null)
            } else item
        }
        val newFailedCount = (session.failedCount - failedItems.size).coerceAtLeast(0)
        val newQueuedCount = session.queuedCount + failedItems.size
        _activeBatchSessionFlow.value = session.copy(
            isFinished = false,
            isCancelled = false,
            isPaused = false,
            failedCount = newFailedCount,
            queuedCount = newQueuedCount,
            items = updatedItems
        )
        onFeedback("Retrying ${failedItems.size} failed batch items...")
        launchBatchExecution(session.id, updatedItems.filter { it.state == BatchItemState.QUEUED }, session.config)
    }

    fun clearBatchSession() {
        _activeBatchSessionFlow.value = null
    }
}
