package com.example.data.downloader

import com.example.data.model.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Client for communicating with the Universal Media Downloader Backend API:
 * - POST /api/media/info
 * - POST /api/media/download
 * - GET  /api/media/status/:jobId
 * - GET  /api/media/file/:jobId
 * - POST /api/media/cancel/:jobId
 */
class BackendApiClient(
    private val httpClient: OkHttpClient
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    data class BackendJobInitResponse(
        val jobId: String,
        val status: String,
        val streamFileUrl: String
    )

    data class BackendJobStatusResponse(
        val jobId: String,
        val status: String, // downloading, processing, merging, validating, completed, failed, cancelled
        val progress: Float,
        val speedBytesPerSec: Long,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val filename: String?,
        val mimeType: String?,
        val error: String?
    )

    fun fetchMediaInfoFromBackend(baseUrl: String, targetUrl: String): MediaInfo {
        val cleanBase = baseUrl.trim().removeSuffix("/")
        val payload = JSONObject().apply {
            put("url", targetUrl)
        }
        val request = Request.Builder()
            .url("$cleanBase/api/media/info")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val errMsg = try {
                    JSONObject(bodyStr).optString("error", "Backend HTTP ${response.code}")
                } catch (_: Exception) {
                    "Backend returned HTTP ${response.code}"
                }
                throw IOException(errMsg)
            }

            val root = JSONObject(bodyStr)
            val platformStr = root.optString("platform", "")
            val platform = MediaPlatform.values().firstOrNull {
                it.name.equals(platformStr, ignoreCase = true) ||
                    it.displayName.equals(platformStr, ignoreCase = true)
            } ?: MediaPlatform.detectFromUrl(targetUrl)

            val title = root.optString("title", "").ifBlank {
                throw IOException("Backend returned empty media title")
            }
            val creator = root.optString("creator", root.optString("uploader", "Unknown Creator"))
            val durationSeconds = root.optLong("durationSeconds", root.optLong("duration", 0L))
            val thumbnailUrl = root.optString("thumbnailUrl", root.optString("thumbnail", ""))
            val canonicalUrl = root.optString("canonicalUrl", targetUrl)

            val videoFormats = parseFormatsArray(
                cleanBase = cleanBase,
                targetUrl = canonicalUrl,
                arr = root.optJSONArray("videoFormats") ?: JSONArray(),
                isAudioDefault = false
            )
            val audioFormats = parseFormatsArray(
                cleanBase = cleanBase,
                targetUrl = canonicalUrl,
                arr = root.optJSONArray("audioFormats") ?: JSONArray(),
                isAudioDefault = true
            )

            if (videoFormats.isEmpty() && audioFormats.isEmpty()) {
                val combined = parseFormatsArray(
                    cleanBase = cleanBase,
                    targetUrl = canonicalUrl,
                    arr = root.optJSONArray("formats") ?: JSONArray(),
                    isAudioDefault = false
                )
                return MediaInfo(
                    originalUrl = targetUrl,
                    canonicalUrl = canonicalUrl,
                    title = title,
                    creator = creator,
                    durationSeconds = durationSeconds,
                    durationFormatted = formatDurationSeconds(durationSeconds),
                    thumbnailUrl = thumbnailUrl,
                    platform = platform,
                    videoFormats = combined.filter { !it.isAudioOnly },
                    audioFormats = combined.filter { it.isAudioOnly },
                    extractorSource = "Backend API ($cleanBase)"
                )
            }

            return MediaInfo(
                originalUrl = targetUrl,
                canonicalUrl = canonicalUrl,
                title = title,
                creator = creator,
                durationSeconds = durationSeconds,
                durationFormatted = formatDurationSeconds(durationSeconds),
                thumbnailUrl = thumbnailUrl,
                platform = platform,
                videoFormats = videoFormats,
                audioFormats = audioFormats,
                extractorSource = "Backend API ($cleanBase)"
            )
        }
    }

    fun startBackendDownloadJob(
        baseUrl: String,
        targetUrl: String,
        formatOption: MediaFormatOption
    ): BackendJobInitResponse {
        val cleanBase = baseUrl.trim().removeSuffix("/")
        val payload = JSONObject().apply {
            put("url", targetUrl)
            put("formatId", formatOption.id)
            put("qualityLabel", formatOption.qualityLabel)
            put("container", formatOption.container)
            put("isAudioOnly", formatOption.isAudioOnly)
        }
        val request = Request.Builder()
            .url("$cleanBase/api/media/download")
            .post(payload.toString().toRequestBody(jsonMediaType))
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val errMsg = try {
                    JSONObject(bodyStr).optString("error", "Backend HTTP ${response.code}")
                } catch (_: Exception) {
                    "Backend download start failed: HTTP ${response.code}"
                }
                throw IOException(errMsg)
            }
            val json = JSONObject(bodyStr)
            val jobId = json.getString("jobId")
            return BackendJobInitResponse(
                jobId = jobId,
                status = json.optString("status", "downloading"),
                streamFileUrl = "$cleanBase/api/media/file/$jobId"
            )
        }
    }

    fun getBackendJobStatus(baseUrl: String, jobId: String): BackendJobStatusResponse {
        val cleanBase = baseUrl.trim().removeSuffix("/")
        val request = Request.Builder()
            .url("$cleanBase/api/media/status/$jobId")
            .get()
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(request).execute().use { response ->
            val bodyStr = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("Status check failed: HTTP ${response.code}")
            }
            val json = JSONObject(bodyStr)
            return BackendJobStatusResponse(
                jobId = jobId,
                status = json.optString("status", "downloading"),
                progress = json.optDouble("progress", 0.0).toFloat(),
                speedBytesPerSec = json.optLong("speedBytesPerSec", 0L),
                downloadedBytes = json.optLong("downloadedBytes", 0L),
                totalBytes = json.optLong("totalBytes", -1L),
                filename = json.optString("filename", "").ifBlank { null },
                mimeType = json.optString("mimeType", "").ifBlank { null },
                error = json.optString("error", "").ifBlank { null }
            )
        }
    }

    fun cancelBackendJob(baseUrl: String, jobId: String): Boolean {
        val cleanBase = baseUrl.trim().removeSuffix("/")
        val request = Request.Builder()
            .url("$cleanBase/api/media/cancel/$jobId")
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        return try {
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    private fun parseFormatsArray(
        cleanBase: String,
        targetUrl: String,
        arr: JSONArray,
        isAudioDefault: Boolean
    ): List<MediaFormatOption> {
        val list = mutableListOf<MediaFormatOption>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "fmt_$i")
            val isAudioOnly = obj.optBoolean("isAudioOnly", isAudioDefault)
            val container = obj.optString("container", if (isAudioOnly) "m4a" else "mp4")
            val qualityLabel = obj.optString("qualityLabel", if (isAudioOnly) "Audio ($container)" else "Video ($container)")
            val height = obj.optInt("resolutionHeight", obj.optInt("height", 0))
            val width = obj.optInt("width", 0)
            val fps = obj.optInt("fps", 30)
            val bitrateKbps = obj.optInt("bitrateKbps", obj.optInt("bitrate", 0))
            val contentLength = obj.optLong("contentLengthBytes", obj.optLong("filesize", -1L))
            val streamUrl = obj.optString("streamUrl", "$cleanBase/api/media/stream?url=$targetUrl&formatId=$id")
            list.add(
                MediaFormatOption(
                    id = id,
                    qualityLabel = qualityLabel,
                    resolutionHeight = height,
                    width = width,
                    fps = fps,
                    container = container,
                    mimeType = obj.optString("mimeType", if (isAudioOnly) "audio/$container" else "video/$container"),
                    videoCodec = obj.optString("videoCodec", "").ifBlank { null },
                    audioCodec = obj.optString("audioCodec", "").ifBlank { null },
                    bitrateKbps = bitrateKbps,
                    bitrateLabel = obj.optString("bitrateLabel", if (bitrateKbps > 0) "$bitrateKbps kbps" else ""),
                    contentLengthBytes = contentLength,
                    isEstimatedSize = obj.optBoolean("isEstimatedSize", contentLength <= 0L),
                    isAudioOnly = isAudioOnly,
                    needsMerging = obj.optBoolean("needsMerging", false),
                    isLossless = obj.optBoolean("isLossless", container.equals("wav", true) || container.equals("flac", true)),
                    streamUrl = streamUrl
                )
            )
        }
        return list
    }
}
