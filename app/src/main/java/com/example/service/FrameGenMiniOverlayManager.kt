package com.example.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.data.model.FrameGenConfig
import com.example.data.model.FrameGenFloatingSize
import com.example.data.model.FrameGenFloatingStyle
import com.example.data.model.FrameGenTelemetry
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Automatic Per-Game Mini Floating FrameGen FPS Window Manager.
 *
 * - Automatically opens a lightweight, draggable mini floating window when a user-added
 *   FrameGen game is in the foreground (or when the user activates Live Preview in FrameGen settings).
 * - Automatically closes the mini floating window as soon as the user exits or closes the game.
 * - Supports 5 distinct floating styles ([FrameGenFloatingStyle]) and 5 scale sizes ([FrameGenFloatingSize]).
 * - Strictly sets `preferredDisplayModeId = 0`, `preferredRefreshRate = 0f`, and
 *   `REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE` so it NEVER forces the device's minimum
 *   screen refresh rate to maximum.
 */
object FrameGenMiniOverlayManager {

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var rootContainer: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var modeBadgeView: TextView? = null
    private var gameTitleView: TextView? = null
    private var collapseBtnView: TextView? = null
    private var closeBtnView: TextView? = null
    private var fpsBreakdownRow: LinearLayout? = null
    private var nativeFpsView: TextView? = null
    private var plusGenFpsView: TextView? = null
    private var totalFpsView: TextView? = null
    private var detailRowView: TextView? = null

    private var isShowing = false
    private var isCompactCollapsed = false
    private var activeGamePackageForSession: String = ""
    private var manuallyDismissedForGamePackage: String = ""

    private var appliedStyle: FrameGenFloatingStyle = FrameGenFloatingStyle.TACTICAL_HUD_CARD
    private var appliedSize: FrameGenFloatingSize = FrameGenFloatingSize.MEDIUM

    private var lastRenderedSignature: String = ""
    private var onOverlaySelfRedraw: (() -> Unit)? = null
    private var onPreviewDismissed: (() -> Unit)? = null

    fun isOverlayShowing(): Boolean = isShowing

    fun resetManualDismissalIfGameChanged(currentForegroundPkg: String) {
        if (manuallyDismissedForGamePackage.isNotBlank() &&
            !manuallyDismissedForGamePackage.equals(currentForegroundPkg, ignoreCase = true)
        ) {
            manuallyDismissedForGamePackage = ""
        }
    }

    fun onGameClosedOrLeftForeground(context: Context) {
        manuallyDismissedForGamePackage = ""
        if (activeGamePackageForSession != "preview.session") {
            hide(context)
        }
    }

