package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.adblock.AdBlockerEngine
import com.example.data.model.SocialPlatform
import com.example.data.repository.KernelRepository

class DragonAccessibilityMonitorService : AccessibilityService() {

    private val TAG = "DragonAccessibility"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastAdScanTimestampMs: Long = 0L

    @Volatile
    private var lastAutoSwipeTimestampMs: Long = 0L

    @Volatile
    private var lastSkipTapTimestampMs: Long = 0L

    @Volatile
    private var isScanInProgress: Boolean = false

    @Volatile
    private var isTrailingScanScheduled: Boolean = false

    @Volatile
    private var isAdAudioMutedByShield: Boolean = false

    private var lastKnownForegroundPkg: String = ""

    private val trailingScanRunnable = Runnable {
        isTrailingScanScheduled = false
        val pkg = lastKnownForegroundPkg
        if (pkg.isNotEmpty()) {
            runAdShieldScanForPackage(pkg)
        }
    }

    // Strips zero-width spaces, joiners, directional marks, and non-breaking spaces used by Facebook/IG to obfuscate "Sponsored"
    private val INVISIBLE_CHARS_REGEX = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]")
    private val NBSP_REGEX = Regex("\\u00A0")

    // Official YouTube, Facebook Watch, TikTok, Instagram & Twitch Skip/Dismiss Ad View IDs
    private val SKIP_AD_VIEW_IDS = listOf(
        "com.google.android.youtube:id/skip_ad_button",
        "com.google.android.youtube:id/modern_skip_ad_button",
        "com.google.android.youtube:id/skip_ad_button_container",
        "com.google.android.youtube:id/ad_skip_button",
        "com.google.android.apps.youtube.music:id/skip_ad_button"
    )

    // Official In-Video Ad Indicator View IDs (strictly inside video player)
    private val YOUTUBE_IN_VIDEO_AD_VIEW_IDS = listOf(
        "com.google.android.youtube:id/ad_progress_text",
        "com.google.android.youtube:id/ad_countdown",
        "com.google.android.youtube:id/player_learn_more_button"
    )

    // Platform-specific Sponsored Feed & Companion Ad View IDs
    private val PLATFORM_SPONSORED_VIEW_IDS = mapOf(
        SocialPlatform.INSTAGRAM to listOf(
            "com.instagram.android:id/secondary_label",
            "com.instagram.android:id/row_feed_photo_profile_metalabel",
            "com.instagram.android:id/ad_cta_button",
            "com.instagram.android:id/clips_ad_cta",
            "com.instagram.android:id/sponsored_label"
        ),
        SocialPlatform.YOUTUBE to listOf(
            "com.google.android.youtube:id/ad_attribution",
            "com.google.android.youtube:id/ad_badge",
            "com.google.android.youtube:id/ad_cta_button",
            "com.google.android.youtube:id/sparkles_ad",
            "com.google.android.youtube:id/promoted_video"
        ),
        SocialPlatform.TIKTOK to listOf(
            "com.zhiliaoapp.musically:id/ad_tag",
            "com.ss.android.ugc.trill:id/ad_tag"
        )
    )

    // View ID substrings that identify an ad or sponsored container inside any social app
    private val GENERIC_SPONSORED_ID_SUBSTRINGS = listOf(
        "ad_badge",
        "ad_attribution",
        "ad_cta_button",
        "clips_ad_cta",
        "sponsored_label",
        "promoted_video",
        "sparkles_ad",
        "ad_tag",
        "commerce_ad",
        "feed_ad"
    )

    private var lastHandledAdSignature: String = ""
    private var lastHandledAdSignatureTimeMs: Long = 0L

    // Matches "Sponsored", "Promoted", "Paid partnership", "Ad ·" inside long Litho contentDescriptions
    private val SPONSORED_SEGMENT_REGEX = Regex(
        pattern = "(^|\\n|\\s*[·•\\-|.]\\s*)(sponsored|promoted|paid partnership|sponsored reel|sponsored post)(\\s*[·•\\-|.]|\\s*$|\\n)|(^|\\n|\\s)(ad\\s*[·•]\\s*\\d*:?\\d*)",
        option = RegexOption.IGNORE_CASE
    )

    // Matches in-video ad countdowns like "Skip in 5", "Ad 1 of 2", "Ad · 0:15", "Sponsored · 0:"
    private val VIDEO_AD_COUNTDOWN_REGEX = Regex(
        pattern = "(skip in \\d+|ad \\d+ of \\d+|ad\\s*[·•]\\s*\\d+:\\d+|sponsored\\s*[·•]\\s*\\d+:\\d+)",
        option = RegexOption.IGNORE_CASE
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Dragon Accessibility Monitor Service Connected.")
        KernelRepository.getInstance(applicationContext).onAccessibilityServiceStateChanged(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            if (pkg == applicationContext.packageName && !className.contains("MainActivity", ignoreCase = true)) {
                return
            }
            if (pkg != lastKnownForegroundPkg) {
                if (isAdAudioMutedByShield) {
                    restoreMutedAudio()
                }
                lastKnownForegroundPkg = pkg
                lastHandledAdSignature = ""
                KernelRepository.getInstance(applicationContext).onForegroundAppChanged(pkg, className)
            }
        } else if (AdBlockerEngine.detectSocialPlatform(pkg) != null) {
            lastKnownForegroundPkg = pkg
        }

        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastAdScanTimestampMs
        if (isScanInProgress || elapsed < 260L) {
            if (!isTrailingScanScheduled && AdBlockerEngine.detectSocialPlatform(pkg) != null) {
                isTrailingScanScheduled = true
                val delay = (280L - elapsed).coerceIn(90L, 280L)
                mainHandler.postDelayed(trailingScanRunnable, delay)
            }
            return
        }

        runAdShieldScanForPackage(pkg)
    }

    private fun scheduleFollowUpScan(delayMs: Long = 360L) {
        mainHandler.removeCallbacks(trailingScanRunnable)
        isTrailingScanScheduled = true
        mainHandler.postDelayed(trailingScanRunnable, delayMs)
    }

    private fun runAdShieldScanForPackage(pkg: String) {
        if (isScanInProgress) return

        val socialPlatform = AdBlockerEngine.detectSocialPlatform(pkg)
        if (socialPlatform == null) {
            if (isAdAudioMutedByShield) restoreMutedAudio()
            return
        }

        val repo = KernelRepository.getInstance(applicationContext)
        val adConfig = repo.adBlockerConfigFlow.value
        val adTelem = repo.adBlockerTelemetryFlow.value

        if (!adConfig.isGlobalEnabled && !adTelem.isActive) return
        if (adConfig.platformToggles[socialPlatform.id] == false) return
        if (!adConfig.autoSkipInAppSocialAds && !adConfig.autoSwipeSponsoredReels) return

        lastAdScanTimestampMs = SystemClock.elapsedRealtime()
        isScanInProgress = true

        val rootNode = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        if (rootNode == null) {
            isScanInProgress = false
            return
        }

        try {
            val metrics = resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            // STEP 1: Handle In-Video Ads (Skip button tap, countdown fast-forward, & ad audio mute)
            if (adConfig.autoSkipInAppSocialAds) {
                val handledVideoAd = handleInVideoAds(rootNode, socialPlatform, screenWidth, screenHeight, repo)
                if (handledVideoAd) {
                    scheduleFollowUpScan(320L)
                    return
                }
            }

            // STEP 2: Hide / Dismiss Sponsored Feed Ads & Companion Panels in-place (NEVER auto-scroll!)
            if (adConfig.autoSwipeSponsoredReels || adConfig.autoSkipInAppSocialAds) {
                handleSponsoredFeedAndCompanionAdsInPlace(rootNode, socialPlatform, screenWidth, screenHeight, repo)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ad shield scan error: ${e.message}")
        } finally {
            isScanInProgress = false
        }
    }

    private fun deobfuscateText(raw: CharSequence?): String {
        if (raw.isNullOrEmpty()) return ""
        return raw.toString()
            .replace(INVISIBLE_CHARS_REGEX, "")
            .replace(NBSP_REGEX, " ")
            .trim()
    }

    /**
     * Detects and eliminates In-Video Ads across YouTube, Facebook Watch/Reels, Instagram, TikTok, and Twitch.
     */
    private fun handleInVideoAds(
        rootNode: AccessibilityNodeInfo,
        platform: SocialPlatform,
        screenWidth: Int,
        screenHeight: Int,
        repo: KernelRepository
    ): Boolean {
        // 1A. Check official YouTube / YT Music Skip button View IDs
        if (platform == SocialPlatform.YOUTUBE) {
            for (viewId in SKIP_AD_VIEW_IDS) {
                val nodes = try {
                    rootNode.findAccessibilityNodeInfosByViewId(viewId)
                } catch (_: Exception) {
                    null
                }
                if (!nodes.isNullOrEmpty()) {
                    for (node in nodes) {
                        if (node.isVisibleToUser && triggerSkipButtonClickAndTap(node)) {
                            restoreMutedAudio()
                            repo.recordRealSocialAdIntercept(
                                platform = platform,
                                descriptor = "YouTube Video Ad Skipped",
                                action = "VIDEO_AD_SKIPPED"
                            )
                            return true
                        }
                    }
                }
            }
        }

        // 1B. Check "Skip" / "Skip ad" / "Skip ads" / "Dismiss ad" across all platforms
        val skipCandidates = try {
            rootNode.findAccessibilityNodeInfosByText("Skip")
        } catch (_: Exception) {
            null
        }
        var isCountdownVideoAdPlaying = false

        if (!skipCandidates.isNullOrEmpty()) {
            for (node in skipCandidates) {
                if (!node.isVisibleToUser) continue
                val txt = deobfuscateText(node.text)
                val desc = deobfuscateText(node.contentDescription)
                val combined = "$txt $desc"

                if (isSkipButtonLabel(txt) || isSkipButtonLabel(desc)) {
                    if (triggerSkipButtonClickAndTap(node)) {
                        restoreMutedAudio()
                        repo.recordRealSocialAdIntercept(
                            platform = platform,
                            descriptor = "${platform.shortName} Video Ad ($txt)",
                            action = "VIDEO_AD_SKIPPED"
                        )
                        return true
                    }
                } else if (VIDEO_AD_COUNTDOWN_REGEX.containsMatchIn(combined)) {
                    isCountdownVideoAdPlaying = true
                }
            }
        }

        // 1C. Check if an unskippable or countdown In-Video Ad is currently playing inside top video player (top 44% of screen)
        if (!isCountdownVideoAdPlaying && platform == SocialPlatform.YOUTUBE) {
            val playerMaxBottom = (screenHeight * 0.44f).toInt()
            for (adViewId in YOUTUBE_IN_VIDEO_AD_VIEW_IDS) {
                val adNodes = try {
                    rootNode.findAccessibilityNodeInfosByViewId(adViewId)
                } catch (_: Exception) {
                    null
                }
                if (!adNodes.isNullOrEmpty() && adNodes.any { it.isVisibleToUser }) {
                    isCountdownVideoAdPlaying = true
                    break
                }
            }

            // Also check for "Sponsored · 0:15", "Ad 1 of 2", "Ad ·", or "Visit advertiser" inside the top video player
            if (!isCountdownVideoAdPlaying) {
                for (playerAdQuery in listOf("Sponsored", "Ad 1 of", "Ad 2 of", "Ad ·", "Visit advertiser")) {
                    val pNodes = try {
                        rootNode.findAccessibilityNodeInfosByText(playerAdQuery)
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    for (pNode in pNodes) {
                        if (!pNode.isVisibleToUser) continue
                        val b = Rect()
                        pNode.getBoundsInScreen(b)
                        if (b.top >= 0 && b.bottom in 10..playerMaxBottom) {
                            val combined = "${deobfuscateText(pNode.text)} ${deobfuscateText(pNode.contentDescription)}"
                            if (VIDEO_AD_COUNTDOWN_REGEX.containsMatchIn(combined) ||
                                combined.contains("Visit advertiser", ignoreCase = true) ||
                                combined.contains("Sponsored ·", ignoreCase = true) ||
                                combined.contains("Sponsored •", ignoreCase = true) ||
                                combined.trim().equals("Sponsored", ignoreCase = true)
                            ) {
                                isCountdownVideoAdPlaying = true
                                break
                            }
                        }
                    }
                    if (isCountdownVideoAdPlaying) break
                }
            }
        }

        if (isCountdownVideoAdPlaying) {
            muteAdAudioTemporary()
            if (fastForwardActiveVideoAdSeekBar(rootNode, screenHeight)) {
                repo.recordRealSocialAdIntercept(
                    platform = platform,
                    descriptor = "${platform.shortName} Unskippable Video Ad Fast-Forwarded",
                    action = "VIDEO_AD_FAST_FWD"
                )
            }
            return true
        } else if (isAdAudioMutedByShield) {
            restoreMutedAudio()
        }

        return false
    }

    /**
     * Hides or dismisses Sponsored Feed Cards, Promoted Posts, and YouTube Sponsored Companion Panels
     * IN-PLACE without scrolling YouTube or feeds, and skips full-screen Sponsored Reels/Shorts/TikToks.
     */
    private fun handleSponsoredFeedAndCompanionAdsInPlace(
        rootNode: AccessibilityNodeInfo,
        platform: SocialPlatform,
        screenWidth: Int,
        screenHeight: Int,
        repo: KernelRepository
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoSwipeTimestampMs < 480L) return false

        // 0. If an ad dismissal popup/sheet ("Hide ad", "Stop seeing this ad") is open on screen, click it immediately
        for (sheetOption in listOf("Hide ad", "Stop seeing this ad")) {
            val sheetNodes = try {
                rootNode.findAccessibilityNodeInfosByText(sheetOption)
            } catch (_: Exception) {
                null
            }
            if (!sheetNodes.isNullOrEmpty()) {
                for (sNode in sheetNodes) {
                    if (sNode.isVisibleToUser && triggerSkipButtonClickAndTap(sNode)) {
                        lastAutoSwipeTimestampMs = now
                        repo.recordRealSocialAdIntercept(
                            platform = platform,
                            descriptor = "${platform.shortName} $sheetOption Confirmed",
                            action = "FEED_AD_HIDDEN"
                        )
                        return true
                    }
                }
            }
        }

        var matchedAdNode: AccessibilityNodeInfo? = null
        var matchedLabel = "Sponsored Ad"

        // LAYER A: Check platform-specific Sponsored View IDs (Instagram, YouTube, TikTok)
        val platformViewIds = PLATFORM_SPONSORED_VIEW_IDS[platform].orEmpty()
        for (viewId in platformViewIds) {
            val idNodes = try {
                rootNode.findAccessibilityNodeInfosByViewId(viewId)
            } catch (_: Exception) {
                null
            } ?: continue

            for (node in idNodes) {
                if (!node.isVisibleToUser) continue
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!isWithinFeedViewport(bounds, screenHeight)) continue

                val txt = deobfuscateText(node.text)
                val desc = deobfuscateText(node.contentDescription)
                val isDedicatedAdId = viewId.contains("ad_") || viewId.contains("sponsored") || viewId.contains("promoted")
                if (isDedicatedAdId || isConfirmedSponsoredNode(txt, desc, viewId)) {
                    matchedAdNode = node
                    matchedLabel = "Sponsored (${viewId.substringAfterLast('/')})"
                    break
                }
            }
            if (matchedAdNode != null) break
        }

        // LAYER B: Native C++ search for Sponsored / Promoted badges
        if (matchedAdNode == null) {
            val queries = listOf(
                "Sponsored",
                "Promoted",
                "Paid partnership",
                "Ad ·"
            )
            for (query in queries) {
                val nodes = try {
                    rootNode.findAccessibilityNodeInfosByText(query)
                } catch (_: Exception) {
                    null
                } ?: continue

                for (node in nodes) {
                    if (!node.isVisibleToUser) continue
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (!isWithinFeedViewport(bounds, screenHeight)) continue

                    val txt = deobfuscateText(node.text)
                    val desc = deobfuscateText(node.contentDescription)
                    val vId = node.viewIdResourceName?.lowercase() ?: ""
                    if (isConfirmedSponsoredNode(txt, desc, vId)) {
                        matchedAdNode = node
                        matchedLabel = query
                        break
                    }
                }
                if (matchedAdNode != null) break
            }
        }

        // LAYER C: De-obfuscated walk of visible feed nodes (catches Facebook S\u200Bponsored zero-width splits & Litho containers)
        if (matchedAdNode == null) {
            val walkResult = DeobfuscatedWalkResult()
            walkFeedForObfuscatedSponsored(rootNode, 0, screenHeight, walkResult)
            if (walkResult.matchedNode != null) {
                matchedAdNode = walkResult.matchedNode
                matchedLabel = walkResult.matchedReason
            }
        }

        val adNode = matchedAdNode ?: return false

        val adBounds = Rect()
        adNode.getBoundsInScreen(adBounds)
        val signature = "${platform.id}:${matchedLabel}:${adBounds.top / 80}:${adBounds.left / 80}"
        if (signature == lastHandledAdSignature && (now - lastHandledAdSignatureTimeMs) < 3500L) {
            return false
        }

        lastAutoSwipeTimestampMs = now
        lastHandledAdSignature = signature
        lastHandledAdSignatureTimeMs = now

        // 1. Hide / Dismiss the Sponsored card or panel IN-PLACE (via Close 'X' / Hide ad / Dismiss button)
        if (hideOrDismissSponsoredCardInPlace(adNode, rootNode, platform, adBounds, screenWidth, screenHeight)) {
            repo.recordRealSocialAdIntercept(
                platform = platform,
                descriptor = "${platform.shortName} $matchedLabel Hidden",
                action = "FEED_AD_HIDDEN"
            )
            return true
        }

        // 2. ONLY for Full-Screen Vertical Video Pagers (TikTok, Instagram Reels, Facebook Reels, YouTube Shorts — NEVER YouTube Home/Watch!),
        //    advance past the full-screen Sponsored Reel/Short/TikTok
        if (isFullScreenReelOrShort(platform, rootNode, adNode, adBounds, screenHeight)) {
            if (advanceFullScreenSponsoredReel(rootNode, screenWidth, screenHeight)) {
                repo.recordRealSocialAdIntercept(
                    platform = platform,
                    descriptor = "${platform.shortName} Sponsored Reel Skipped",
                    action = "REEL_AD_SKIPPED"
                )
                return true
            }
        }

        return false
    }

    private class DeobfuscatedWalkResult {
        var visited: Int = 0
        var matchedNode: AccessibilityNodeInfo? = null
        var matchedReason: String = "Sponsored Post"
    }

    /**
     * Walks the visible tree (up to 160 nodes, depth 20) without stopping on non-visible Litho intermediate containers,
     * stripping zero-width characters to catch obfuscated "S\u200Bponsored" labels and generic ad view IDs.
     */
    private fun walkFeedForObfuscatedSponsored(
        node: AccessibilityNodeInfo?,
        depth: Int,
        screenHeight: Int,
        result: DeobfuscatedWalkResult
    ) {
        if (node == null || depth > 20 || result.visited > 160 || result.matchedNode != null) return
        result.visited++

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Skip top status bar / app bar nodes and off-screen nodes
        if (bounds.bottom > (screenHeight * 0.10f).toInt() && bounds.top < (screenHeight * 0.88f).toInt()) {
            val txt = deobfuscateText(node.text)
            val desc = deobfuscateText(node.contentDescription)
            val vId = node.viewIdResourceName?.lowercase() ?: ""

            if (isConfirmedSponsoredNode(txt, desc, vId)) {
                result.matchedNode = node
                result.matchedReason = if (txt.isNotEmpty() && txt.length <= 30) txt else "Sponsored Feed Ad"
                return
            }
        }

        val childCount = node.childCount.coerceAtMost(20)
        for (i in 0 until childCount) {
            if (result.visited > 160 || result.matchedNode != null) break
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            walkFeedForObfuscatedSponsored(child, depth + 1, screenHeight, result)
        }
    }

    private fun isWithinFeedViewport(bounds: Rect, screenHeight: Int): Boolean {
        return bounds.width() > 0 &&
            bounds.height() > 0 &&
            bounds.bottom > (screenHeight * 0.08f).toInt() &&
            bounds.top < (screenHeight * 0.88f).toInt()
    }

    private fun isSkipButtonLabel(label: String): Boolean {
        if (label.isEmpty() || label.length > 28) return false
        val clean = label.trim()
        return clean.equals("Skip ad", ignoreCase = true) ||
            clean.equals("Skip ads", ignoreCase = true) ||
            clean.equals("Skip", ignoreCase = true) ||
            clean.startsWith("Skip ad", ignoreCase = true) ||
            clean.startsWith("Skip Ads", ignoreCase = true) ||
            clean.equals("Skip trial", ignoreCase = true) ||
            clean.equals("Dismiss ad", ignoreCase = true) ||
            clean.equals("Close ad", ignoreCase = true)
    }

    private fun isConfirmedSponsoredNode(text: String, contentDesc: String, viewIdLower: String): Boolean {
        if (viewIdLower.isNotEmpty() && GENERIC_SPONSORED_ID_SUBSTRINGS.any { viewIdLower.contains(it) }) {
            return true
        }

        for (candidate in arrayOf(text, contentDesc)) {
            if (candidate.isEmpty() || candidate.length > 500) continue
            val clean = candidate.trim()
            val lower = clean.lowercase()

            // 1. Exact or prefix Sponsored / Promoted / Ad badge
            if (lower == "sponsored" ||
                lower == "sponsored." ||
                lower == "promoted" ||
                lower == "sponsored reel" ||
                lower == "sponsored post" ||
                lower == "paid partnership" ||
                lower.startsWith("sponsored ·") ||
                lower.startsWith("sponsored •") ||
                lower.startsWith("sponsored -") ||
                lower.startsWith("promoted ·") ||
                lower.startsWith("promoted by ") ||
                lower.startsWith("paid partnership with ") ||
                lower.startsWith("ad ·") ||
                lower.startsWith("ad •") ||
                lower.endsWith(" · sponsored") ||
                lower.endsWith(" • sponsored")
            ) {
                return true
            }

            // 2. Combined Litho header description containing " · Sponsored · " or "\nSponsored"
            if (SPONSORED_SEGMENT_REGEX.containsMatchIn(clean)) {
                return true
            }
        }
        return false
    }

    private fun triggerSkipButtonClickAndTap(node: AccessibilityNodeInfo): Boolean {
        var clicked = false
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable && current.isEnabled) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    clicked = true
                    break
                }
            }
            current = try { current.parent } catch (_: Exception) { null }
            depth++
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastSkipTapTimestampMs >= 380L) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 4 && bounds.height() > 4 && bounds.centerX() > 0 && bounds.centerY() > 0) {
                lastSkipTapTimestampMs = now
                if (dispatchTapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())) {
                    clicked = true
                }
            }
        }
        return clicked
    }

    private fun dispatchTapAt(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    private fun fastForwardActiveVideoAdSeekBar(rootNode: AccessibilityNodeInfo, screenHeight: Int): Boolean {
        return seekAdBarRecursive(rootNode, 0, screenHeight)
    }

    private fun seekAdBarRecursive(node: AccessibilityNodeInfo?, depth: Int, screenHeight: Int): Boolean {
        if (node == null || depth > 12) return false
        val cls = node.className?.toString() ?: ""
        val vId = node.viewIdResourceName?.lowercase() ?: ""
        if (cls.contains("SeekBar", ignoreCase = true) ||
            cls.contains("ProgressBar", ignoreCase = true) ||
            vId.contains("time_bar") ||
            vId.contains("ad_progress")
        ) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.bottom <= (screenHeight * 0.46f).toInt() && bounds.width() > 100) {
                val args = Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 100f)
                }
                if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args)) {
                    return true
                }
            }
        }
        val count = node.childCount.coerceAtMost(15)
        for (i in 0 until count) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            if (seekAdBarRecursive(child, depth + 1, screenHeight)) return true
        }
        return false
    }

    /**
     * Hides or dismisses a Sponsored Feed Card or YouTube Companion Ad Panel in-place:
     * 1. Walks up to the card/panel container (never the full-screen RecyclerView).
     * 2. Invokes `ACTION_DISMISS` or `ACTION_COLLAPSE` if supported by the card or any parent container.
     * 3. Searches the card subtree (and nearby header band) for a dedicated "Hide ad", "Close ad", "Dismiss",
     *    or top-right "Close" (X) button and clicks/taps it in-place without scrolling.
     */
    private fun hideOrDismissSponsoredCardInPlace(
        sponsoredNode: AccessibilityNodeInfo,
        rootNode: AccessibilityNodeInfo,
        platform: SocialPlatform,
        adBounds: Rect,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        var cardRoot: AccessibilityNodeInfo = sponsoredNode
        var curr: AccessibilityNodeInfo? = sponsoredNode
        for (i in 0 until 7) {
            if (curr == null) break
            val canDismiss = curr.actionList.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id
            }
            if (canDismiss && curr.performAction(AccessibilityNodeInfo.ACTION_DISMISS)) {
                return true
            }
            val canCollapse = curr.actionList.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE.id
            }
            if (canCollapse && curr.performAction(AccessibilityNodeInfo.ACTION_COLLAPSE)) {
                return true
            }

            val p = try { curr.parent } catch (_: Exception) { null } ?: break
            val pCls = p.className?.toString() ?: ""
            val pBounds = Rect()
            p.getBoundsInScreen(pBounds)
            if (pCls.contains("RecyclerView", ignoreCase = true) ||
                pCls.contains("ListView", ignoreCase = true) ||
                pBounds.height() > (screenHeight * 0.82f).toInt()
            ) {
                cardRoot = curr
                break
            }
            cardRoot = p
            curr = p
        }

        // 2. Search the card/panel subtree for an in-place Close / Hide Ad / Dismiss button
        if (clickHideOrCloseButtonInCard(cardRoot, 0, screenWidth, platform, adBounds)) {
            return true
        }

        // 3. If cardRoot was a flat Litho node, search the active window within the card header's vertical band
        return clickHideOrCloseButtonInCard(rootNode, 0, screenWidth, platform, adBounds)
    }

    private fun clickHideOrCloseButtonInCard(
        node: AccessibilityNodeInfo?,
        depth: Int,
        screenWidth: Int,
        platform: SocialPlatform,
        adBounds: Rect
    ): Boolean {
        if (node == null || depth > 9) return false

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Only inspect buttons near the Sponsored card's header or companion panel header
        val inHeaderVerticalBand = bounds.top >= (adBounds.top - 160) && bounds.bottom <= (adBounds.bottom + 360)
        if (bounds.width() > 0 && inHeaderVerticalBand && bounds.centerX() > (screenWidth * 0.65f).toInt()) {
            val desc = deobfuscateText(node.contentDescription).lowercase()
            val txt = deobfuscateText(node.text).lowercase()
            val vId = node.viewIdResourceName?.lowercase() ?: ""

            val isExplicitHideOrClose = desc == "hide ad" ||
                desc == "close ad" ||
                desc == "dismiss ad" ||
                desc == "close ad panel" ||
                desc == "hide post" ||
                desc == "hide" ||
                desc == "remove ad" ||
                desc == "close" ||
                desc == "dismiss" ||
                txt == "hide ad" ||
                txt == "close ad" ||
                txt == "dismiss" ||
                vId.contains("close_button") ||
                vId.contains("dismiss_button") ||
                vId.contains("ad_dismiss") ||
                vId.contains("hide_ad") ||
                vId.contains("engagement_panel_close")

            // On Facebook feed cards, the top-right 'X' (Hide post) button sits at the far right (>88% width) of the header row
            val isFacebookTopRightCloseX = platform == SocialPlatform.FACEBOOK &&
                bounds.centerX() >= (screenWidth * 0.88f).toInt() &&
                bounds.width() in 24..165 &&
                bounds.height() in 24..165 &&
                bounds.top >= (adBounds.top - 110) &&
                bounds.bottom <= (adBounds.bottom + 90) &&
                (node.isClickable || desc.contains("hide") || desc.contains("close") || desc.isEmpty())

            if (isExplicitHideOrClose || isFacebookTopRightCloseX) {
                if (triggerSkipButtonClickAndTap(node)) {
                    return true
                }
            }
        }

        val childCount = node.childCount.coerceAtMost(18)
        for (i in 0 until childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
            if (clickHideOrCloseButtonInCard(child, depth + 1, screenWidth, platform, adBounds)) {
                return true
            }
        }
        return false
    }

    /**
     * Returns true ONLY when the user is viewing a full-screen vertical Reel / Short / TikTok pager
     * (never on YouTube Home feed or YouTube video watch page!).
     */
    private fun isFullScreenReelOrShort(
        platform: SocialPlatform,
        rootNode: AccessibilityNodeInfo,
        adNode: AccessibilityNodeInfo,
        adBounds: Rect,
        screenHeight: Int
    ): Boolean {
        if (platform == SocialPlatform.TIKTOK) return true

        val txt = deobfuscateText(adNode.text).lowercase()
        val desc = deobfuscateText(adNode.contentDescription).lowercase()
        val vId = adNode.viewIdResourceName?.lowercase() ?: ""

        if (txt.contains("sponsored reel") || desc.contains("sponsored reel") || vId.contains("clips_ad_cta")) {
            return true
        }

        if (platform == SocialPlatform.YOUTUBE) {
            // On YouTube, ONLY treat as a Short if the Shorts player container (reel_recycler / reel_player_page_ads) is active
            for (shortId in listOf(
                "com.google.android.youtube:id/reel_recycler",
                "com.google.android.youtube:id/reel_player_page_ads"
            )) {
                val shortNodes = try {
                    rootNode.findAccessibilityNodeInfosByViewId(shortId)
                } catch (_: Exception) {
                    null
                }
                if (!shortNodes.isNullOrEmpty() && shortNodes.any { it.isVisibleToUser }) {
                    return true
                }
            }
            return false
        }

        // On Instagram/Facebook Reels, the Sponsored label sits near the bottom overlay (>70% screen height) inside a full-screen pager
        if ((platform == SocialPlatform.INSTAGRAM || platform == SocialPlatform.FACEBOOK) &&
            adBounds.top > (screenHeight * 0.70f).toInt()
        ) {
            return true
        }

        return false
    }

    private fun advanceFullScreenSponsoredReel(
        rootNode: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        return try {
            val centerX = (screenWidth * 0.50f).coerceAtLeast(100f)
            val startY = (screenHeight * 0.80f).coerceAtLeast(300f)
            val endY = (screenHeight * 0.18f).coerceAtLeast(80f)
            val path = Path().apply {
                moveTo(centerX, startY)
                lineTo(centerX, endY)
            }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 90L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }

    private fun muteAdAudioTemporary() {
        if (isAdAudioMutedByShield) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            isAdAudioMutedByShield = true
        } catch (_: Exception) {
        }
    }

    private fun restoreMutedAudio() {
        if (!isAdAudioMutedByShield) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            isAdAudioMutedByShield = false
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {
        isScanInProgress = false
        mainHandler.removeCallbacks(trailingScanRunnable)
        restoreMutedAudio()
        Log.w(TAG, "Dragon Accessibility Monitor Service Interrupted.")
    }

    override fun onDestroy() {
        isScanInProgress = false
        mainHandler.removeCallbacks(trailingScanRunnable)
        restoreMutedAudio()
        super.onDestroy()
        KernelRepository.getInstance(applicationContext).onAccessibilityServiceStateChanged(false)
        Log.i(TAG, "Dragon Accessibility Monitor Service Destroyed.")
    }
}
