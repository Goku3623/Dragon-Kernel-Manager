package com.example.data.model

enum class AdBlockerMode(
    val title: String,
    val subtitle: String
) {
    HYBRID_ROOT_DNS(
        title = "Hybrid Root + DoH DNS",
        subtitle = "Combined /etc/hosts loopback sinkhole and secure Private DNS for total ad & tracking rejection"
    ),
    PRIVATE_DNS(
        title = "Private DNS (DoT / DoH)",
        subtitle = "System-level encrypted DNS filtering (no VPN required, zero battery overhead)"
    ),
    SYSTEM_HOSTS(
        title = "System Hosts Sinkhole",
        subtitle = "Root mount-bind loopback (0.0.0.0) mapping for instant local socket rejection"
    ),
    IPTABLES_FILTER(
        title = "Kernel Netfilter Iptables",
        subtitle = "Hardware packet-level string inspection dropping ad telemetry and tracking beacons"
    )
}

enum class SocialPlatform(
    val id: String,
    val displayName: String,
    val shortName: String,
    val packageNames: List<String>,
    val defaultRulesCount: Int,
    val description: String,
    val sampleDomains: List<String>
) {
    YOUTUBE(
        id = "youtube",
        displayName = "YouTube & Shorts",
        shortName = "YouTube",
        packageNames = listOf(
            "com.google.android.youtube",
            "com.google.android.youtube.tv",
            "com.google.android.apps.youtube.music",
            "com.google.android.apps.youtube.creator"
        ),
        defaultRulesCount = 1420,
        description = "Video pre-roll & mid-roll ad servers, sponsored promotional overlays, and background tracking",
        sampleDomains = listOf(
            "s.youtube.com",
            "video-stats.l.google.com",
            "ads.youtube.com",
            "pagead2.googlesyndication.com",
            "googleads.g.doubleclick.net",
            "pubads.g.doubleclick.net",
            "securepubads.g.doubleclick.net"
        )
    ),
    FACEBOOK(
        id = "facebook",
        displayName = "Facebook & Messenger",
        shortName = "Facebook",
        packageNames = listOf(
            "com.facebook.katana",
            "com.facebook.lite",
            "com.facebook.orca",
            "com.facebook.mlite"
        ),
        defaultRulesCount = 3480,
        description = "Facebook Reels video ads, Audience Network, sponsored feed injections, pixel trackers & story commercials",
        sampleDomains = listOf(
            "an.facebook.com",
            "b-graph.facebook.com",
            "creative.ak.fbcdn.net",
            "ads.facebook.com",
            "mobileads.facebook.com",
            "pixel.facebook.com",
            "advertising.facebook.com"
        )
    ),
    INSTAGRAM(
        id = "instagram",
        displayName = "Instagram",
        shortName = "Instagram",
        packageNames = listOf(
            "com.instagram.android",
            "com.instagram.lite"
        ),
        defaultRulesCount = 2460,
        description = "Instagram Reels sponsored video ads, stories interstitials, shopping graph ads, and client telemetry",
        sampleDomains = listOf(
            "graph.instagram.com",
            "i.instagram.com/api/v1/ads",
            "ads.instagram.com",
            "instagram.c10r.facebook.com",
            "logging.instagram.com"
        )
    ),
    TIKTOK(
        id = "tiktok",
        displayName = "TikTok",
        shortName = "TikTok",
        packageNames = listOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go"
        ),
        defaultRulesCount = 1940,
        description = "Full-screen video interstitial ads, live stream shopping promo popups, and ByteDance tracking",
        sampleDomains = listOf(
            "ads.tiktok.com",
            "analytics.tiktok.com",
            "mon.tiktokv.com",
            "log.byteoversea.com",
            "pg-ad.tiktokv.com",
            "toblog.byteoversea.com",
            "rtlog.tiktokv.com"
        )
    ),
    SNAPCHAT(
        id = "snapchat",
        displayName = "Snapchat",
        shortName = "Snapchat",
        packageNames = listOf(
            "com.snapchat.android"
        ),
        defaultRulesCount = 1120,
        description = "Discover channel sponsored tiles, Snap story commercial breaks, and audience analytics",
        sampleDomains = listOf(
            "ads.snapchat.com",
            "tr.snapchat.com",
            "app-analytics.snapchat.com",
            "sc-analytics.appspot.com",
            "story-ad-api.snapchat.com",
            "shadow.snapchat.com"
        )
    ),
    OTHER_SOCIAL(
        id = "other_social",
        displayName = "X (Twitter), Reddit & More",
        shortName = "Other Social",
        packageNames = listOf(
            "com.twitter.android",
            "com.twitter.android.lite",
            "com.reddit.frontpage",
            "com.pinterest",
            "tv.twitch.android.app"
        ),
        defaultRulesCount = 3650,
        description = "Promoted tweets, sponsored Reddit feed links, Twitch stream pre-rolls, and analytics networks",
        sampleDomains = listOf(
            "ads-api.twitter.com",
            "analytics.twitter.com",
            "alb.reddit.com",
            "events.redditmedia.com",
            "ads.pinterest.com",
            "usher.ttvnw.net"
        )
    )
}

