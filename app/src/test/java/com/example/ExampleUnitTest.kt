package com.example

import com.example.data.downloader.MediaProcessingEngine
import com.example.data.model.BatteryProfile
import com.example.data.model.MediaPlatform
import com.example.data.model.formatBytes
import com.example.data.model.formatDurationSeconds
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testBatteryProfilesExist() {
        val profiles = BatteryProfile.values()
        assertTrue(profiles.isNotEmpty())
        val balanced = profiles.first { it.name == "BALANCED" }
        val perf = profiles.first { it.name == "PERFORMANCE" }
        val gaming = profiles.first { it.name == "GAMING" }

        assertEquals(40.0f, balanced.thermalLimitC, 0.01f)
        assertEquals(43.0f, perf.thermalLimitC, 0.01f)
        assertEquals(0.0f, gaming.thermalLimitC, 0.01f)
        assertTrue(gaming.isUncapped)
    }

    @Test
    fun testPlatformDetectionAllSupportedPlatforms() {
        assertEquals(
            MediaPlatform.YOUTUBE,
            MediaPlatform.detectFromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            MediaPlatform.YOUTUBE,
            MediaPlatform.detectFromUrl("https://youtu.be/dQw4w9WgXcQ")
        )
        assertEquals(
            MediaPlatform.YOUTUBE_SHORTS,
            MediaPlatform.detectFromUrl("https://www.youtube.com/shorts/abc123def45")
        )
        assertEquals(
            MediaPlatform.TIKTOK,
            MediaPlatform.detectFromUrl("https://vm.tiktok.com/ZM8abcde/")
        )
        assertEquals(
            MediaPlatform.SNAPCHAT,
            MediaPlatform.detectFromUrl("https://story.snapchat.com/s/sample")
        )
        assertEquals(
            MediaPlatform.FACEBOOK,
            MediaPlatform.detectFromUrl("https://www.facebook.com/reel/123456789")
        )
        assertEquals(
            MediaPlatform.INSTAGRAM,
            MediaPlatform.detectFromUrl("https://www.instagram.com/reel/C12345678/")
        )
        assertEquals(
            MediaPlatform.TWITTER,
            MediaPlatform.detectFromUrl("https://x.com/user/status/1234567890")
        )
        assertEquals(
            MediaPlatform.REDDIT,
            MediaPlatform.detectFromUrl("https://www.reddit.com/r/videos/comments/abc123/sample/")
        )
        assertEquals(
            MediaPlatform.PINTEREST,
            MediaPlatform.detectFromUrl("https://www.pinterest.com/pin/123456789/")
        )
        assertEquals(
            MediaPlatform.VIMEO,
            MediaPlatform.detectFromUrl("https://vimeo.com/123456789")
        )
        assertEquals(
            MediaPlatform.DIRECT,
            MediaPlatform.detectFromUrl("https://cdn.example.org/media/sample_video.mp4")
        )
        assertEquals(
            MediaPlatform.DIRECT,
            MediaPlatform.detectFromUrl("https://cdn.example.org/audio/track.mp3")
        )
        assertEquals(
            MediaPlatform.UNSUPPORTED,
            MediaPlatform.detectFromUrl("")
        )
    }

    @Test
    fun testMediaValidationRejectsHtmlAndAcceptsValidMp4Container() {
        val htmlTemp = File.createTempFile("fake_video", ".mp4")
        try {
            htmlTemp.writeText("<!DOCTYPE html><html><head><title>Error</title></head><body>Not a video".repeat(20))
            val badReport = MediaProcessingEngine.validateMediaFile(htmlTemp, expectedAudioOnly = false)
            assertFalse("HTML disguised as MP4 must be rejected", badReport.isValid)
        } finally {
            htmlTemp.delete()
        }

        val validMp4Temp = File.createTempFile("valid_video", ".mp4")
        try {
            val bytes = ByteArray(1024)
            // ISO BMFF ftyp box header at offset 4..7
            bytes[3] = 24
            bytes[4] = 'f'.code.toByte()
            bytes[5] = 't'.code.toByte()
            bytes[6] = 'y'.code.toByte()
            bytes[7] = 'p'.code.toByte()
            bytes[8] = 'i'.code.toByte()
            bytes[9] = 's'.code.toByte()
            bytes[10] = 'o'.code.toByte()
            bytes[11] = 'm'.code.toByte()
            validMp4Temp.writeBytes(bytes)

            val goodReport = MediaProcessingEngine.validateMediaFile(validMp4Temp, expectedAudioOnly = false)
            assertTrue("Valid MP4 ftyp header should pass magic container validation", goodReport.isValid)
            assertEquals(1024L, goodReport.fileSizeBytes)
        } finally {
            validMp4Temp.delete()
        }
    }

    @Test
    fun testFormattingHelpers() {
        assertEquals("1.50 MB", formatBytes(1572864L))
        assertEquals("3:45", formatDurationSeconds(225L))
        assertEquals("1:02:05", formatDurationSeconds(3725L))
    }

    @Test
    fun testDetailedFormatEnrichmentAndPlayableAudioSynthesis() {
        val client = okhttp3.OkHttpClient()
        val backend = com.example.data.downloader.BackendApiClient(client)
        val engine = com.example.data.downloader.MediaExtractionEngine(client, backend, null)

        val baseInfo = com.example.data.model.MediaInfo(
            originalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            canonicalUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            title = "Sample Video",
            creator = "Sample Creator",
            durationSeconds = 60L,
            durationFormatted = "1:00",
            thumbnailUrl = "",
            platform = MediaPlatform.YOUTUBE,
            videoFormats = emptyList(),
            audioFormats = emptyList()
        )

        val enriched = engine.enrichMediaInfoWithDetailedFormats(baseInfo)
        assertTrue("Expected at least 15 detailed video formats", enriched.videoFormats.size >= 15)
        assertTrue("Expected at least 15 detailed audio formats", enriched.audioFormats.size >= 15)

        val videoContainers = enriched.videoFormats.map { it.container }.toSet()
        assertTrue(videoContainers.containsAll(listOf("mp4", "webm", "mkv")))

        val audioContainers = enriched.audioFormats.map { it.container }.toSet()
        assertTrue(audioContainers.containsAll(listOf("m4a", "mp3", "wav", "flac", "aac", "ogg", "webm")))

        val wavTemp = File.createTempFile("synth_test", ".wav")
        try {
            val ok = MediaProcessingEngine.synthesizePlayableAudioFile(
                outputFile = wavTemp,
                container = "wav",
                sampleRateHz = 44100,
                channels = 2,
                durationSeconds = 4L,
                bitrateKbps = 1411
            )
            assertTrue("Synthesized WAV should succeed", ok)
            val validation = MediaProcessingEngine.validateMediaFile(wavTemp, expectedAudioOnly = true)
            assertTrue("Synthesized WAV must pass validation", validation.isValid)
        } finally {
            wavTemp.delete()
        }
    }
}
