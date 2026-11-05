package com.example.data.visualstyle

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

enum class LutGradingProfile(
    val id: String,
    val label: String,
    val description: String,
    val shaderIndex: Int
) {
    NONE("none", "Neutral Bypass", "No LUT color grading applied", 0),
    COMPETITIVE_CLARITY("competitive", "Tactical Clarity LUT", "Lifted shadow visibility & controlled highlight roll-off", 1),
    ULTRA_VIBRANT("vibrant", "Hyper-Vibrance LUT", "Selective chroma boost protecting skin/neutral tones", 2),
    CINEMATIC_TEAL_ORANGE("cinematic", "Cinematic Teal & Gold", "Warm golden highlights with cool cyan-teal shadow split-toning", 3),
    AMOLED_DEEP_BLACK("amoled", "AMOLED Deep Obsidian", "Crushed near-black floor with high micro-contrast punch", 4),
    HDR_INSPIRED_TONEMAP("hdr_tonemap", "ACES-Inspired S-Curve", "Expanded local perceived dynamic range (Preview tone-mapping)", 5),
    NIGHT_WARM_SHIELD("night_shield", "Circadian Warm LUT", "Attenuated short-wavelength blue spectrum for dark rooms", 6),
    RETRO_ARCADE_CRT("retro_crt", "Vintage Arcade 80s", "Faded phosphor blacks, warm amber mids & split-tone nostalgia", 7)
}

enum class GamingPreviewScene(
    val id: String,
    val title: String,
    val subtitle: String
) {
    COMPETITIVE_FPS("fps_arena", "Tactical FPS Arena", "Shadow visibility, crosshair contrast & foliage separation"),
    CYBERPUNK_CITY("cyber_city", "Neon Cyberpunk Night", "High-chroma neon signage, specular reflections & deep blacks"),
    AMOLED_DUNGEON("dark_dungeon", "Abyssal RPG Dungeon", "Near-black shadow detail, torchlight gradients & AMOLED floor"),
    OPEN_WORLD_HDR("open_world", "Open-World Horizon", "Sky highlights, natural foliage & color temperature balance"),
    CUSTOM_IMAGE("custom_image", "Custom User Image", "Loaded via Android Photo Picker")
}

enum class TargetWindowColorMode(
    val id: String,
    val label: String,
    val description: String
) {
    DEFAULT("DEFAULT", "Standard sRGB (Default)", "Standard Android window color space"),
    WIDE_COLOR_GAMUT("WIDE_GAMUT", "Wide Color Gamut (DCI-P3)", "Requests wide-gamut rendering on supported displays (API 26+)"),
    HDR("HDR", "HDR Window Mode", "Requests HDR window output on hardware-capable HDR panels (API 26+)")
}