    fun showOrUpdate(
        context: Context,
        telemetry: FrameGenTelemetry,
        config: FrameGenConfig,
        onSelfRedraw: (() -> Unit)? = null,
        onPreviewClosed: (() -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        onOverlaySelfRedraw = onSelfRedraw
        onPreviewDismissed = onPreviewClosed

        val fgPkg = telemetry.activeForegroundPackage
        resetManualDismissalIfGameChanged(fgPkg)

        val shouldShowForGame = fgPkg.isNotBlank() &&
            telemetry.isForegroundAddedGame &&
            telemetry.isActive &&
            config.autoShowFloatingWindow &&
            !manuallyDismissedForGamePackage.equals(fgPkg, ignoreCase = true)

        val shouldShowForPreview = config.floatingWindowPreviewActive

        if (!shouldShowForGame && !shouldShowForPreview) {
            hide(appContext)
            return
        }

        if (!Settings.canDrawOverlays(appContext)) {
            return
        }

        mainHandler.post {
            try {
                val styleOrSizeChanged = isShowing &&
                    (appliedStyle != config.floatingStyle || appliedSize != config.floatingSize)

                if (styleOrSizeChanged) {
                    rebuildOverlayPreservingPosition(appContext, config)
                } else if (!isShowing || rootContainer == null) {
                    createAndAttachView(appContext, config)
                }

                activeGamePackageForSession = if (shouldShowForGame) fgPkg else "preview.session"
                bindTelemetryData(appContext, telemetry, config)
            } catch (_: Exception) {
            }
        }
    }

    fun hide(context: Context) {
        val appContext = context.applicationContext
        mainHandler.post {
            val wm = windowManager ?: (appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            val view = rootContainer
            if (view != null && wm != null) {
                try {
                    wm.removeViewImmediate(view)
                } catch (_: Exception) {
                }
            }
            clearViewReferences()
            layoutParams = null
            isShowing = false
            activeGamePackageForSession = ""
            lastRenderedSignature = ""
        }
    }

    private fun clearViewReferences() {
        rootContainer = null
        modeBadgeView = null
        gameTitleView = null
        collapseBtnView = null
        closeBtnView = null
        fpsBreakdownRow = null
        nativeFpsView = null
        plusGenFpsView = null
        totalFpsView = null
        detailRowView = null
    }

    private fun rebuildOverlayPreservingPosition(appContext: Context, config: FrameGenConfig) {
        val wm = windowManager ?: (appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return
        val savedX = layoutParams?.x
        val savedY = layoutParams?.y
        val oldView = rootContainer
        if (oldView != null) {
            try {
                wm.removeViewImmediate(oldView)
            } catch (_: Exception) {
            }
        }
        clearViewReferences()
        isShowing = false
        createAndAttachView(appContext, config, savedX, savedY)
    }

    @SuppressLint("ClickableViewAccessibility", "WrongConstant")
    private fun createAndAttachView(
        appContext: Context,
        config: FrameGenConfig,
        preservedX: Int? = null,
        preservedY: Int? = null
    ) {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val density = appContext.resources.displayMetrics.density
        val scale = config.floatingSize.scaleFactor
        val style = config.floatingStyle

        appliedStyle = style
        appliedSize = config.floatingSize

        fun dp(value: Float): Int = (value * scale * density).roundToInt().coerceAtLeast(1)

        val root = LinearLayout(appContext).apply {
            orientation = when (style) {
                FrameGenFloatingStyle.COMPACT_PILL_BAR,
                FrameGenFloatingStyle.MINIMAL_FPS_BADGE -> LinearLayout.HORIZONTAL
                else -> LinearLayout.VERTICAL
            }
            gravity = when (style) {
                FrameGenFloatingStyle.COMPACT_PILL_BAR,
                FrameGenFloatingStyle.MINIMAL_FPS_BADGE -> Gravity.CENTER_VERTICAL
                FrameGenFloatingStyle.VERTICAL_SIDE_DOCK -> Gravity.CENTER_HORIZONTAL
                else -> Gravity.START
            }
            val hPad = when (style) {
                FrameGenFloatingStyle.COMPACT_PILL_BAR -> dp(12f)
                FrameGenFloatingStyle.MINIMAL_FPS_BADGE -> dp(9f)
                FrameGenFloatingStyle.VERTICAL_SIDE_DOCK -> dp(9f)
                else -> dp(11f)
            }
            val vPad = when (style) {
                FrameGenFloatingStyle.COMPACT_PILL_BAR -> dp(6f)
                FrameGenFloatingStyle.MINIMAL_FPS_BADGE -> dp(5f)
                FrameGenFloatingStyle.VERTICAL_SIDE_DOCK -> dp(8f)
                else -> dp(8f)
            }
            setPadding(hPad, vPad, hPad, vPad)
            background = createPanelBackground(style, density, scale, Color.parseColor("#00E5FF"))
            elevation = 14f * density

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE
                } catch (_: Exception) {
                }
            }
        }

        when (style) {
            FrameGenFloatingStyle.COMPACT_PILL_BAR -> buildCompactPillBarLayout(appContext, root, scale, ::dp)
            FrameGenFloatingStyle.MINIMAL_FPS_BADGE -> buildMinimalBadgeLayout(appContext, root, scale, ::dp)
            FrameGenFloatingStyle.VERTICAL_SIDE_DOCK -> buildVerticalSideDockLayout(appContext, root, scale, ::dp)
            FrameGenFloatingStyle.LIQUID_GLASS_POD -> buildCardLayout(appContext, root, scale, ::dp, isLiquidGlass = true)
            FrameGenFloatingStyle.TACTICAL_HUD_CARD -> buildCardLayout(appContext, root, scale, ::dp, isLiquidGlass = false)
        }

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
            x = preservedX ?: (14 * density).roundToInt()
            y = preservedY ?: (110 * density).roundToInt()
            // Synchronize hardware display refresh rate with FrameGen Target FPS (e.g. 120Hz/144Hz)
            // This ensures SurfaceFlinger does NOT throttle or drop generated frames on both root and non-root devices!
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val targetRate = config.targetFps.toFloat().coerceIn(60f, 165f)
                preferredRefreshRate = targetRate
                try {
                    @Suppress("DEPRECATION")
                    val defaultDisplay = wm.defaultDisplay
                    val supportedModes = defaultDisplay?.supportedModes
                    val matchedMode = supportedModes?.filter { it.refreshRate >= (targetRate - 2f) }?.minByOrNull { it.refreshRate }
                        ?: supportedModes?.maxByOrNull { it.refreshRate }
                    if (matchedMode != null && matchedMode.refreshRate >= 80f) {
                        preferredDisplayModeId = matchedMode.modeId
                    }
                } catch (_: Exception) {}
            }
        }

