package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.visualstyle.VisualPreset
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Mini Floating Visual Style Control Overlay Manager.
 *
 * - Displays a draggable floating icon with the exact Dragon Tool app logo design
 *   ([R.drawable.ic_launcher_background] + [R.drawable.ic_launcher_foreground]).
 * - Automatically opens when a selected game is in the foreground (or when active in-app / preview mode).
 * - Tapping the floating icon toggles a compact floating popup list of all Visual Styles
 *   (built-in + custom presets) synced in real time with [com.example.data.visualstyle.VisualStyleManager].
 * - Selecting any style from the popup list immediately applies and syncs that Visual Style.
 */
object VisualStyleMiniOverlayManager {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var rootWrapper: LinearLayout? = null
    private var iconContainerView: FrameLayout? = null
    private var iconBadgeDotView: View? = null
    private var popupCardView: LinearLayout? = null
    private var activePresetSubtitleView: TextView? = null
    private var presetListContainer: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var isShowing = false
    private var isPopupExpanded = false

    private var cachedPresets: List<VisualPreset> = emptyList()
    private var cachedActivePresetId: String = "preset_competitive"
    private var cachedActiveGameLabel: String = ""
    private var onSelectPresetCallback: ((VisualPreset) -> Unit)? = null

    fun isOverlayShowing(): Boolean = isShowing

    fun isPopupCurrentlyExpanded(): Boolean = isPopupExpanded

    fun syncStateAndVisibility(
        context: Context,
        shouldShow: Boolean,
        presets: List<VisualPreset>,
        activePresetId: String,
        activeGameLabel: String = "",
        onSelectPreset: (VisualPreset) -> Unit
    ) {
        val appContext = context.applicationContext
        cachedPresets = presets
        cachedActivePresetId = activePresetId
        cachedActiveGameLabel = activeGameLabel
        onSelectPresetCallback = onSelectPreset

        if (!shouldShow) {
            hide(appContext)
            return
        }

        if (!Settings.canDrawOverlays(appContext)) {
            hide(appContext)
            return
        }

        mainHandler.post {
            try {
                if (!isShowing || rootWrapper == null) {
                    createAndAttachOverlay(appContext)
                }
                bindPresetsAndActiveState(appContext)
            } catch (_: Exception) {
            }
        }
    }