data class VisualAdjustments(
    val saturationPercent: Float = 100f,       // 0 - 200%
    val contrastPercent: Float = 100f,         // 50 - 150%
    val brightnessPercent: Float = 100f,       // 50 - 150%
    val colorTempKelvin: Float = 6500f,        // 3000K - 9000K
    val sharpness: Float = 15f,                // 0 - 100
    val gamma: Float = 1.0f,                   // 0.5 - 2.5
    val redChannelPercent: Float = 100f,       // 0 - 200%
    val greenChannelPercent: Float = 100f,     // 0 - 200%
    val blueChannelPercent: Float = 100f,      // 0 - 200%
    val enableGrayscale: Boolean = false,
    val enableSepia: Boolean = false,
    val enableInvert: Boolean = false,
    val useCustomColorMatrix: Boolean = false,
    // 3x3 custom color matrix row-major (default identity)
    val customMatrixValues: List<Float> = listOf(
        1f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 1f
    ),
    val lutProfile: LutGradingProfile = LutGradingProfile.NONE,
    val lutIntensityPercent: Float = 75f       // 0 - 100%
) {
    fun clamped(): VisualAdjustments = copy(
        saturationPercent = saturationPercent.coerceIn(0f, 200f),
        contrastPercent = contrastPercent.coerceIn(50f, 150f),
        brightnessPercent = brightnessPercent.coerceIn(50f, 150f),
        colorTempKelvin = colorTempKelvin.coerceIn(3000f, 9000f),
        sharpness = sharpness.coerceIn(0f, 100f),
        gamma = gamma.coerceIn(0.5f, 2.5f),
        redChannelPercent = redChannelPercent.coerceIn(0f, 200f),
        greenChannelPercent = greenChannelPercent.coerceIn(0f, 200f),
        blueChannelPercent = blueChannelPercent.coerceIn(0f, 200f),
        lutIntensityPercent = lutIntensityPercent.coerceIn(0f, 100f),
        customMatrixValues = if (customMatrixValues.size == 9) {
            customMatrixValues.map { it.coerceIn(-2f, 2f) }
        } else {
            listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
    )

    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("saturationPercent", saturationPercent.toDouble())
        obj.put("contrastPercent", contrastPercent.toDouble())
        obj.put("brightnessPercent", brightnessPercent.toDouble())
        obj.put("colorTempKelvin", colorTempKelvin.toDouble())
        obj.put("sharpness", sharpness.toDouble())
        obj.put("gamma", gamma.toDouble())
        obj.put("redChannelPercent", redChannelPercent.toDouble())
        obj.put("greenChannelPercent", greenChannelPercent.toDouble())
        obj.put("blueChannelPercent", blueChannelPercent.toDouble())
        obj.put("enableGrayscale", enableGrayscale)
        obj.put("enableSepia", enableSepia)
        obj.put("enableInvert", enableInvert)
        obj.put("useCustomColorMatrix", useCustomColorMatrix)
        val matrixArr = JSONArray()
        customMatrixValues.forEach { matrixArr.put(it.toDouble()) }
        obj.put("customMatrixValues", matrixArr)
        obj.put("lutProfile", lutProfile.id)
        obj.put("lutIntensityPercent", lutIntensityPercent.toDouble())
        return obj
    }

    companion object {
        val DEFAULT = VisualAdjustments()

        fun fromJson(obj: JSONObject?): VisualAdjustments {
            if (obj == null) return DEFAULT
            val matrixList = mutableListOf<Float>()
            val arr = obj.optJSONArray("customMatrixValues")
            if (arr != null && arr.length() == 9) {
                for (i in 0 until 9) {
                    matrixList.add(arr.optDouble(i, if (i % 4 == 0) 1.0 else 0.0).toFloat())
                }
            } else {
                matrixList.addAll(listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
            }
            val lutId = obj.optString("lutProfile", LutGradingProfile.NONE.id)
            val lut = LutGradingProfile.entries.find { it.id == lutId } ?: LutGradingProfile.NONE
            return VisualAdjustments(
                saturationPercent = obj.optDouble("saturationPercent", 100.0).toFloat(),
                contrastPercent = obj.optDouble("contrastPercent", 100.0).toFloat(),
                brightnessPercent = obj.optDouble("brightnessPercent", 100.0).toFloat(),
                colorTempKelvin = obj.optDouble("colorTempKelvin", 6500.0).toFloat(),
                sharpness = obj.optDouble("sharpness", 15.0).toFloat(),
                gamma = obj.optDouble("gamma", 1.0).toFloat(),
                redChannelPercent = obj.optDouble("redChannelPercent", 100.0).toFloat(),
                greenChannelPercent = obj.optDouble("greenChannelPercent", 100.0).toFloat(),
                blueChannelPercent = obj.optDouble("blueChannelPercent", 100.0).toFloat(),
                enableGrayscale = obj.optBoolean("enableGrayscale", false),
                enableSepia = obj.optBoolean("enableSepia", false),
                enableInvert = obj.optBoolean("enableInvert", false),
                useCustomColorMatrix = obj.optBoolean("useCustomColorMatrix", false),
                customMatrixValues = matrixList,
                lutProfile = lut,
                lutIntensityPercent = obj.optDouble("lutIntensityPercent", 75.0).toFloat()
            ).clamped()
        }

        /**
         * Converts color temperature in Kelvin (3000K..9000K, neutral = 6500K)
         * to normalized RGB multipliers using Tanner Helland's Planckian locus approximation,
         * normalized so 6500K produces exactly (1.0, 1.0, 1.0).
         */
        fun kelvinToRgbMultipliers(kelvin: Float): FloatArray {
            val raw = rawKelvinToRgb(kelvin.coerceIn(3000f, 9000f))
            val ref = rawKelvinToRgb(6500f)
            return floatArrayOf(
                (raw[0] / ref[0]).coerceIn(0.55f, 1.45f),
                (raw[1] / ref[1]).coerceIn(0.65f, 1.35f),
                (raw[2] / ref[2]).coerceIn(0.45f, 1.55f)
            )
        }

        private fun rawKelvinToRgb(kelvin: Float): FloatArray {
            val temp = kelvin / 100.0
            val r: Double
            val g: Double
            val b: Double

            if (temp <= 66.0) {
                r = 255.0
                g = (99.4708025861 * ln(temp) - 161.1195681661).coerceIn(0.0, 255.0)
                b = if (temp <= 19.0) {
                    0.0
                } else {
                    (138.5177312231 * ln(temp - 10.0) - 305.0447927307).coerceIn(0.0, 255.0)
                }
            } else {
                r = (329.698727446 * (temp - 60.0).pow(-0.1332047592)).coerceIn(0.0, 255.0)
                g = (288.1221695283 * (temp - 60.0).pow(-0.0755148492)).coerceIn(0.0, 255.0)
                b = 255.0
            }
            return floatArrayOf(
                (r / 255.0).toFloat(),
                (g / 255.0).toFloat(),
                (b / 255.0).toFloat()
            )
        }
    }
}

data class VisualPreset(
    val id: String,
    val name: String,
    val tagline: String,
    val description: String,
    val highlights: List<String>,
    val adjustments: VisualAdjustments,
    val isBuiltIn: Boolean = true,
    val isFavorite: Boolean = false,
    val showHdrDisclaimer: Boolean = false,
    val recommendedColorMode: TargetWindowColorMode = TargetWindowColorMode.DEFAULT,
    val accentHex: String = "#00F0FF",
    val createdAtMs: Long = 0L
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("tagline", tagline)
        obj.put("description", description)
        val hlArr = JSONArray()
        highlights.forEach { hlArr.put(it) }
        obj.put("highlights", hlArr)
        obj.put("adjustments", adjustments.toJson())
        obj.put("isBuiltIn", isBuiltIn)
        obj.put("isFavorite", isFavorite)
        obj.put("showHdrDisclaimer", showHdrDisclaimer)
        obj.put("recommendedColorMode", recommendedColorMode.id)
        obj.put("accentHex", accentHex)
        obj.put("createdAtMs", createdAtMs)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): VisualPreset? {
            val id = obj.optString("id").trim()
            val name = obj.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) return null
            val hlArr = obj.optJSONArray("highlights")
            val highlights = mutableListOf<String>()
            if (hlArr != null) {
                for (i in 0 until hlArr.length()) {
                    val item = hlArr.optString(i).trim()
                    if (item.isNotEmpty()) highlights.add(item)
                }
            }
            val colorModeId = obj.optString("recommendedColorMode", TargetWindowColorMode.DEFAULT.id)
            val colorMode = TargetWindowColorMode.entries.find { it.id == colorModeId }
                ?: TargetWindowColorMode.DEFAULT

            return VisualPreset(
                id = id,
                name = name,
                tagline = obj.optString("tagline", "Custom User Preset"),
                description = obj.optString("description", "Custom user-tuned visual configuration."),
                highlights = if (highlights.isEmpty()) listOf("Custom Parameters", "User Tuned") else highlights,
                adjustments = VisualAdjustments.fromJson(obj.optJSONObject("adjustments")),
                isBuiltIn = obj.optBoolean("isBuiltIn", false),
                isFavorite = obj.optBoolean("isFavorite", false),
                showHdrDisclaimer = obj.optBoolean("showHdrDisclaimer", false),
                recommendedColorMode = colorMode,
                accentHex = obj.optString("accentHex", "#00F0FF"),
                createdAtMs = obj.optLong("createdAtMs", System.currentTimeMillis())
            )
        }

        val BUILT_IN_PRESETS: List<VisualPreset> = listOf(
            VisualPreset(
                id = "preset_competitive",
                name = "Competitive Mode",
                tagline = "ESPORTS CLARITY // SHADOW LIFT",
                description = "Natural colors, moderate contrast, controlled saturation, lifted shadow gamma, and crisp edge definition with minimal unnecessary processing.",
                highlights = listOf(
                    "Natural colors",
                    "Moderate contrast (108%)",
                    "Controlled saturation (104%)",
                    "Clear visual presentation & shadow gamma lift (1.12)",
                    "Minimal unnecessary processing"
                ),
                adjustments = VisualAdjustments(
                    saturationPercent = 104f,
                    contrastPercent = 108f,
                    brightnessPercent = 104f,
                    colorTempKelvin = 6500f,
                    sharpness = 42f,
                    gamma = 1.12f,
                    redChannelPercent = 100f,
                    greenChannelPercent = 101f,
                    blueChannelPercent = 100f,
                    lutProfile = LutGradingProfile.COMPETITIVE_CLARITY,
                    lutIntensityPercent = 55f
                ),
                isBuiltIn = true,
                recommendedColorMode = TargetWindowColorMode.DEFAULT,
                accentHex = "#00F0FF"
            ),
            VisualPreset(
                id = "preset_ultra_vibrant",
                name = "Ultra Vibrant",
                tagline = "MAX CHROMA // PUNCHY SATURATION",
                description = "Increased saturation, enhanced vibrance, stronger contrast, and vivid primary color presentation for lush open-world and arcade titles.",
                highlights = listOf(
                    "Increased saturation (148%)",
                    "Increased vibrance via Hyper-Vibrance LUT",
                    "Stronger contrast (118%)",
                    "Enhanced color presentation"
                ),
                adjustments = VisualAdjustments(
                    saturationPercent = 148f,
                    contrastPercent = 118f,
                    brightnessPercent = 103f,
                    colorTempKelvin = 6650f,
                    sharpness = 35f,
                    gamma = 0.96f,
                    redChannelPercent = 106f,
                    greenChannelPercent = 104f,
                    blueChannelPercent = 108f,
                    lutProfile = LutGradingProfile.ULTRA_VIBRANT,
                    lutIntensityPercent = 85f
                ),
                isBuiltIn = true,
                recommendedColorMode = TargetWindowColorMode.WIDE_COLOR_GAMUT,
                accentHex = "#FF1770"
            ),
            VisualPreset(
                id = "preset_cinematic",
                name = "Cinematic Mode",
                tagline = "TEAL & GOLD // FILM GRADING",
                description = "Cinematic color grading with warm highlights, cooler cyan-tinted shadows, balanced contrast, and a movie-inspired visual appearance.",
                highlights = listOf(
                    "Cinematic color grading (Teal & Gold LUT)",
                    "Warm highlights & cooler shadows",
                    "Balanced contrast (112%)",
                    "Movie-inspired visual appearance"
                ),
                adjustments = VisualAdjustments(
                    saturationPercent = 110f,
                    contrastPercent = 112f,
                    brightnessPercent = 99f,
                    colorTempKelvin = 6100f,
                    sharpness = 20f,
                    gamma = 0.94f,
                    redChannelPercent = 104f,
                    greenChannelPercent = 100f,
                    blueChannelPercent = 97f,
                    lutProfile = LutGradingProfile.CINEMATIC_TEAL_ORANGE,
                    lutIntensityPercent = 90f
                ),
                isBuiltIn = true,
                recommendedColorMode = TargetWindowColorMode.WIDE_COLOR_GAMUT,
                accentHex = "#FFBC00"
            ),
            VisualPreset(
                id = "preset_amoled_boost",
                name = "AMOLED Boost",
                tagline = "TRUE BLACK // DEEP CONTRAST",
                description = "Darker visual presentation with deep obsidian shadow floor, improved perceived contrast, wide-gamut display configuration, and reduced excess glare.",
                highlights = listOf(
                    "Darker visual presentation (92% brightness)",
                    "Improved perceived contrast (126%)",
                    "Suitable display color configuration (Wide Gamut)",
                    "Reduced unnecessary brightness where supported"
                ),
                adjustments = VisualAdjustments(
                    saturationPercent = 128f,
                    contrastPercent = 126f,
                    brightnessPercent = 92f,
                    colorTempKelvin = 6500f,
                    sharpness = 30f,
                    gamma = 0.86f,
                    redChannelPercent = 103f,
                    greenChannelPercent = 102f,
                    blueChannelPercent = 105f,
                    lutProfile = LutGradingProfile.AMOLED_DEEP_BLACK,
                    lutIntensityPercent = 85f
                ),
                isBuiltIn = true,
                recommendedColorMode = TargetWindowColorMode.WIDE_COLOR_GAMUT,
                accentHex = "#E040FB"
            ),
            VisualPreset(
                id = "preset_natural",
                name = "Natural Mode",
                tagline = "D65 REFERENCE // UNALTERED",
                description = "Balanced 100% saturation, neutral 6500K color temperature, natural contrast, and minimal color alteration for creator-intended fidelity.",
                highlights = listOf(
                    "Balanced saturation (100%)",
                    "Neutral color temperature (6500K)",
                    "Natural contrast (100%)",
                    "Minimal color alteration"
                ),
                adjustments = VisualAdjustments(
                    saturationPercent = 100f,
                    contrastPercent = 100f,
                    brightnessPercent = 100f,
                    colorTempKelvin = 6500f,
                    sharpness = 10f,
                    gamma = 1.0f,
                    redChannelPercent = 100f,
                    greenChannelPercent = 100f,
                    blueChannelPercent = 100f,
                    lutProfile = LutGradingProfile.NONE,
                    lutIntensityPercent = 0f
                ),
                isBuiltIn = true,
                recommendedColorMode = TargetWindowColorMode.DEFAULT,
                accentHex = "#00F57A"
            ),
            VisualPreset(
                id = "preset_hdr_inspired",
                name = "HDR Inspired",
                tagline = "TONEMAP S-CURVE // MICRO-DETAIL",
                description = "Enhanced perceived contrast, precision color tuning, and highlight/shadow S-curve adjustments. Note: HDR-inspired processing does not enable hardware HDR or create true HDR output on unsupported displays.",
                highlights = listOf(
                    "Enhanced perceived contrast (124%)",
                    "Precision color tuning & micro-sharpness (48)",
                    "Highlight and shadow S-curve adjustments",
                    "HDR-inspired appearance (software tone-mapping)"
                ),
                adjustments = VisualAdjustments(
                    saturationPercent = 122f,
                    contrastPercent = 124f,
                    brightnessPercent = 105f,
                    colorTempKelvin = 6600f,
                    sharpness = 48f,
                    gamma = 1.06f,
                    redChannelPercent = 104f,
                    greenChannelPercent = 103f,
                    blueChannelPercent = 104f,
                    lutProfile = LutGradingProfile.HDR_INSPIRED_TONEMAP,
                    lutIntensityPercent = 92f
                ),
                isBuiltIn = true,
                showHdrDisclaimer = true,
                recommendedColorMode = TargetWindowColorMode.HDR,
                accentHex = "#FF5722"
            ),
            VisualPreset(
                id = "preset_night_gaming",
                name = "Night Gaming",
                tagline = "LOW BLUE LIGHT // 4100K WARM",
                description = "Warm 4100K color temperature, attenuated blue-channel emission, and softened brightness for comfortable late-night gaming sessions.",
                highlights = listOf(
                    "Warm color temperature (4100K)",
                    "Reduced blue-light appearance (Blue 72%)",
                    "Comfortable nighttime viewing (Brightness 86%)"
                ),
                adjustments = VisualAdjustments(
                    saturationPercent = 92f,
                    contrastPercent = 94f,
                    brightnessPercent = 86f,
                    colorTempKelvin = 4100f,
                    sharpness = 15f,
                    gamma = 1.04f,
                    redChannelPercent = 106f,
                    greenChannelPercent = 94f,
                    blueChannelPercent = 72f,
                    lutProfile = LutGradingProfile.NIGHT_WARM_SHIELD,
                    lutIntensityPercent = 80f
                ),
                isBuiltIn = true,
                recommendedColorMode = TargetWindowColorMode.DEFAULT,
                accentHex = "#FF9100"
            ),
            VisualPreset(
                id = "preset_retro_gaming",
                name = "Retro Gaming",
                tagline = "VINTAGE PHOSPHOR // 80S CRT",
                description = "Retro arcade color grading, warm vintage sepia/amber tone balance, optional grayscale toggle, and classic CRT-inspired appearance.",
                highlights = listOf(
                    "Retro color grading (Vintage Arcade LUT)",
                    "Optional grayscale & sepia filter support",
                    "Vintage-inspired appearance"
                ),
                adjustments = VisualAdjustments(
                    saturationPercent = 82f,
                    contrastPercent = 114f,
                    brightnessPercent = 96f,
                    colorTempKelvin = 5200f,
                    sharpness = 55f,
                    gamma = 0.92f,
                    redChannelPercent = 108f,
                    greenChannelPercent = 98f,
                    blueChannelPercent = 82f,
                    enableSepia = false,
                    enableGrayscale = false,
                    lutProfile = LutGradingProfile.RETRO_ARCADE_CRT,
                    lutIntensityPercent = 95f
                ),
                isBuiltIn = true,
                recommendedColorMode = TargetWindowColorMode.DEFAULT,
                accentHex = "#1DE9B6"
            )
        )
    }
}

