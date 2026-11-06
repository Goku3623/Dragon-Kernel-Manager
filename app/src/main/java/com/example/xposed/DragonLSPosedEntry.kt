package com.example.xposed

import android.app.AndroidAppHelper
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.SeekBar
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * 100% Self-Contained, Crash-Safe 5-Layer LSPosed / Xposed Module Entry Point.
 * Declared in `assets/xposed_init`.
 *
 * Key Stability Guarantees:
 * - Zero external class imports from `com.example.*` (prevents Zygote/Target-App ClassLoader verification errors).
 * - 3.5-second cold-start grace period so social app launch, splash screen, login & initial feed load never hang or fail.
 * - Zero exception throwing in `InetAddress` (returns `0.0.0.0` loopback address just like `/etc/hosts`).
 * - Zero intrinsic or layout-pass hooks (`StringBuilder`, `URL`, `View.measure` are untouched).
 */
class DragonLSPosedEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val MODULE_PKG = "com.android.dragontools"
        private const val CONFIG_URI_STR = "content://com.android.dragontools.lsposed.provider/config"
        private const val TAG = "DragonLSPosed"
        private const val STARTUP_GRACE_MS = 3500L

        private val INVISIBLE_CHARS_REGEX = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u206F\\uFEFF]")
        private val NBSP_REGEX = Regex("\\u00A0")

        private val SOCIAL_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "app.revanced.android.youtube",
            "tv.twitch.android.app",
            "com.facebook.katana",
            "com.facebook.lite",
            "com.facebook.orca",
            "com.facebook.mlite",
            "com.instagram.android",
            "com.instagram.lite",
            "com.instagram.barcelona",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go",
            "com.snapchat.android",
            "com.twitter.android",
            "com.twitter.android.lite",
            "com.reddit.frontpage",
            "com.pinterest"
        )

        /**
         * Strict dedicated ad-serving domains only (never includes main app API, graph, CDN, or router hosts).
         */
        private val STRICT_AD_HOSTS = setOf(
            "ads.youtube.com",
            "pagead2.googlesyndication.com",
            "pagead2.googleadservices.com",
            "tpc.googlesyndication.com",
            "ade.googlesyndication.com",
            "googleads.g.doubleclick.net",
            "pubads.g.doubleclick.net",
            "securepubads.g.doubleclick.net",
            "ad.doubleclick.net",
            "cm.g.doubleclick.net",
            "mediavisor.doubleclick.net",
            "adservice.google.com",
            "imasdk.googleapis.com",
            "an.facebook.com",
            "audience-network.facebook.com",
            "ads.facebook.com",
            "mobileads.facebook.com",
            "ads-api.facebook.com",
            "adservice.facebook.com",
            "pixel.facebook.com",
            "an.meta.com",
            "ads.meta.com",
            "ads.instagram.com",
            "ads-api.instagram.com",
            "an.instagram.com",
            "pixel.instagram.com",
            "ads.tiktok.com",
            "ad.tiktok.com",
            "ads-sg.tiktok.com",
            "ads-va.tiktok.com",
            "ads-api.tiktok.com",
            "pg-ad.tiktokv.com",
            "pangolin-sdk-toutiao.com",
            "ads.snapchat.com",
            "adserver.snapchat.com",
            "story-ad-api.snapchat.com",
            "ads-api.snapchat.com",
            "ads-api.twitter.com",
            "ads.twitter.com",
            "ads.x.com",
            "ads.reddit.com",
            "ads.pinterest.com",
            "ads.twitch.tv",
            "rt.applovin.com",
            "ms.applovin.com",
            "d.applovin.com",
            "a.applovin.com",
            "unityads.unity3d.com",
            "auction.unityads.unity3d.com",
            "ads.api.vungle.com"
        )

        private val JSON_SPONSORED_KEYS = setOf(
            "is_sponsored",
            "is_promoted",
            "is_paid_partnership",
            "is_ad_pod",
            "is_sponsored_post",
            "show_sponsored_label"
        )

        private val YOUTUBE_AD_ARRAY_KEYS = setOf(
            "adplacements",
            "playerads",
            "adslots"
        )

        private val SKIP_BUTTON_IDS = listOf(
            "skip_ad_button",
            "modern_skip_ad_button",
            "skip_ad_button_container",
            "ad_skip_button"
        )

        private val IN_VIDEO_AD_OVERLAY_IDS = listOf(
            "ad_progress_text",
            "ad_countdown",
            "player_learn_more_button"
        )

        private val AD_CONTAINER_IDS = listOf(
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

        private val LITHO_SPONSORED_REGEX = Regex(
            pattern = "(^|\\n|\\s*[·•\\-|.]\\s*)(sponsored|promoted|paid partnership|sponsored reel|sponsored post)(\\s*[·•\\-|.]|\\s*$|\\n)|(^|\\n|\\s)(ad\\s*[·•]\\s*\\d*:?\\d*)",
            option = RegexOption.IGNORE_CASE
        )
    }

    private var xPrefs: XSharedPreferences? = null
    private var processLoadTimeMs: Long = 0L
    private var lastReportTimeMs: Long = 0L
    private var lastConfigSyncTimeMs: Long = 0L

    private val bgExecutor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "DragonXposedBg").apply { isDaemon = true }
        }
    }

    @Volatile private var isGlobalEnabled: Boolean = true
    @Volatile private var hookLithoAndViews: Boolean = true
    @Volatile private var hookYoutubeAndVideoAds: Boolean = true
    @Volatile private var hookMobileAdSdks: Boolean = true
    @Volatile private var hookNetworkAndWebView: Boolean = true
    @Volatile private var hookJsonAndGraphQl: Boolean = true

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        try {
            xPrefs = XSharedPreferences(MODULE_PKG, "dragon_adblocker_prefs").apply {
                makeWorldReadable()
            }
        } catch (_: Throwable) {
        }
    }

    private fun isStartupComplete(): Boolean {
        if (processLoadTimeMs == 0L) return true
        return (SystemClock.elapsedRealtime() - processLoadTimeMs) >= STARTUP_GRACE_MS
    }

    private fun triggerAsyncConfigSyncIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastConfigSyncTimeMs < 20_000L) return
        lastConfigSyncTimeMs = now

        try {
            bgExecutor.execute {
                try {
                    xPrefs?.let { prefs ->
                        prefs.reload()
                        isGlobalEnabled = prefs.getBoolean("adblock_global_enabled", true)
                        hookLithoAndViews = prefs.getBoolean("adblock_xposed_litho", true)
                        hookYoutubeAndVideoAds = prefs.getBoolean("adblock_xposed_yt_video", true)
                        hookMobileAdSdks = prefs.getBoolean("adblock_xposed_ad_sdks", true)
                        hookNetworkAndWebView = prefs.getBoolean("adblock_xposed_net_webview", true)
                        hookJsonAndGraphQl = prefs.getBoolean("adblock_xposed_json_graphql", true)
                    }
                } catch (_: Throwable) {}

                try {
                    val app = AndroidAppHelper.currentApplication() ?: return@execute
                    app.contentResolver.query(
                        Uri.parse(CONFIG_URI_STR),
                        null,
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idxGlobal = cursor.getColumnIndex("is_adblock_enabled")
                            val idxLitho = cursor.getColumnIndex("xposed_hook_litho")
                            val idxYtVideo = cursor.getColumnIndex("xposed_hook_youtube_video")
                            val idxAdSdks = cursor.getColumnIndex("xposed_hook_ad_sdks")
                            val idxNetWeb = cursor.getColumnIndex("xposed_hook_network_webview")
                            val idxJson = cursor.getColumnIndex("xposed_hook_json_graphql")

                            if (idxGlobal >= 0) isGlobalEnabled = cursor.getInt(idxGlobal) == 1
                            if (idxLitho >= 0) hookLithoAndViews = cursor.getInt(idxLitho) == 1
                            if (idxYtVideo >= 0) hookYoutubeAndVideoAds = cursor.getInt(idxYtVideo) == 1
                            if (idxAdSdks >= 0) hookMobileAdSdks = cursor.getInt(idxAdSdks) == 1
                            if (idxNetWeb >= 0) hookNetworkAndWebView = cursor.getInt(idxNetWeb) == 1
                            if (idxJson >= 0) hookJsonAndGraphQl = cursor.getInt(idxJson) == 1
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam?) {
        val pkg = lpparam?.packageName ?: return
        val cl = lpparam.classLoader ?: return

        // 1. Self-hook for Dragon Kernel Tools UI verification
        if (pkg == MODULE_PKG) {
            hookSelfStatus(cl)
            return
        }

        // Skip Android system packages
        if (pkg == "android" || pkg.startsWith("com.android.")) {
            return
        }

        processLoadTimeMs = SystemClock.elapsedRealtime()

        // Only attach social & ad hooks to target social packages
        if (SOCIAL_PACKAGES.contains(pkg)) {
            XposedBridge.log("[$TAG] Attaching Startup-Safe Xposed AdShield to: $pkg")
            hookInProcessDnsAndWebView(pkg)
            hookInProcessJsonFeedParser(pkg)
            hookInProcessViewsLithoAndVideoAds(pkg)
            hookInProcessMobileAdSdks(pkg, cl)
            return
        }

        // For games/other apps in LSPosed scope: attach Ad SDK nullifier & 120Hz Refresh Rate Unlocker
        hookInProcessMobileAdSdks(pkg, cl)
        hookGameRefreshRateUnlocker()
    }

    private fun deobfuscate(raw: CharSequence?): String {
        if (raw.isNullOrEmpty()) return ""
        return raw.toString()
            .replace(INVISIBLE_CHARS_REGEX, "")
            .replace(NBSP_REGEX, " ")
            .trim()
    }

    private fun hookSelfStatus(cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.example.xposed.DragonLSPosedStatus",
                cl,
                "isModuleActiveHooked",
                XC_MethodReplacement.returnConstant(true)
            )
            val version = try {
                XposedBridge.getXposedVersion()
            } catch (_: Throwable) {
                93
            }
            XposedHelpers.findAndHookMethod(
                "com.example.xposed.DragonLSPosedStatus",
                cl,
                "getHookedApiVersion",
                XC_MethodReplacement.returnConstant(version)
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Self-hook error: ${t.message}")
        }
    }

    /**
     * Hook Mechanism 1: Startup-Safe In-Process DNS Sinkhole (0.0.0.0 loopback) & WebView Subresource Ad Blocker.
     * Never throws exceptions in InetAddress; returns 0.0.0.0 just like /etc/hosts.
     */
    private fun hookInProcessDnsAndWebView(pkg: String) {
        try {
            XposedHelpers.findAndHookMethod(
                InetAddress::class.java,
                "getAllByName",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isStartupComplete()) return
                        triggerAsyncConfigSyncIfNeeded()
                        if (!isGlobalEnabled || !hookNetworkAndWebView) return

                        val host = (param.args?.firstOrNull() as? String)?.trim()?.lowercase() ?: return
                        if (host.isEmpty() || host == "localhost" || host == "127.0.0.1") return

                        if (isDomainBlocked(host)) {
                            reportInterceptToModuleAsync(pkg, host, "LSPOSED_DNS_SINKHOLE")
                            param.result = arrayOf(InetAddress.getByAddress(host, byteArrayOf(0, 0, 0, 0)))
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                InetAddress::class.java,
                "getByName",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isStartupComplete()) return
                        if (!isGlobalEnabled || !hookNetworkAndWebView) return

                        val host = (param.args?.firstOrNull() as? String)?.trim()?.lowercase() ?: return
                        if (host.isEmpty() || host == "localhost" || host == "127.0.0.1") return

                        if (isDomainBlocked(host)) {
                            reportInterceptToModuleAsync(pkg, host, "LSPOSED_DNS_SINKHOLE")
                            param.result = InetAddress.getByAddress(host, byteArrayOf(0, 0, 0, 0))
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }

        // WebView subresource ad blocker (never blocks main frame)
        try {
            XposedHelpers.findAndHookMethod(
                WebViewClient::class.java,
                "shouldInterceptRequest",
                WebView::class.java,
                WebResourceRequest::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isStartupComplete() || !isGlobalEnabled || !hookNetworkAndWebView) return
                        val req = param.args?.getOrNull(1) as? WebResourceRequest ?: return
                        if (req.isForMainFrame) return
                        val host = req.url?.host?.lowercase() ?: return
                        if (host.isNotEmpty() && isDomainBlocked(host)) {
                            reportInterceptToModuleAsync(pkg, "WebView Ad ($host)", "LSPOSED_WEBVIEW_DROP")
                            param.result = WebResourceResponse(
                                "text/plain",
                                "UTF-8",
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * Hook Mechanism 2: Startup-Safe JSON & YouTube `adPlacements`/`playerAds` Stripper.
     */
    private fun hookInProcessJsonFeedParser(pkg: String) {
        try {
            val boolHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!isStartupComplete() || !isGlobalEnabled || !hookJsonAndGraphQl) return
                    val key = (param.args?.firstOrNull() as? String)?.lowercase() ?: return
                    if (JSON_SPONSORED_KEYS.contains(key)) {
                        param.result = false
                    }
                }
            }

            XposedHelpers.findAndHookMethod(
                org.json.JSONObject::class.java,
                "optBoolean",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                boolHook
            )
            XposedHelpers.findAndHookMethod(
                org.json.JSONObject::class.java,
                "optBoolean",
                String::class.java,
                boolHook
            )

            // Return null for adPlacements / playerAds ONLY when present on the JSONObject
            XposedHelpers.findAndHookMethod(
                org.json.JSONObject::class.java,
                "optJSONArray",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result == null) return
                        if (!isStartupComplete() || !isGlobalEnabled || (!hookJsonAndGraphQl && !hookYoutubeAndVideoAds)) return
                        val key = (param.args?.firstOrNull() as? String)?.lowercase() ?: return
                        if (YOUTUBE_AD_ARRAY_KEYS.contains(key)) {
                            reportInterceptToModuleAsync(pkg, "YouTube Ad Array Stripped ($key)", "LSPOSED_JSON_STRIP")
                            param.result = null
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private val hiddenAdCards = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

    /**
     * Hook Mechanism 3 & 4:
     * - Post-Startup Sponsored Feed Card Zero-Height Hider (via `View.post` so layout/measure never deadlocks)
     * - YouTube & Video Pre/Mid-Roll Instant Ad Skipper & SeekBar Fast-Forwarder
     */
    private fun hookInProcessViewsLithoAndVideoAds(pkg: String) {
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isStartupComplete() || !isGlobalEnabled) return
                        val view = param.thisObject as? View ?: return
                        val id = view.id
                        if (id == View.NO_ID) return
                        val resName = try {
                            view.resources.getResourceEntryName(id).lowercase()
                        } catch (_: Throwable) {
                            return
                        }

                        if (hookYoutubeAndVideoAds) {
                            if (SKIP_BUTTON_IDS.any { resName.contains(it) }) {
                                scheduleInProcessSkipClick(view, pkg, resName)
                                return
                            }

                            if (IN_VIDEO_AD_OVERLAY_IDS.any { resName.contains(it) }) {
                                fastForwardWindowAdSeekBar(view, pkg)
                                return
                            }
                        }

                        if (hookLithoAndViews && AD_CONTAINER_IDS.any { resName.contains(it) }) {
                            collapseSponsoredCardContainer(view, pkg, "Ad View ($resName)")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                View::class.java,
                "setContentDescription",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isStartupComplete() || !isGlobalEnabled) return
                        val view = param.thisObject as? View ?: return
                        val desc = deobfuscate(param.args?.firstOrNull() as? CharSequence)
                        if (desc.isEmpty() || desc.length > 450) return

                        if (hookYoutubeAndVideoAds &&
                            (desc.equals("Skip ad", ignoreCase = true) ||
                                desc.equals("Skip ads", ignoreCase = true) ||
                                desc.startsWith("Skip ad", ignoreCase = true))
                        ) {
                            scheduleInProcessSkipClick(view, pkg, desc)
                            return
                        }

                        if (hookLithoAndViews && isSponsoredOrPaidCtaText(desc)) {
                            collapseSponsoredCardContainer(view, pkg, "Litho Sponsored Card")
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                TextView::class.java,
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isStartupComplete() || !isGlobalEnabled) return
                        val tv = param.thisObject as? TextView ?: return
                        val raw = deobfuscate(param.args?.firstOrNull() as? CharSequence)
                        if (raw.isEmpty() || raw.length > 280) return

                        if (hookYoutubeAndVideoAds &&
                            (raw.equals("Skip ad", ignoreCase = true) ||
                                raw.equals("Skip ads", ignoreCase = true) ||
                                raw.startsWith("Skip ad", ignoreCase = true))
                        ) {
                            scheduleInProcessSkipClick(tv, pkg, raw)
                            return
                        }

                        if (hookLithoAndViews && isSponsoredOrPaidCtaText(raw)) {
                            collapseSponsoredCardContainer(tv, pkg, "Sponsored Card ($raw)")
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * Hook Mechanism 5: Embedded Mobile Ad SDK Method Nullifier.
     */
    private fun hookInProcessMobileAdSdks(pkg: String, cl: ClassLoader) {
        val noOpAdHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!isStartupComplete() || !isGlobalEnabled || !hookMobileAdSdks) return
                val thisObj = param.thisObject
                if (thisObj is View) {
                    thisObj.post { hideViewInPlaceZeroHeight(thisObj) }
                }
                val sdkName = param.method?.declaringClass?.simpleName ?: "AdSDK"
                reportInterceptToModuleAsync(pkg, "$sdkName.${param.method?.name}() Nullified", "LSPOSED_SDK_NULLIFIED")
                param.result = null
            }
        }

        val sdkClassAndMethods = listOf(
            "com.google.android.gms.ads.BaseAdView" to listOf("loadAd"),
            "com.google.android.gms.ads.AdView" to listOf("loadAd"),
            "com.google.android.gms.ads.AdLoader" to listOf("loadAd", "loadAds"),
            "com.facebook.ads.AdView" to listOf("loadAd"),
            "com.facebook.ads.InterstitialAd" to listOf("loadAd", "show"),
            "com.unity3d.ads.UnityAds" to listOf("load", "show"),
            "com.applovin.mediation.ads.MaxAdView" to listOf("loadAd"),
            "com.applovin.mediation.ads.MaxInterstitialAd" to listOf("loadAd", "showAd")
        )

        for ((className, methods) in sdkClassAndMethods) {
            try {
                val clazz = XposedHelpers.findClassIfExists(className, cl) ?: continue
                for (methodName in methods) {
                    XposedBridge.hookAllMethods(clazz, methodName, noOpAdHook)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun scheduleInProcessSkipClick(view: View, pkg: String, label: String) {
        for (delayMs in longArrayOf(50L, 300L, 700L)) {
            view.postDelayed({
                try {
                    if (view.isAttachedToWindow) {
                        view.isEnabled = true
                        view.visibility = View.VISIBLE
                        if (!view.performClick()) {
                            (view.parent as? View)?.performClick()
                        }
                        reportInterceptToModuleAsync(pkg, "Video Ad ($label)", "LSPOSED_SKIP_CLICK")
                    }
                } catch (_: Throwable) {}
            }, delayMs)
        }
    }

    private fun fastForwardWindowAdSeekBar(adOverlayView: View, pkg: String) {
        adOverlayView.post {
            try {
                val root = adOverlayView.rootView as? ViewGroup ?: return@post
                seekAndClickSkipInTree(root, 0, pkg)
            } catch (_: Throwable) {}
        }
    }

    private fun seekAndClickSkipInTree(group: ViewGroup, depth: Int, pkg: String) {
        if (depth > 12) return
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            if (child is SeekBar && child.max > 0) {
                child.progress = child.max
                reportInterceptToModuleAsync(pkg, "Video Ad SeekBar Fast-Forwarded", "LSPOSED_AD_SEEK")
            }
            val id = child.id
            if (id != View.NO_ID) {
                val name = try { child.resources.getResourceEntryName(id).lowercase() } catch (_: Throwable) { "" }
                if (SKIP_BUTTON_IDS.any { name.contains(it) }) {
                    child.isEnabled = true
                    child.performClick()
                }
            }
            if (child is ViewGroup) {
                seekAndClickSkipInTree(child, depth + 1, pkg)
            }
        }
    }

    private fun isSponsoredOrPaidCtaText(text: String): Boolean {
        val clean = text.trim()
        val lower = clean.lowercase()
        if (lower == "sponsored" ||
            lower == "sponsored." ||
            lower == "promoted" ||
            lower == "sponsored reel" ||
            lower == "sponsored post" ||
            lower == "paid partnership" ||
            lower.startsWith("sponsored ·") ||
            lower.startsWith("sponsored •") ||
            lower.startsWith("promoted ·") ||
            lower.startsWith("promoted by ") ||
            lower.startsWith("ad ·") ||
            lower.startsWith("ad •") ||
            lower.endsWith(" · sponsored") ||
            lower.endsWith(" • sponsored") ||
            lower.contains("\nsponsored")
        ) {
            return true
        }
        return LITHO_SPONSORED_REGEX.containsMatchIn(clean)
    }

    private fun collapseSponsoredCardContainer(triggerView: View, pkg: String, reason: String) {
        triggerView.post {
            try {
                var current: View? = triggerView
                var recyclerItemCard: View? = null
                var depth = 0
                while (current != null && depth < 14) {
                    val parent = current.parent as? ViewGroup ?: break
                    val parentClass = parent.javaClass.name
                    if (parentClass.contains("RecyclerView", ignoreCase = true) ||
                        parentClass.contains("ListView", ignoreCase = true)
                    ) {
                        val grandParentClass = parent.parent?.javaClass?.name ?: ""
                        if (!grandParentClass.contains("ViewPager", ignoreCase = true)) {
                            val screenH = current.resources?.displayMetrics?.heightPixels ?: 2400
                            if (current.height in 1..(screenH * 0.85f).toInt()) {
                                recyclerItemCard = current
                            }
                        }
                        break
                    }
                    current = parent
                    depth++
                }

                val target = recyclerItemCard ?: return@post
                if (!hiddenAdCards.containsKey(target)) {
                    hiddenAdCards[target] = true
                    hideViewInPlaceZeroHeight(target)
                    reportInterceptToModuleAsync(pkg, reason, "LSPOSED_SPONSORED_HIDDEN")
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun hideViewInPlaceZeroHeight(target: View) {
        try {
            target.alpha = 0f
            target.scaleY = 0f
            target.minimumHeight = 0
            target.setPadding(0, 0, 0, 0)
            target.visibility = View.GONE
            val lp = target.layoutParams
            if (lp != null && lp.height != 0) {
                lp.height = 0
                if (lp is ViewGroup.MarginLayoutParams) {
                    lp.topMargin = 0
                    lp.bottomMargin = 0
                }
                target.layoutParams = lp
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookGameRefreshRateUnlocker() {
        try {
            XposedHelpers.findAndHookMethod(
                android.view.Display::class.java,
                "getRefreshRate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val current = (param.result as? Float) ?: return
                        if (current < 120f) {
                            param.result = 120f
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun isDomainBlocked(host: String): Boolean {
        if (STRICT_AD_HOSTS.contains(host)) return true
        var dotIndex = host.indexOf('.')
        while (dotIndex != -1 && dotIndex < host.length - 1) {
            val suffix = host.substring(dotIndex + 1)
            if (STRICT_AD_HOSTS.contains(suffix)) return true
            dotIndex = host.indexOf('.', dotIndex + 1)
        }
        return false
    }

    private fun reportInterceptToModuleAsync(pkg: String, descriptor: String, action: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReportTimeMs < 2000L) return
        lastReportTimeMs = now

        try {
            bgExecutor.execute {
                try {
                    val app = AndroidAppHelper.currentApplication() ?: return@execute
                    val extras = Bundle().apply {
                        putString("package_name", pkg)
                        putString("descriptor", descriptor)
                        putString("action", action)
                    }
                    app.contentResolver.call(
                        Uri.parse(CONFIG_URI_STR),
                        "report_lsposed_intercept",
                        pkg,
                        extras
                    )
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }
}