    fun hide(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post {
            val wm = windowManager ?: (appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            val view = rootWrapper
            if (view != null && wm != null) {
                try {
                    wm.removeViewImmediate(view)
                } catch (_: Exception) {
                }
            }
            rootWrapper = null
            iconContainerView = null
            iconBadgeDotView = null
            popupCardView = null
            activePresetSubtitleView = null
            presetListContainer = null
            layoutParams = null
            isShowing = false
            isPopupExpanded = false
        }
    }

    @SuppressLint("ClickableViewAccessibility", "WrongConstant")
    private fun createAndAttachOverlay(appContext: Context) {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val density = appContext.resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).roundToInt().coerceAtLeast(1)

        val wrapper = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE
                } catch (_: Exception) {
                }
            }
        }

        // 1. Floating Dragon Tool App Logo Icon Button
        val iconSizePx = dp(52f)
        val iconFrame = FrameLayout(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(iconSizePx, iconSizePx)
            background = createFloatingLogoFrameDrawable(density, parseColorSafe(getActiveAccentHex(), "#00F0FF"))
            elevation = 16f * density
        }

        val innerLogoPadding = dp(3.5f)
        val logoImageView = ImageView(appContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                setMargins(innerLogoPadding, innerLogoPadding, innerLogoPadding, innerLogoPadding)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            val bgDrawable = ContextCompat.getDrawable(appContext, R.drawable.ic_launcher_background)?.mutate()
            val fgDrawable = ContextCompat.getDrawable(appContext, R.drawable.ic_launcher_foreground)?.mutate()
            if (bgDrawable != null && fgDrawable != null) {
                setImageDrawable(LayerDrawable(arrayOf(bgDrawable, fgDrawable)))
            } else if (fgDrawable != null) {
                setImageDrawable(fgDrawable)
            }
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(13f).toFloat())
                }
            }
        }

        // Active style status indicator dot on bottom-right corner of the Dragon logo
        val dotSize = dp(12f)
        val statusDot = View(appContext).apply {
            layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(2f), dp(2f))
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(parseColorSafe(getActiveAccentHex(), "#00F0FF"))
                setStroke(dp(1.5f), Color.parseColor("#0B0F17"))
            }
        }

        iconFrame.addView(logoImageView)
        iconFrame.addView(statusDot)

        // 2. Expandable Popup Visual Style List Container
        val popupCard = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(236f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f)
            }
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16f).toFloat()
                setColor(Color.parseColor("#F20C1018"))
                setStroke(dp(1.3f), Color.parseColor("#00F0FF"))
            }
            elevation = 18f * density
            visibility = if (isPopupExpanded) View.VISIBLE else View.GONE
        }

        // Popup Header Row
        val headerRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val headerTextCol = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val headerTitle = TextView(appContext).apply {
            text = "DRAGON VISUAL STYLE"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#00F0FF"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val headerSub = TextView(appContext).apply {
            text = "Active: Competitive Mode"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#CBD5E1"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        headerTextCol.addView(headerTitle)
        headerTextCol.addView(headerSub)

        val closePopupBtn = TextView(appContext).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(8f), dp(2f), dp(4f), dp(2f))
            setOnClickListener {
                isPopupExpanded = false
                popupCard.visibility = View.GONE
            }
        }

        headerRow.addView(headerTextCol)
        headerRow.addView(closePopupBtn)

        val divider = View(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1f)
            ).apply {
                topMargin = dp(6f)
                bottomMargin = dp(6f)
            }
            setBackgroundColor(Color.parseColor("#243044"))
        }

        val scrollView = ScrollView(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(230f)
            )
            isVerticalScrollBarEnabled = true
        }

        val listContainer = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        scrollView.addView(listContainer)
        popupCard.addView(headerRow)
        popupCard.addView(divider)
        popupCard.addView(scrollView)

        wrapper.addView(iconFrame)
        wrapper.addView(popupCard)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (16 * density).roundToInt()
            y = (180 * density).roundToInt()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                preferredDisplayModeId = 0
                preferredRefreshRate = 0f
            }
        }

        attachIconDragAndTapListener(appContext, iconFrame, wrapper, popupCard, params, wm, density)

        try {
            wm.addView(wrapper, params)
            rootWrapper = wrapper
            iconContainerView = iconFrame
            iconBadgeDotView = statusDot
            popupCardView = popupCard
            activePresetSubtitleView = headerSub
            presetListContainer = listContainer
            layoutParams = params
            isShowing = true
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachIconDragAndTapListener(
        appContext: Context,
        iconView: View,
        rootWrapper: View,
        popupView: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
        density: Float
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        iconView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).roundToInt()
                    val dy = (event.rawY - initialTouchY).roundToInt()
                    if (abs(dx) > (6 * density) || abs(dy) > (6 * density)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            wm.updateViewLayout(rootWrapper, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        isPopupExpanded = !isPopupExpanded
                        popupView.visibility = if (isPopupExpanded) View.VISIBLE else View.GONE
                        if (isPopupExpanded) {
                            bindPresetsAndActiveState(appContext)
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun bindPresetsAndActiveState(appContext: Context) {
        val density = appContext.resources.displayMetrics.density
        fun dp(value: Float): Int = (value * density).roundToInt().coerceAtLeast(1)

        val activePreset = cachedPresets.find { it.id == cachedActivePresetId }
            ?: cachedPresets.firstOrNull()
        val activeAccentColor = parseColorSafe(activePreset?.accentHex ?: "#00F0FF", "#00F0FF")

        // Update floating icon border & dot color to reflect active Visual Style
        iconContainerView?.background = createFloatingLogoFrameDrawable(density, activeAccentColor)
        iconBadgeDotView?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(activeAccentColor)
            setStroke(dp(1.5f), Color.parseColor("#0B0F17"))
        }

        popupCardView?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16f).toFloat()
            setColor(Color.parseColor("#F20C1018"))
            setStroke(dp(1.3f), activeAccentColor)
        }

        val gameSuffix = if (cachedActiveGameLabel.isNotBlank()) " • $cachedActiveGameLabel" else ""
        activePresetSubtitleView?.text = "Active: ${activePreset?.name ?: "Custom"}$gameSuffix"

        val listContainer = presetListContainer ?: return
        listContainer.removeAllViews()

        cachedPresets.forEach { preset ->
            val isSelected = preset.id == cachedActivePresetId
            val presetAccent = parseColorSafe(preset.accentHex, "#00F0FF")

            val itemRow = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(5f)
                }
                setPadding(dp(9f), dp(7f), dp(9f), dp(7f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10f).toFloat()
                    if (isSelected) {
                        setColor(Color.argb(48, Color.red(presetAccent), Color.green(presetAccent), Color.blue(presetAccent)))
                        setStroke(dp(1.3f), presetAccent)
                    } else {
                        setColor(Color.parseColor("#151C28"))
                        setStroke(dp(1f), Color.parseColor("#243044"))
                    }
                }
                setOnClickListener {
                    cachedActivePresetId = preset.id
                    onSelectPresetCallback?.invoke(preset)
                    bindPresetsAndActiveState(appContext)
                }
            }

            // Left accent bar
            val colorBar = View(appContext).apply {
                layoutParams = LinearLayout.LayoutParams(dp(4f), dp(26f)).apply {
                    rightMargin = dp(8f)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(4f).toFloat()
                    setColor(presetAccent)
                }
            }

            // Preset Name + Parameters
            val textCol = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(appContext).apply {
                text = preset.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#E2E8F0"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            val adj = preset.adjustments
            val tagPrefix = if (preset.isBuiltIn) "" else "CUSTOM • "
            val subTv = TextView(appContext).apply {
                text = "${tagPrefix}SAT ${adj.saturationPercent.roundToInt()}% • CONT ${adj.contrastPercent.roundToInt()}% • ${adj.colorTempKelvin.roundToInt()}K"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
                typeface = Typeface.MONOSPACE
                setTextColor(if (isSelected) presetAccent else Color.parseColor("#94A3B8"))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            textCol.addView(titleTv)
            textCol.addView(subTv)

            // Active status badge on the right
            val statusBadge = TextView(appContext).apply {
                text = if (isSelected) "✓ ACTIVE" else "APPLY"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setPadding(dp(6f), dp(2.5f), dp(6f), dp(2.5f))
                if (isSelected) {
                    setTextColor(Color.parseColor("#081018"))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(6f).toFloat()
                        setColor(presetAccent)
                    }
                } else {
                    setTextColor(Color.parseColor("#94A3B8"))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(6f).toFloat()
                        setColor(Color.parseColor("#1E293B"))
                    }
                }
            }

            itemRow.addView(colorBar)
            itemRow.addView(textCol)
            itemRow.addView(statusBadge)

            listContainer.addView(itemRow)
        }
    }

    private fun createFloatingLogoFrameDrawable(density: Float, accentColor: Int): GradientDrawable {
        val strokePx = (1.8f * density).roundToInt().coerceAtLeast(2)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 15f * density
            setColor(Color.parseColor("#12131A"))
            setStroke(strokePx, accentColor)
        }
    }

    private fun getActiveAccentHex(): String {
        val activePreset = cachedPresets.find { it.id == cachedActivePresetId }
        return activePreset?.accentHex ?: "#00F0FF"
    }

    private fun parseColorSafe(hex: String, fallbackHex: String): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: Exception) {
            Color.parseColor(fallbackHex)
        }
    }
}