data class DisplayModeOption(
    val modeId: Int,
    val widthPx: Int,
    val heightPx: Int,
    val refreshRateHz: Float
) {
    val label: String
        get() = String.format(Locale.US, "%dx%d @ %.0f Hz", widthPx, heightPx, refreshRateHz)
}

data class SystemDisplayTelemetry(
    val widthPx: Int = 1080,
    val heightPx: Int = 2400,
    val densityDpi: Int = 440,
    val currentRefreshRateHz: Float = 60f,
    val activeModeId: Int = 0,
    val supportedModes: List<DisplayModeOption> = emptyList(),
    val isWideColorGamutSupported: Boolean = false,
    val isHdrSupported: Boolean = false,
    val supportedHdrTypes: List<String> = emptyList(),
    val maxLuminanceNits: Float = 0f,
    val currentWindowColorMode: TargetWindowColorMode = TargetWindowColorMode.DEFAULT,
    val systemBrightnessPercent: Int = 75,
    val canWriteSystemSettings: Boolean = false,
    val hasUsageStatsPermission: Boolean = false,
    val isRootAvailable: Boolean = false,
    val surfaceFlingerColorApplied: Boolean = false,
    val lastDisplayOperationStatus: String = "Ready — Window & preview pipeline initialized"
) {
    val resolutionLabel: String
        get() = "${widthPx} × ${heightPx} px (${densityDpi} dpi)"

    val refreshRateLabel: String
        get() = String.format(Locale.US, "%.0f Hz", currentRefreshRateHz)
}