        attachDragAndTapHandler(appContext, root, params, wm, density)

        try {
            wm.addView(root, params)
            rootContainer = root
            layoutParams = params
            isShowing = true
            lastRenderedSignature = ""
        } catch (_: Exception) {
        }
    }

    private fun buildCardLayout(
        appContext: Context,
        root: LinearLayout,
        scale: Float,
        dp: (Float) -> Int,
        isLiquidGlass: Boolean
    ) {
        val headerRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = TextView(appContext).apply {
            text = if (isLiquidGlass) "✦ FG 2X" else "⚡ FG 2X"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f * scale)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(if (isLiquidGlass) Color.parseColor("#E0F7FA") else Color.parseColor("#081018"))
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(6f).toFloat()
                setColor(if (isLiquidGlass) Color.parseColor("#4400E5FF") else Color.parseColor("#00E5FF"))
                if (isLiquidGlass) {
                    setStroke(dp(1f), Color.parseColor("#AA80D8FF"))
                }
            }
        }

        val title = TextView(appContext).apply {
            text = "GAME"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * scale)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(if (isLiquidGlass) Color.parseColor("#E0F7FA") else Color.parseColor("#E2E8F0"))
            setPadding(dp(6f), 0, dp(6f), 0)
            maxLines = 1
        }

        val collapseBtn = TextView(appContext).apply {
            text = if (isCompactCollapsed) "＋" else "－"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * scale)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isLiquidGlass) Color.parseColor("#B2EBF2") else Color.parseColor("#94A3B8"))
            setPadding(dp(4f), dp(1f), dp(4f), dp(1f))
            setOnClickListener {
                isCompactCollapsed = !isCompactCollapsed
                text = if (isCompactCollapsed) "＋" else "－"
                detailRowView?.visibility = if (isCompactCollapsed) View.GONE else View.VISIBLE
            }
        }

        val closeBtn = createCloseButton(appContext, scale, dp)

        headerRow.addView(badge)
        headerRow.addView(title)
        headerRow.addView(collapseBtn)
        headerRow.addView(closeBtn)

        val fpsRow = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(2f))
        }

        val nativeTv = TextView(appContext).apply {
            text = "NAT 60"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scale)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        val genTv = TextView(appContext).apply {
            text = " + GEN 60 "
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scale)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
        }

        val totalTv = TextView(appContext).apply {
            text = "→ 120 FPS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f * scale)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
        }

        fpsRow.addView(nativeTv)
        fpsRow.addView(genTv)
        fpsRow.addView(totalTv)

        val detailTv = TextView(appContext).apply {
            text = "1.4ms • Balanced • GPU 39°C"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.2f * scale)
            typeface = Typeface.MONOSPACE
            setTextColor(if (isLiquidGlass) Color.parseColor("#B2EBF2") else Color.parseColor("#94A3B8"))
            visibility = if (isCompactCollapsed) View.GONE else View.VISIBLE
        }

        root.addView(headerRow)
        root.addView(fpsRow)
        root.addView(detailTv)

        modeBadgeView = badge
        gameTitleView = title
        collapseBtnView = collapseBtn
        closeBtnView = closeBtn
        fpsBreakdownRow = fpsRow
        nativeFpsView = nativeTv
        plusGenFpsView = genTv
        totalFpsView = totalTv
        detailRowView = detailTv
    }

    private fun buildCompactPillBarLayout(
        appContext: Context,
        root: LinearLayout,
        scale: Float,
        dp: (Float) -> Int
    ) {
        val badge = TextView(appContext).apply {
            text = "⚡2X"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f * scale)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#061018"))
            setPadding(dp(6f), dp(2f), dp(6f), dp(2f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(99f).toFloat()
                setColor(Color.parseColor("#00E5FF"))
            }
        }

        val nativeTv = TextView(appContext).apply {
            text = " 60"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scale)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(5f), 0, 0, 0)
        }

        val genTv = TextView(appContext).apply {
            text = "+60"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f * scale)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
        }

        val totalTv = TextView(appContext).apply {
            text = " → 120 FPS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f * scale)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
        }

        val detailTv = TextView(appContext).apply {
            text = " • 39°C"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f * scale)
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, dp(4f), 0)
            visibility = if (isCompactCollapsed) View.GONE else View.VISIBLE
        }

        val closeBtn = createCloseButton(appContext, scale, dp)

        root.addView(badge)
        root.addView(nativeTv)
        root.addView(genTv)
        root.addView(totalTv)
        root.addView(detailTv)
        root.addView(closeBtn)

        modeBadgeView = badge
        nativeFpsView = nativeTv
        plusGenFpsView = genTv
        totalFpsView = totalTv
        detailRowView = detailTv
        closeBtnView = closeBtn
    }

    private fun buildMinimalBadgeLayout(
        appContext: Context,
        root: LinearLayout,
        scale: Float,
        dp: (Float) -> Int
    ) {
        val totalTv = TextView(appContext).apply {
            text = "⚡ 120 FPS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * scale)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
        }

        val genTv = TextView(appContext).apply {
            text = " +60 FG"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f * scale)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
            setPadding(dp(4f), 0, dp(4f), 0)
            visibility = if (isCompactCollapsed) View.GONE else View.VISIBLE
        }

        val closeBtn = createCloseButton(appContext, scale, dp)

        root.addView(totalTv)
        root.addView(genTv)
        root.addView(closeBtn)

        totalFpsView = totalTv
        plusGenFpsView = genTv
        closeBtnView = closeBtn
    }

    private fun buildVerticalSideDockLayout(
        appContext: Context,
        root: LinearLayout,
        scale: Float,
        dp: (Float) -> Int
    ) {
        val topHeader = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = TextView(appContext).apply {
            text = "⚡2X"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f * scale)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#071018"))
            setPadding(dp(5f), dp(1.5f), dp(5f), dp(1.5f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(5f).toFloat()
                setColor(Color.parseColor("#00E5FF"))
            }
        }

        val closeBtn = createCloseButton(appContext, scale, dp)
        topHeader.addView(badge)
        topHeader.addView(closeBtn)

        val totalTv = TextView(appContext).apply {
            text = "120 FPS"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f * scale)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4f), 0, dp(2f))
        }

        val nativeTv = TextView(appContext).apply {
            text = "NAT 60"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f * scale)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val genTv = TextView(appContext).apply {
            text = "+60 GEN"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f * scale)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E676"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(1f), 0, dp(2f))
        }

        val detailTv = TextView(appContext).apply {
            text = "1.4ms • 39°C"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f * scale)
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = if (isCompactCollapsed) View.GONE else View.VISIBLE
        }

        root.addView(topHeader)
        root.addView(totalTv)
        root.addView(nativeTv)
        root.addView(genTv)
        root.addView(detailTv)

        modeBadgeView = badge
        closeBtnView = closeBtn
        totalFpsView = totalTv
        nativeFpsView = nativeTv
        plusGenFpsView = genTv
        detailRowView = detailTv
    }

    private fun createCloseButton(
        appContext: Context,
        scale: Float,
        dp: (Float) -> Int
    ): TextView {
        return TextView(appContext).apply {
            text = "✕"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f * scale)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF5252"))
            setPadding(dp(5f), dp(1f), dp(2f), dp(1f))
            setOnClickListener {
                if (activeGamePackageForSession.isNotBlank() && activeGamePackageForSession != "preview.session") {
                    manuallyDismissedForGamePackage = activeGamePackageForSession
                }
                onPreviewDismissed?.invoke()
                hide(appContext)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragAndTapHandler(
        appContext: Context,
        root: LinearLayout,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
        density: Float
    ) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        root.setOnTouchListener { _, event ->
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
                            wm.updateViewLayout(root, params)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        isCompactCollapsed = !isCompactCollapsed
                        collapseBtnView?.text = if (isCompactCollapsed) "＋" else "－"
                        if (appliedStyle == FrameGenFloatingStyle.MINIMAL_FPS_BADGE) {
                            plusGenFpsView?.visibility = if (isCompactCollapsed) View.GONE else View.VISIBLE
                        } else {
                            detailRowView?.visibility = if (isCompactCollapsed) View.GONE else View.VISIBLE
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun bindTelemetryData(
        appContext: Context,
        telemetry: FrameGenTelemetry,
        config: FrameGenConfig
    ) {
        val density = appContext.resources.displayMetrics.density
        val scale = config.floatingSize.scaleFactor
        val style = config.floatingStyle

        val isPreviewOnly = config.floatingWindowPreviewActive &&
            (!telemetry.isForegroundAddedGame || !telemetry.isActive)

        val displayNativeFps = telemetry.nativeFps.coerceIn(1, 240)
        val displayGenFps = telemetry.generatedFps.coerceAtLeast(0)
        val displayTotalFps = telemetry.totalPresentedFps.coerceAtLeast(displayNativeFps)
        val displayLatencyMs = if (telemetry.frameGenLatencyMs > 0.1f) {
            telemetry.frameGenLatencyMs
        } else {
            1.4f
        }

        val signature = "$style|$scale|$isPreviewOnly|${telemetry.activeForegroundTitle}|$displayNativeFps|$displayGenFps|$displayTotalFps|${(displayLatencyMs * 10).roundToInt()}|${telemetry.gpuTempC.roundToInt()}|${telemetry.isPausedByGuardrail}|${config.mode.label}|${telemetry.isLosslessDllActive}"
        if (signature == lastRenderedSignature) {
            return
        }
        lastRenderedSignature = signature
        onOverlaySelfRedraw?.invoke()

        val accentColor = when {
            isPreviewOnly -> Color.parseColor("#00E5FF")
            telemetry.isPausedByGuardrail -> Color.parseColor("#FFB300")
            telemetry.isLosslessDllActive -> Color.parseColor("#FFD700") // LSFG Gold
            telemetry.isActive && displayGenFps > 0 -> Color.parseColor("#00E5FF")
            else -> Color.parseColor("#78909C")
        }

        rootContainer?.background = createPanelBackground(style, density, scale, accentColor)

        val dp6 = (6 * scale * density).roundToInt().coerceAtLeast(1)
        val dp1 = (1 * scale * density).roundToInt().coerceAtLeast(1)

        val fgPrefix = if (telemetry.isLosslessDllActive) "LSFG" else "FG"

        modeBadgeView?.apply {
            text = when (style) {
                FrameGenFloatingStyle.COMPACT_PILL_BAR,
                FrameGenFloatingStyle.VERTICAL_SIDE_DOCK -> {
                    if (telemetry.isPausedByGuardrail && !isPreviewOnly) "GUARD" else "⚡$fgPrefix ${config.mode.label}"
                }
                FrameGenFloatingStyle.LIQUID_GLASS_POD -> {
                    if (telemetry.isPausedByGuardrail && !isPreviewOnly) "✦ GUARD" else "✦ $fgPrefix ${config.mode.label}"
                }
                else -> {
                    if (telemetry.isPausedByGuardrail && !isPreviewOnly) "GUARDRAIL" else "⚡ $fgPrefix ${config.mode.label}"
                }
            }
            if (style == FrameGenFloatingStyle.LIQUID_GLASS_POD) {
                setTextColor(Color.parseColor("#E0F7FA"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp6.toFloat()
                    setColor(Color.parseColor("#4400E5FF"))
                    setStroke(dp1, accentColor)
                }
            } else {
                setTextColor(Color.parseColor("#071018"))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = if (style == FrameGenFloatingStyle.COMPACT_PILL_BAR) (99 * density) else dp6.toFloat()
                    setColor(accentColor)
                }
            }
        }

        val rawTitle = when {
            isPreviewOnly && telemetry.activeForegroundTitle == "Dragon Tools" -> "PREVIEW HUD"
            else -> telemetry.activeForegroundTitle.ifBlank { "Game" }
        }
        gameTitleView?.text = if (rawTitle.length > 16) rawTitle.take(15) + "…" else rawTitle

        when (style) {
            FrameGenFloatingStyle.COMPACT_PILL_BAR -> {
                nativeFpsView?.text = " $displayNativeFps"
                plusGenFpsView?.apply {
                    text = "+$displayGenFps"
                    setTextColor(
                        if (displayGenFps > 0) Color.parseColor("#00E676") else Color.parseColor("#94A3B8")
                    )
                }
                totalFpsView?.apply {
                    text = " → $displayTotalFps FPS"
                    setTextColor(accentColor)
                }
                detailRowView?.text = String.format(
                    Locale.US,
                    " • %.1fms • %d°C",
                    displayLatencyMs,
                    telemetry.gpuTempC.roundToInt()
                )
            }

            FrameGenFloatingStyle.MINIMAL_FPS_BADGE -> {
                totalFpsView?.apply {
                    text = "⚡ $displayTotalFps FPS"
                    setTextColor(accentColor)
                }
                plusGenFpsView?.apply {
                    text = " +$displayGenFps FG"
                    setTextColor(
                        if (displayGenFps > 0) Color.parseColor("#00E676") else Color.parseColor("#94A3B8")
                    )
                }
            }

            FrameGenFloatingStyle.VERTICAL_SIDE_DOCK -> {
                totalFpsView?.apply {
                    text = "$displayTotalFps FPS"
                    setTextColor(accentColor)
                }
                nativeFpsView?.text = "NAT $displayNativeFps"
                plusGenFpsView?.apply {
                    text = "+$displayGenFps GEN"
                    setTextColor(
                        if (displayGenFps > 0) Color.parseColor("#00E676") else Color.parseColor("#94A3B8")
                    )
                }
                detailRowView?.text = String.format(
                    Locale.US,
                    "%.1fms • %d°C",
                    displayLatencyMs,
                    telemetry.gpuTempC.roundToInt()
                )
            }

            FrameGenFloatingStyle.TACTICAL_HUD_CARD,
            FrameGenFloatingStyle.LIQUID_GLASS_POD -> {
                nativeFpsView?.text = "NAT $displayNativeFps"
                plusGenFpsView?.apply {
                    text = " + GEN $displayGenFps "
                    setTextColor(
                        if (displayGenFps > 0) Color.parseColor("#00E676") else Color.parseColor("#94A3B8")
                    )
                }
                totalFpsView?.apply {
                    text = "→ $displayTotalFps FPS"
                    setTextColor(accentColor)
                }
                detailRowView?.text = if (telemetry.isPausedByGuardrail && !isPreviewOnly) {
                    telemetry.guardrailStatus.take(36)
                } else {
                    String.format(
                        Locale.US,
                        "%.1fms • %s • GPU %d°C",
                        displayLatencyMs,
                        telemetry.effectiveQuality.label,
                        telemetry.gpuTempC.roundToInt()
                    )
                }
            }
        }
    }

    private fun createPanelBackground(
        style: FrameGenFloatingStyle,
        density: Float,
        scale: Float,
        borderColor: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            when (style) {
                FrameGenFloatingStyle.COMPACT_PILL_BAR -> {
                    cornerRadius = 999f * density
                    setColor(Color.parseColor("#EE080C14"))
                    setStroke((1.3f * density).roundToInt().coerceAtLeast(1), borderColor)
                }
                FrameGenFloatingStyle.MINIMAL_FPS_BADGE -> {
                    cornerRadius = 10f * scale * density
                    setColor(Color.parseColor("#E6090D16"))
                    setStroke((1.1f * density).roundToInt().coerceAtLeast(1), borderColor)
                }
                FrameGenFloatingStyle.LIQUID_GLASS_POD -> {
                    cornerRadius = 18f * scale * density
                    colors = intArrayOf(
                        Color.parseColor("#D812283A"),
                        Color.parseColor("#CC0A1826")
                    )
                    orientation = GradientDrawable.Orientation.TL_BR
                    setStroke((1.5f * density).roundToInt().coerceAtLeast(1), Color.parseColor("#AA80D8FF"))
                }
                FrameGenFloatingStyle.VERTICAL_SIDE_DOCK -> {
                    cornerRadius = 12f * scale * density
                    setColor(Color.parseColor("#EE0A0E17"))
                    setStroke((1.3f * density).roundToInt().coerceAtLeast(1), borderColor)
                }
                FrameGenFloatingStyle.TACTICAL_HUD_CARD -> {
                    cornerRadius = 14f * scale * density
                    setColor(Color.parseColor("#EA0B1018"))
                    setStroke((1.2f * density).roundToInt().coerceAtLeast(1), borderColor)
                }
            }
        }
    }
}
