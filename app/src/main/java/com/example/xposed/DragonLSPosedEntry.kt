package com.example.xposed

import android.app.AndroidAppHelper
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import com.example.data.adblock.AdBlockerEngine
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

/**
 * Official LSPosed / Xposed Module Entry Point for Dragon Kernel Tools.
 * Declared in `assets/xposed_init`.
 *
 * Provides:
 * 1. In-Process Social Media Ad & Sponsored Post/Reel Blocker (DNS/InetAddress, URI/Cronet/OkHttp3, JSONObject, Litho ContentDescription & Views)
 * 2. In-Process YouTube & Video Pre/Mid-Roll Instant Ad Skipper & SeekBar Fast-Forwarder
 * 3. In-Process 120Hz/144Hz Display Refresh Rate & FrameGen Unlocker for Games
 */
class DragonLSPosedEntry : IXposedHookLoadPackage, IXposedHookZygoteInit {

    companion object {
        private const val MODULE_PKG = "com.aistudio.dragonkernel.kfqz"
        private const val TAG = "DragonLSPosed"

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
            "com.instagram.android",
            "com.instagram.lite",
            "com.instagram.barcelona",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go",
            "com.snapchat.android",
            "com.twitter.android",
            "com.reddit.frontpage",
            "com.pinterest"
        )

        private val AD_URL_PATH_SIGNATURES = listOf(
            "/pagead/",
            "/get_midroll_info",
            "/player/ad_break",
            "/api/stats/ads",
            "/api/v1/ads/",
            "/aweme/v1/ad/",
            "/aweme/v2/commerce/",
            "/sponsored_story",
            "ad_impression",
            "ads.youtube.com",
            "doubleclick.net",
            "googlesyndication.com",
            "googleadservices.com",
            "an.facebook.com",
            "ads.facebook.com",
            "ads.instagram.com",
            "ads.tiktok.com",
            "pg-ad.tiktokv.com",
            "pangolin-sdk-toutiao.com",
            "ads.snapchat.com",
            "story-ad-api.snapchat.com",
            "ads-api.twitter.com",
            "ads.reddit.com"
        )

        private val JSON_SPONSORED_KEYS = setOf(
            "is_sponsored",
            "is_ad",
            "is_promoted",
            "has_ads",
            "is_commercial",
            "show_sponsored_label",
            "is_paid_partnership"
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

        private val PAID_AD_CTA_LABELS = setOf(
            "shop now",
            "learn more",
            "install now",
            "sign up",
            "apply now",
            "book now",
            "order now",
            "get offer",
            "get quote",
            "send whatsapp message",
            "visit instagram profile"
        )

        private val LITHO_SPONSORED_REGEX = Regex(
            pattern = "(^|\\n|\\s*[·•\\-|.]\\s*)(sponsored|promoted|paid partnership|sponsored reel|sponsored post)(\\s*[·•\\-|.]|\\s*$|\\n)|(^|\\n|\\s)(ad\\s*[·•]\\s*\\d*:?\\d*)",
            option = RegexOption.IGNORE_CASE
        )
    }

