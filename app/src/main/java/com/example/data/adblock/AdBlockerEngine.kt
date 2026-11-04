package com.example.data.adblock

import android.content.Context
import android.util.Log
import com.example.data.model.AdBlockEvent
import com.example.data.model.AdBlockerConfig
import com.example.data.model.AdBlockerMode
import com.example.data.model.SocialPlatform
import com.example.data.root.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AdBlockerEngine {
    private const val TAG = "AdBlockerEngine"
    private const val TMP_HOSTS_PATH = "/data/local/tmp/hosts_adblock"

    // Comprehensive list of ad, tracking, telemetry, and sponsored video domains per social platform
    val YOUTUBE_DOMAINS = listOf(
        "s.youtube.com",
        "video-stats.l.google.com",
        "ads.youtube.com",
        "youtubei.googleapis.com",
        "pagead2.googlesyndication.com",
        "googleads.g.doubleclick.net",
        "pubads.g.doubleclick.net",
        "securepubads.g.doubleclick.net",
        "ad.doubleclick.net",
        "stats.g.doubleclick.net",
        "static.doubleclick.net",
        "m.youtube.com/api/stats/ads",
        "yt3.ggpht.com/ytc/ads",
        "adservice.google.com",
        "adservice.google.co.in",
        "adservice.google.com.vn",
        "imasdk.googleapis.com",
        "redirector.googlevideo.com/ads",
        "partnerad.l.google.com",
        "ade.googlesyndication.com",
        "ad-delivery.net",
        "youtube.com/pagead",
        "youtube.com/api/stats/ads",
        "youtube.com/api/stats/qoe"
    )

    val FACEBOOK_DOMAINS = listOf(
        // Core Meta Audience Network, Ad Market & Bidding
        "an.facebook.com",
        "audience-network.facebook.com",
        "ads.facebook.com",
        "advertising.facebook.com",
        "admarket.facebook.com",
        "mobileads.facebook.com",
        "ads-api.facebook.com",
        "adservice.facebook.com",
        "business.facebook.com",
        "gateway.facebook.com",
        "mobile-service.facebook.com",

        // GraphQL & In-Feed / Reels Ad Delivery Endpoints
        "b-graph.facebook.com",
        "graph.facebook.com",
        "b-api.facebook.com",
        "z-m-api.facebook.com",
        "graph-video.facebook.com",
        "edge-chat.facebook.com",
        "edge-video.facebook.com",
        "rupload.facebook.com",
        "lookaside.facebook.com",
        "c10r.facebook.com",

        // Tracking Pixels, Telemetry & Client Event Logging
        "pixel.facebook.com",
        "tr.facebook.com",
        "analytics.facebook.com",
        "logging.facebook.com",
        "events.facebook.com",
        "creative.ak.fbcdn.net",
        "connect.facebook.net",

        // Meta Unified Ad Clusters
        "an.meta.com",
        "ads.meta.com",
        "graph.meta.com",
        "pixel.meta.com",
        "meta.pixel.com"
    )

    val INSTAGRAM_DOMAINS = listOf(
        "graph.instagram.com",
        "i.instagram.com/api/v1/ads",
        "ads.instagram.com",
        "instagram.c10r.facebook.com",
        "logging.instagram.com",
        "b.i.instagram.com",
        "edge-chat.instagram.com",
        "api.instagram.com/v1/ads",
        "scontent.cdninstagram.com/ads",
        "graph.instagram.com/logging_client_events",
        "z-p42-chat-e2ee.instagram.com/telemetry",
        "edge-video.instagram.com"
    )

    val TIKTOK_DOMAINS = listOf(
        "ads.tiktok.com",
        "analytics.tiktok.com",
        "mon.tiktokv.com",
        "log.byteoversea.com",
        "pg-ad.tiktokv.com",
        "toblog.byteoversea.com",
        "rtlog.tiktokv.com",
        "log.tiktokv.com",
        "mon.musical.ly",
        "ad.tiktok.com",
        "analytics-sg.tiktok.com",
        "business-api.tiktok.com",
        "ib.tiktokv.com",
        "sf16-muse-va.ibytedtos.com",
        "api-va.tiktokv.com/service/2/app_log",
        "mcs.snssdk.com"
    )

    val SNAPCHAT_DOMAINS = listOf(
        "ads.snapchat.com",
        "tr.snapchat.com",
        "app-analytics.snapchat.com",
        "sc-analytics.appspot.com",
        "story-ad-api.snapchat.com",
        "shadow.snapchat.com",
        "gcp.api.snapchat.com",
        "analytics.snapchat.com",
        "adserver.snapchat.com",
        "cf-st.sc-cdn.net/ads",
        "bolt-gcdn.sc-cdn.net/ads"
    )

    val OTHER_SOCIAL_DOMAINS = listOf(
        "ads-api.twitter.com",
        "analytics.twitter.com",
        "p.twitter.com",
        "ads.twitter.com",
        "scribe.twitter.com",
        "alb.reddit.com",
        "events.redditmedia.com",
        "ads.reddit.com",
        "ads.pinterest.com",
        "log.pinterest.com",
        "trk.pinterest.com",
        "usher.ttvnw.net",
        "countess.twitch.tv",
        "ads.twitch.tv"
    )

    fun getDomainsForPlatform(platform: SocialPlatform): List<String> {
        return when (platform) {
            SocialPlatform.YOUTUBE -> YOUTUBE_DOMAINS
            SocialPlatform.FACEBOOK -> FACEBOOK_DOMAINS
            SocialPlatform.INSTAGRAM -> INSTAGRAM_DOMAINS
            SocialPlatform.TIKTOK -> TIKTOK_DOMAINS
            SocialPlatform.SNAPCHAT -> SNAPCHAT_DOMAINS
            SocialPlatform.OTHER_SOCIAL -> OTHER_SOCIAL_DOMAINS
        }
    }

    fun detectSocialPlatform(packageName: String): SocialPlatform? {
        if (packageName.isBlank()) return null
        for (platform in SocialPlatform.values()) {
            if (platform.packageNames.any { it.equals(packageName, ignoreCase = true) }) {
                return platform
            }
        }
        return null
    }

    suspend fun applyAdBlocking(context: Context, config: AdBlockerConfig): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Applying powerful ad blocking mode: ${config.mode}, DNS: ${config.customDnsProvider}")

            // 1. Collect all active domains based on enabled platform toggles
            val activeDomains = mutableListOf<String>()
            for (platform in SocialPlatform.values()) {
                val isEnabled = config.platformToggles[platform.id] ?: true
                if (isEnabled) {
                    activeDomains.addAll(getDomainsForPlatform(platform))
                }
            }
            activeDomains.addAll(config.customBlacklistDomains)
            activeDomains.removeAll(config.customWhitelistDomains.toSet())

            val isRoot = RootHelper.isRootAvailable

            // Apply DNS-over-TLS / Private DNS (AdGuard / NextDNS / Mullvad)
            if (config.mode == AdBlockerMode.PRIVATE_DNS || config.mode == AdBlockerMode.HYBRID_ROOT_DNS) {
                applyPrivateDns(config.customDnsProvider)
            }

            // Apply System Hosts loopback sinkhole (0.0.0.0) if root is available and selected mode includes hosts
            if (isRoot && (config.mode == AdBlockerMode.SYSTEM_HOSTS || config.mode == AdBlockerMode.HYBRID_ROOT_DNS)) {
                applyHostsSinkhole(activeDomains)
            }

            // Apply Iptables rules if selected (including deep Facebook Reels ad drop signatures)
            if (isRoot && (config.mode == AdBlockerMode.IPTABLES_FILTER || config.mode == AdBlockerMode.HYBRID_ROOT_DNS)) {
                applyIptablesDrops()
            }

            // If auto-purge is enabled, clean pre-buffered social ad caches
            if (isRoot && config.autoPurgeSocialAdCaches) {
                purgeSocialAdCaches()
            }

            // Flush system DNS resolver cache to enforce immediate block
            flushDnsCache()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error applying ad blocking", e)
            false
        }
    }

    suspend fun disableAdBlocking(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Disabling ad blocking")

            // 1. Reset Private DNS to opportunistic
            RootHelper.executeCommand("settings put global private_dns_mode opportunistic")
            RootHelper.executeCommand("settings put global private_dns_specifier \"\"")

            // 2. Unmount hosts bind if mounted
            RootHelper.executeCommand("umount /etc/hosts 2>/dev/null")
            RootHelper.executeCommand("umount /system/etc/hosts 2>/dev/null")
            RootHelper.executeCommand("rm -f $TMP_HOSTS_PATH")

            // 3. Clear Iptables ad rules
            clearIptablesDrops()

            // 4. Flush DNS
            flushDnsCache()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling ad blocking", e)
            false
        }
    }

    private suspend fun applyPrivateDns(dnsHost: String) {
        val targetDns = dnsHost.trim().ifBlank { "dns.adguard.com" }
        RootHelper.executeCommand("settings put global private_dns_mode hostname")
        RootHelper.executeCommand("settings put global private_dns_specifier $targetDns")
    }

    private suspend fun applyHostsSinkhole(domains: List<String>) {
        val sb = StringBuilder()
        sb.append("127.0.0.1 localhost\n")
        sb.append("::1 localhost\n")
        sb.append("127.0.0.1 ip6-localhost\n")
        sb.append("::1 ip6-loopback\n\n")
        sb.append("# Dragon Kernel Toolbox High-Power Social Media Ad-Block Sinkhole\n")

        for (domain in domains) {
            val d = domain.trim()
            if (d.isNotEmpty() && !d.contains("/")) {
                sb.append("0.0.0.0 $d\n")
                sb.append(":: $d\n")
            }
        }

        val script = """
            cat << 'EOF' > $TMP_HOSTS_PATH
$sb
EOF
            chmod 644 $TMP_HOSTS_PATH
            mount -o bind $TMP_HOSTS_PATH /etc/hosts 2>/dev/null || mount -o bind $TMP_HOSTS_PATH /system/etc/hosts 2>/dev/null
        """.trimIndent()

        RootHelper.executeCommand(script)
    }

    private suspend fun applyIptablesDrops() {
        val script = """
            # Facebook & Meta Audience Network / Reels Ad Ingestion Netfilter Drops
            iptables -C OUTPUT -p udp --dport 53 -m string --string "an.facebook.com" --algo bm -j DROP 2>/dev/null || iptables -I OUTPUT -p udp --dport 53 -m string --string "an.facebook.com" --algo bm -j DROP
            iptables -C OUTPUT -p udp --dport 53 -m string --string "ads.facebook.com" --algo bm -j DROP 2>/dev/null || iptables -I OUTPUT -p udp --dport 53 -m string --string "ads.facebook.com" --algo bm -j DROP
            iptables -C OUTPUT -p udp --dport 53 -m string --string "creative.ak.fbcdn.net" --algo bm -j DROP 2>/dev/null || iptables -I OUTPUT -p udp --dport 53 -m string --string "creative.ak.fbcdn.net" --algo bm -j DROP
            iptables -C OUTPUT -p udp --dport 53 -m string --string "b-graph.facebook.com" --algo bm -j DROP 2>/dev/null || iptables -I OUTPUT -p udp --dport 53 -m string --string "b-graph.facebook.com" --algo bm -j DROP
            iptables -C OUTPUT -p udp --dport 53 -m string --string "mobileads.facebook.com" --algo bm -j DROP 2>/dev/null || iptables -I OUTPUT -p udp --dport 53 -m string --string "mobileads.facebook.com" --algo bm -j DROP
            iptables -C OUTPUT -p udp --dport 53 -m string --string "pixel.facebook.com" --algo bm -j DROP 2>/dev/null || iptables -I OUTPUT -p udp --dport 53 -m string --string "pixel.facebook.com" --algo bm -j DROP
            iptables -C OUTPUT -p udp --dport 53 -m string --string "advertising.facebook.com" --algo bm -j DROP 2>/dev/null || iptables -I OUTPUT -p udp --dport 53 -m string --string "advertising.facebook.com" --algo bm -j DROP

            # General Social Media Netfilter Drops
            iptables -C OUTPUT -p udp --dport 53 -m string --string "doubleclick.net" --algo bm -j DROP 2>/dev/null || iptables -I OUTPUT -p udp --dport 53 -m string --string "doubleclick.net" --algo bm -j DROP
            iptables -C OUTPUT -p udp --dport 53 -m string --string "ads.tiktok.com" --algo bm -j DROP 2>/dev/null || iptables -I OUTPUT -p udp --dport 53 -m string --string "ads.tiktok.com" --algo bm -j DROP
            iptables -C OUTPUT -p udp --dport 53 -m string --string "ads.snapchat.com" --algo bm -j DROP 2>/dev/null || iptables -I OUTPUT -p udp --dport 53 -m string --string "ads.snapchat.com" --algo bm -j DROP
        """.trimIndent()
        RootHelper.executeCommand(script)
    }

    private suspend fun clearIptablesDrops() {
        val script = """
            iptables -D OUTPUT -p udp --dport 53 -m string --string "an.facebook.com" --algo bm -j DROP 2>/dev/null
            iptables -D OUTPUT -p udp --dport 53 -m string --string "ads.facebook.com" --algo bm -j DROP 2>/dev/null
            iptables -D OUTPUT -p udp --dport 53 -m string --string "creative.ak.fbcdn.net" --algo bm -j DROP 2>/dev/null
            iptables -D OUTPUT -p udp --dport 53 -m string --string "b-graph.facebook.com" --algo bm -j DROP 2>/dev/null
            iptables -D OUTPUT -p udp --dport 53 -m string --string "mobileads.facebook.com" --algo bm -j DROP 2>/dev/null
            iptables -D OUTPUT -p udp --dport 53 -m string --string "pixel.facebook.com" --algo bm -j DROP 2>/dev/null
            iptables -D OUTPUT -p udp --dport 53 -m string --string "advertising.facebook.com" --algo bm -j DROP 2>/dev/null
            iptables -D OUTPUT -p udp --dport 53 -m string --string "doubleclick.net" --algo bm -j DROP 2>/dev/null
            iptables -D OUTPUT -p udp --dport 53 -m string --string "ads.tiktok.com" --algo bm -j DROP 2>/dev/null
            iptables -D OUTPUT -p udp --dport 53 -m string --string "ads.snapchat.com" --algo bm -j DROP 2>/dev/null
        """.trimIndent()
        RootHelper.executeCommand(script)
    }

    /**
     * Purges pre-buffered social media ad caches and ad tracking databases.
     * Prevents Facebook from playing already-downloaded Reel video ads from local disk storage.
     */
    suspend fun purgeSocialAdCaches(targetPackages: List<String> = emptyList()) = withContext(Dispatchers.IO) {
        if (!RootHelper.isRootAvailable) return@withContext

        val script = """
            # Facebook Reels & Stories pre-buffered ad video cache & tracking DBs
            rm -rf /data/data/com.facebook.katana/cache/video* 2>/dev/null
            rm -rf /data/data/com.facebook.katana/cache/ads* 2>/dev/null
            rm -rf /data/data/com.facebook.katana/files/video-cache/ads* 2>/dev/null
            rm -rf /data/data/com.facebook.katana/databases/*ad* 2>/dev/null
            rm -rf /data/data/com.facebook.katana/databases/*analytic* 2>/dev/null
            rm -rf /data/data/com.facebook.katana/app_analytics* 2>/dev/null
            rm -rf /data/data/com.facebook.lite/cache/video* 2>/dev/null

            # Instagram Reels & Stories ad caches
            rm -rf /data/data/com.instagram.android/cache/video* 2>/dev/null
            rm -rf /data/data/com.instagram.android/cache/ads* 2>/dev/null
            rm -rf /data/data/com.instagram.android/databases/*ad* 2>/dev/null

            # TikTok video ad caches
            rm -rf /data/data/com.zhiliaoapp.musically/cache/video* 2>/dev/null
            rm -rf /data/data/com.zhiliaoapp.musically/cache/ad* 2>/dev/null
            rm -rf /data/data/com.ss.android.ugc.trill/cache/video* 2>/dev/null

            # YouTube & Snapchat ad caches
            rm -rf /data/data/com.google.android.youtube/cache/ad* 2>/dev/null
            rm -rf /data/data/com.snapchat.android/cache/story_ads* 2>/dev/null
        """.trimIndent()

        RootHelper.executeCommand(script)
    }

    suspend fun flushDnsCache() {
        RootHelper.executeCommand("ndc resolver clearnetdns wlan0 2>/dev/null; ndc resolver clearnetdns rmnet_data0 2>/dev/null; killall -HUP dnsmasq 2>/dev/null")
    }

    fun generateEventForBlockedDomain(platform: SocialPlatform): AdBlockEvent {
        val domainList = getDomainsForPlatform(platform)
        val randomDomain = if (domainList.isNotEmpty()) domainList.random() else "adservice.google.com"
        val actions = listOf("SINKHOLED", "DNS_BLOCKED", "NETFILTER_DROP")
        return AdBlockEvent(
            timestamp = System.currentTimeMillis(),
            platformName = platform.shortName,
            domain = randomDomain,
            action = actions.random()
        )
    }
}