data class AdBlockerConfig(
    val isGlobalEnabled: Boolean = true,
    val autoActivateOnSocialApps: Boolean = true,
    val mode: AdBlockerMode = AdBlockerMode.HYBRID_ROOT_DNS,
    val customDnsProvider: String = "dns.adguard.com",
    val blockVideoAds: Boolean = true,
    val blockSponsoredPosts: Boolean = true,
    val blockTelemetry: Boolean = true,
    val blockReelsAndStoriesAds: Boolean = true,
    val autoPurgeSocialAdCaches: Boolean = true,
    val platformToggles: Map<String, Boolean> = mapOf(
        SocialPlatform.YOUTUBE.id to true,
        SocialPlatform.FACEBOOK.id to true,
        SocialPlatform.INSTAGRAM.id to true,
        SocialPlatform.TIKTOK.id to true,
        SocialPlatform.SNAPCHAT.id to true,
        SocialPlatform.OTHER_SOCIAL.id to true
    ),
    val customBlacklistDomains: List<String> = emptyList(),
    val customWhitelistDomains: List<String> = emptyList()
)

data class AdBlockEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val platformName: String,
    val domain: String,
    val action: String = "SINKHOLED"
)

data class AdBlockerTelemetry(
    val isActive: Boolean = true,
    val isAutoTriggered: Boolean = false,
    val activeForegroundApp: String = "",
    val activeForegroundPlatform: SocialPlatform? = null,
    val totalBlockedQueries: Long = 2489L,
    val blockedTodayQueries: Long = 412L,
    val activeRulesCount: Int = 11310,
    val activeDnsSpecifier: String = "dns.adguard.com",
    val activeHostsEntriesCount: Int = 8940,
    val recentBlockedEvents: List<AdBlockEvent> = listOf(
        AdBlockEvent(
            System.currentTimeMillis() - 25000,
            "YouTube",
            "pagead2.googlesyndication.com",
            "SINKHOLED"
        ),
        AdBlockEvent(
            System.currentTimeMillis() - 42000,
            "Instagram",
            "graph.instagram.com/logging_client_events",
            "DNS_BLOCKED"
        ),
        AdBlockEvent(
            System.currentTimeMillis() - 85000,
            "TikTok",
            "log.byteoversea.com",
            "SINKHOLED"
        ),
        AdBlockEvent(
            System.currentTimeMillis() - 120000,
            "Facebook",
            "an.facebook.com",
            "NETFILTER_DROP"
        ),
        AdBlockEvent(
            System.currentTimeMillis() - 180000,
            "Snapchat",
            "story-ad-api.snapchat.com",
            "SINKHOLED"
        )
    ),
    val lastActivatedTimestamp: Long = System.currentTimeMillis()
)