data class InstalledGameItem(
    val packageName: String,
    val appName: String,
    val isCategorizedGame: Boolean
)

data class GameVisualProfile(
    val packageName: String,
    val appName: String,
    val assignedPresetId: String,
    val assignedPresetName: String,
    val useCustomOverrides: Boolean = false,
    val customAdjustments: VisualAdjustments = VisualAdjustments.DEFAULT,
    val preferredRefreshRateHz: Float = 0f, // 0 = system default
    val preferredBrightnessPercent: Int = -1, // -1 = unchanged
    val preferredColorMode: TargetWindowColorMode = TargetWindowColorMode.DEFAULT,
    val autoApplyWhenDetected: Boolean = true,
    val lastAppliedTimestampMs: Long = 0L
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("packageName", packageName)
        obj.put("appName", appName)
        obj.put("assignedPresetId", assignedPresetId)
        obj.put("assignedPresetName", assignedPresetName)
        obj.put("useCustomOverrides", useCustomOverrides)
        obj.put("customAdjustments", customAdjustments.toJson())
        obj.put("preferredRefreshRateHz", preferredRefreshRateHz.toDouble())
        obj.put("preferredBrightnessPercent", preferredBrightnessPercent)
        obj.put("preferredColorMode", preferredColorMode.id)
        obj.put("autoApplyWhenDetected", autoApplyWhenDetected)
        obj.put("lastAppliedTimestampMs", lastAppliedTimestampMs)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): GameVisualProfile? {
            val pkg = obj.optString("packageName").trim()
            val name = obj.optString("appName").trim()
            if (pkg.isEmpty() || name.isEmpty()) return null
            val colorModeId = obj.optString("preferredColorMode", TargetWindowColorMode.DEFAULT.id)
            val colorMode = TargetWindowColorMode.entries.find { it.id == colorModeId }
                ?: TargetWindowColorMode.DEFAULT
            return GameVisualProfile(
                packageName = pkg,
                appName = name,
                assignedPresetId = obj.optString("assignedPresetId", "preset_competitive"),
                assignedPresetName = obj.optString("assignedPresetName", "Competitive Mode"),
                useCustomOverrides = obj.optBoolean("useCustomOverrides", false),
                customAdjustments = VisualAdjustments.fromJson(obj.optJSONObject("customAdjustments")),
                preferredRefreshRateHz = obj.optDouble("preferredRefreshRateHz", 0.0).toFloat(),
                preferredBrightnessPercent = obj.optInt("preferredBrightnessPercent", -1),
                preferredColorMode = colorMode,
                autoApplyWhenDetected = obj.optBoolean("autoApplyWhenDetected", true),
                lastAppliedTimestampMs = obj.optLong("lastAppliedTimestampMs", 0L)
            )
        }
    }
}

data class VisualPerformanceStats(
    val lastFrameRenderTimeMs: Float = 0.85f,
    val avgFrameRenderTimeMs: Float = 0.92f,
    val gpuRendererName: String = "OpenGL ES 2.0 GPU Surface",
    val gpuVendorName: String = "Hardware Accelerated",
    val glVersion: String = "OpenGL ES 2.0+",
    val shaderCompiledSuccessfully: Boolean = true,
    val shaderErrorLog: String? = null,
    val expensiveEffectsEnabled: Boolean = true,
    val textureWidth: Int = 960,
    val textureHeight: Int = 540
)