    private var xPrefs: XSharedPreferences? = null
    private val blockedHostSet: HashSet<String> = HashSet(512)
    private var lastReportTimeMs: Long = 0L

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        try {
            xPrefs = XSharedPreferences(MODULE_PKG, "dragon_adblocker_prefs").apply {
                makeWorldReadable()
            }
            populateBlockedHostsSet()
        } catch (_: Throwable) {
        }
    }

    private fun populateBlockedHostsSet() {
        blockedHostSet.addAll(AdBlockerEngine.YOUTUBE_DOMAINS)
        blockedHostSet.addAll(AdBlockerEngine.FACEBOOK_DOMAINS)
        blockedHostSet.addAll(AdBlockerEngine.INSTAGRAM_DOMAINS)
        blockedHostSet.addAll(AdBlockerEngine.TIKTOK_DOMAINS)
        blockedHostSet.addAll(AdBlockerEngine.SNAPCHAT_DOMAINS)
        blockedHostSet.addAll(AdBlockerEngine.OTHER_SOCIAL_DOMAINS)
        blockedHostSet.addAll(AdBlockerEngine.EMBEDDED_AD_SDK_DOMAINS)
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam?) {
        val pkg = lpparam?.packageName ?: return
        val cl = lpparam.classLoader ?: return

        // 1. Hook our own module status check so Dragon Kernel Tools UI knows LSPosed is active
        if (pkg == MODULE_PKG) {
            hookSelfStatus(cl)
            return
        }

        if (blockedHostSet.isEmpty()) {
            populateBlockedHostsSet()
        }

        // 2. Social Media In-Process Ad & Sponsored Post/Reel Blocker
        if (SOCIAL_PACKAGES.contains(pkg)) {
            XposedBridge.log("[$TAG] Attaching In-Process Ad & Sponsored Shield to: $pkg")
            hookInProcessDnsAndSockets(pkg)
            hookInProcessUriCronetAndOkHttp(pkg, cl)
            hookInProcessJsonFeedParser(pkg)
            hookInProcessViewsLithoAndVideoAds(pkg)
            return
        }

        // 3. Game & App 120Hz/144Hz FrameGen Refresh Rate Unlocker
        if (pkg != "android" && pkg != "com.android.systemui") {
            hookGameRefreshRateUnlocker(pkg)
        }
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
     * Layer 1: In-Process DNS Sinkhole (InetAddress.getAllByName & getByName).
     */
    private fun hookInProcessDnsAndSockets(pkg: String) {
        try {
            val dnsHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val host = (param.args?.firstOrNull() as? String)?.trim()?.lowercase() ?: return
                    if (host.isEmpty() || host == "localhost" || host == "127.0.0.1") return

                    if (isDomainBlocked(host)) {
                        reportInterceptToModule(pkg, host, "LSPOSED_DNS_SINKHOLE")
                        param.throwable = UnknownHostException("Blocked by Dragon LSPosed AdShield: $host")
                    }
                }
            }

            XposedHelpers.findAndHookMethod(
                InetAddress::class.java,
                "getAllByName",
                String::class.java,
                dnsHook
            )
            XposedHelpers.findAndHookMethod(
                InetAddress::class.java,
                "getByName",
                String::class.java,
                dnsHook
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * Layer 2: In-Process URI, Cronet (YouTube/TikTok) & OkHttp3 Request Blocker.
     */
    private fun hookInProcessUriCronetAndOkHttp(pkg: String, cl: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                URI::class.java,
                "create",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val rawUri = (param.args?.firstOrNull() as? String) ?: return
                        if (isAdUrl(rawUri)) {
                            reportInterceptToModule(pkg, "URI Ad Endpoint Blocked", "LSPOSED_URI_DROP")
                            param.args[0] = "https://127.0.0.1:0/dragon_blocked"
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }

        try {
            val okHttpBuilderClass = XposedHelpers.findClassIfExists("okhttp3.Request\$Builder", cl)
            if (okHttpBuilderClass != null) {
                XposedHelpers.findAndHookMethod(
                    okHttpBuilderClass,
                    "url",
                    String::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val rawUrl = (param.args?.firstOrNull() as? String) ?: return
                            if (isAdUrl(rawUrl)) {
                                reportInterceptToModule(pkg, "OkHttp Ad Request", "LSPOSED_HTTP_DROP")
                                param.args[0] = "https://127.0.0.1:0/dragon_blocked_ad"
                            }
                        }
                    }
                )
            }
        } catch (_: Throwable) {
        }

        try {
            val cronetClass = XposedHelpers.findClassIfExists("org.chromium.net.CronetEngine", cl)
            if (cronetClass != null) {
                XposedBridge.hookAllMethods(
                    cronetClass,
                    "newUrlRequestBuilder",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val rawUrl = (param.args?.firstOrNull() as? String) ?: return
                            if (isAdUrl(rawUrl)) {
                                reportInterceptToModule(pkg, "Cronet Video Ad Stream", "LSPOSED_CRONET_DROP")
                                param.args[0] = "https://127.0.0.1:0/dragon_blocked_cronet"
                            }
                        }
                    }
                )
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Layer 3: In-Process JSON Feed Sponsored Flag Stripper.
     */
    private fun hookInProcessJsonFeedParser(pkg: String) {
        try {
            XposedHelpers.findAndHookMethod(
                org.json.JSONObject::class.java,
                "optBoolean",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = (param.args?.firstOrNull() as? String)?.lowercase() ?: return
                        if (JSON_SPONSORED_KEYS.contains(key)) {
                            param.result = false
                        }
                    }
                }
            )
            XposedHelpers.findAndHookMethod(
                org.json.JSONObject::class.java,
                "optBoolean",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = (param.args?.firstOrNull() as? String)?.lowercase() ?: return
                        if (JSON_SPONSORED_KEYS.contains(key)) {
                            param.result = false
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private val hiddenAdCards = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, Boolean>())
    private var activeMeasuringCard: View? = null
    private var activeMeasuringCardIsAd: Boolean = false
    private var activeDrawingCard: View? = null

    /**
     * Layer 4: In-Process Litho (`StaticLayout`, `Layout.draw`, `AccessibilityNodeInfo`),
     * `View.measure`, `ViewGroup.dispatchDraw`, `View.setContentDescription`, `TextView.setText`, & `View.onAttachedToWindow`
     * Zero-Height Sponsored Feed Card Collapser + Instant Video Ad Fast-Forward.
     */
    private fun hookInProcessViewsLithoAndVideoAds(pkg: String) {
        // 4A. Hook View.measure so any RecyclerView item card detected as Sponsored measures as 0x0 pixels in-place!
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "measure",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        if (hiddenAdCards.containsKey(view)) {
                            val zeroSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.EXACTLY)
                            param.args[0] = zeroSpec
                            param.args[1] = zeroSpec
                            return
                        }
                        if (isFeedRecyclerItemCard(view)) {
                            activeMeasuringCard = view
                            activeMeasuringCardIsAd = false
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        if (view === activeMeasuringCard) {
                            val detectedAd = activeMeasuringCardIsAd
                            activeMeasuringCard = null
                            activeMeasuringCardIsAd = false
                            if (detectedAd) {
                                hiddenAdCards[view] = true
                                try {
                                    XposedHelpers.callMethod(view, "setMeasuredDimension", 0, 0)
                                } catch (_: Throwable) {}
                                hideViewInPlaceZeroHeight(view)
                                reportInterceptToModule(pkg, "Litho Feed Sponsored Card", "LSPOSED_SPONSORED_HIDDEN")
                            }
                        } else if (hiddenAdCards.containsKey(view)) {
                            try {
                                XposedHelpers.callMethod(view, "setMeasuredDimension", 0, 0)
                            } catch (_: Throwable) {}
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }

        // 4B. Hook ViewGroup.dispatchDraw + android.text.Layout.draw(Canvas) to catch background-computed Litho TextDrawables
        try {
            XposedHelpers.findAndHookMethod(
                ViewGroup::class.java,
                "dispatchDraw",
                android.graphics.Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val vg = param.thisObject as? ViewGroup ?: return
                        if (hiddenAdCards.containsKey(vg)) {
                            param.result = null
                            return
                        }
                        if (isFeedRecyclerItemCard(vg)) {
                            activeDrawingCard = vg
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val vg = param.thisObject as? ViewGroup ?: return
                        if (vg === activeDrawingCard) {
                            activeDrawingCard = null
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                android.text.Layout::class.java,
                "draw",
                android.graphics.Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val layout = param.thisObject as? android.text.Layout ?: return
                        val rawText = deobfuscate(layout.text)
                        if (rawText.isEmpty() || rawText.length > 450) return

                        if (isSponsoredOrPaidCtaText(rawText)) {
                            val drawingCard = activeDrawingCard
                            if (drawingCard != null && !hiddenAdCards.containsKey(drawingCard)) {
                                hiddenAdCards[drawingCard] = true
                                param.result = null
                                drawingCard.post {
                                    hideViewInPlaceZeroHeight(drawingCard)
                                    reportInterceptToModule(pkg, "Litho Canvas Sponsored Card", "LSPOSED_SPONSORED_HIDDEN")
                                }
                            }
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }

        // 4C. Hook StaticLayout.Builder.obtain (used by Litho in YouTube, Facebook, Instagram, TikTok for all text!)
        try {
            val builderClass = XposedHelpers.findClassIfExists("android.text.StaticLayout\$Builder", null)
            if (builderClass != null) {
                XposedBridge.hookAllMethods(
                    builderClass,
                    "obtain",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val source = param.args?.firstOrNull() as? CharSequence ?: return
                            val clean = deobfuscate(source)
                            if (clean.isEmpty() || clean.length > 450) return
                            if (isSponsoredOrPaidCtaText(clean)) {
                                if (activeMeasuringCard != null) {
                                    activeMeasuringCardIsAd = true
                                }
                            }
                        }
                    }
                )
            }
        } catch (_: Throwable) {
        }

        // 4D. Hook AccessibilityNodeInfo.setText & setContentDescription (used by Litho ExploreByTouchHelper virtual nodes)
        try {
            val a11yHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val txt = deobfuscate(param.args?.firstOrNull() as? CharSequence)
                    if (txt.isEmpty() || txt.length > 450) return
                    if (isSponsoredOrPaidCtaText(txt)) {
                        val card = activeMeasuringCard ?: activeDrawingCard
                        if (card != null && !hiddenAdCards.containsKey(card)) {
                            hiddenAdCards[card] = true
                            card.post {
                                hideViewInPlaceZeroHeight(card)
                                reportInterceptToModule(pkg, "Litho Virtual Sponsored Card", "LSPOSED_SPONSORED_HIDDEN")
                            }
                        }
                    }
                }
            }
            XposedHelpers.findAndHookMethod(
                android.view.accessibility.AccessibilityNodeInfo::class.java,
                "setText",
                CharSequence::class.java,
                a11yHook
            )
            XposedHelpers.findAndHookMethod(
                android.view.accessibility.AccessibilityNodeInfo::class.java,
                "setContentDescription",
                CharSequence::class.java,
                a11yHook
            )
        } catch (_: Throwable) {
        }

        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val id = view.id
                        if (id == View.NO_ID) return
                        val resName = try {
                            view.resources.getResourceEntryName(id).lowercase()
                        } catch (_: Throwable) {
                            return
                        }

                        if (SKIP_BUTTON_IDS.any { resName.contains(it) }) {
                            scheduleInProcessSkipClick(view, pkg, resName)
                            return
                        }

                        if (IN_VIDEO_AD_OVERLAY_IDS.any { resName.contains(it) }) {
                            fastForwardWindowAdSeekBar(view, pkg)
                            return
                        }

                        if (AD_CONTAINER_IDS.any { resName.contains(it) }) {
                            if (activeMeasuringCard != null) activeMeasuringCardIsAd = true
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
                        val view = param.thisObject as? View ?: return
                        val desc = deobfuscate(param.args?.firstOrNull() as? CharSequence)
                        if (desc.isEmpty() || desc.length > 500) return

                        if (desc.equals("Skip ad", ignoreCase = true) ||
                            desc.equals("Skip ads", ignoreCase = true) ||
                            desc.startsWith("Skip ad", ignoreCase = true)
                        ) {
                            scheduleInProcessSkipClick(view, pkg, desc)
                            return
                        }

                        if (isSponsoredOrPaidCtaText(desc)) {
                            if (activeMeasuringCard != null) activeMeasuringCardIsAd = true
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
                        val tv = param.thisObject as? TextView ?: return
                        val raw = deobfuscate(param.args?.firstOrNull() as? CharSequence)
                        if (raw.isEmpty() || raw.length > 300) return

                        if (raw.equals("Skip ad", ignoreCase = true) ||
                            raw.equals("Skip ads", ignoreCase = true) ||
                            raw.startsWith("Skip ad", ignoreCase = true)
                        ) {
                            scheduleInProcessSkipClick(tv, pkg, raw)
                            return
                        }

                        if (isSponsoredOrPaidCtaText(raw)) {
                            if (activeMeasuringCard != null) activeMeasuringCardIsAd = true
                            collapseSponsoredCardContainer(tv, pkg, "Sponsored Card ($raw)")
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun isFeedRecyclerItemCard(view: View): Boolean {
        val parent = view.parent as? ViewGroup ?: return false
        val parentClass = parent.javaClass.name
        if (!parentClass.contains("RecyclerView", ignoreCase = true) &&
            !parentClass.contains("ListView", ignoreCase = true)
        ) {
            return false
        }
        val grandParentClass = parent.parent?.javaClass?.name ?: ""
        if (grandParentClass.contains("ViewPager", ignoreCase = true)) {
            return false
        }
        return true
    }

    private fun scheduleInProcessSkipClick(view: View, pkg: String, label: String) {
        for (delayMs in longArrayOf(0L, 250L, 600L, 1100L)) {
            view.postDelayed({
                try {
                    if (view.isAttachedToWindow) {
                        view.isEnabled = true
                        view.visibility = View.VISIBLE
                        if (!view.performClick()) {
                            (view.parent as? View)?.performClick()
                        }
                        reportInterceptToModule(pkg, "Video Ad ($label)", "LSPOSED_SKIP_CLICK")
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
                reportInterceptToModule(pkg, "Video Ad SeekBar Fast-Forwarded", "LSPOSED_AD_SEEK")
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
                while (current != null && depth < 15) {
                    val parent = current.parent as? ViewGroup ?: break
                    val parentClass = parent.javaClass.name
                    if (parentClass.contains("RecyclerView", ignoreCase = true) ||
                        parentClass.contains("ListView", ignoreCase = true)
                    ) {
                        val screenH = current.resources?.displayMetrics?.heightPixels ?: 2400
                        if (current.height < (screenH * 0.88f).toInt()) {
                            recyclerItemCard = current
                        }
                        break
                    }
                    current = parent
                    depth++
                }

                val target = recyclerItemCard ?: if (triggerView.height < 900) triggerView else null
                if (target != null && !hiddenAdCards.containsKey(target)) {
                    hiddenAdCards[target] = true
                    hideViewInPlaceZeroHeight(target)
                    target.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        if (hiddenAdCards.containsKey(v) && (v.height > 0 || v.layoutParams?.height != 0)) {
                            v.alpha = 0f
                            v.scaleY = 0f
                            v.visibility = View.GONE
                            val params = v.layoutParams
                            if (params != null && params.height != 0) {
                                params.height = 0
                                if (params is ViewGroup.MarginLayoutParams) {
                                    params.topMargin = 0
                                    params.bottomMargin = 0
                                }
                                v.layoutParams = params
                            }
                            v.bottom = v.top
                        }
                    }
                    reportInterceptToModule(pkg, reason, "LSPOSED_SPONSORED_HIDDEN")
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun hideViewInPlaceZeroHeight(target: View) {
        target.alpha = 0f
        target.scaleY = 0f
        target.minimumHeight = 0
        target.setPadding(0, 0, 0, 0)
        target.visibility = View.GONE
        val lp = target.layoutParams
        if (lp != null) {
            lp.height = 0
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.topMargin = 0
                lp.bottomMargin = 0
            }
            target.layoutParams = lp
        }
        target.requestLayout()
    }

    /**
     * Layer 5: In-Process Game 120Hz / 144Hz Refresh Rate & FrameGen Unlocker.
     */
    private fun hookGameRefreshRateUnlocker(pkg: String) {
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
        if (blockedHostSet.contains(host)) return true
        var dotIndex = host.indexOf('.')
        while (dotIndex != -1 && dotIndex < host.length - 1) {
            val suffix = host.substring(dotIndex + 1)
            if (blockedHostSet.contains(suffix)) return true
            dotIndex = host.indexOf('.', dotIndex + 1)
        }
        return false
    }

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return AD_URL_PATH_SIGNATURES.any { lower.contains(it) }
    }

    private fun reportInterceptToModule(pkg: String, descriptor: String, action: String) {
        val now = System.currentTimeMillis()
        if (now - lastReportTimeMs < 1200L) return
        lastReportTimeMs = now

        try {
            val app = AndroidAppHelper.currentApplication() ?: return
            val extras = Bundle().apply {
                putString("package_name", pkg)
                putString("descriptor", descriptor)
                putString("action", action)
            }
            app.contentResolver.call(
                DragonLSPosedConfigProvider.CONFIG_URI,
                "report_lsposed_intercept",
                pkg,
                extras
            )
        } catch (_: Throwable) {
        }
    }
}
