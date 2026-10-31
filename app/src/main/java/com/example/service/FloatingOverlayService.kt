package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.example.data.repository.KernelRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

class FloatingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var appRowView: TextView? = null
    private var fpsRowView: TextView? = null
    private var cpuRowView: TextView? = null
    private var batRowView: TextView? = null
    private var gpuRowView: TextView? = null

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var updateJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupOverlayView()
        startTicker()
    }

    private fun setupOverlayView() {
        val wm = windowManager ?: return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 120
        }
        overlayParams = params

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = dpToPx(10)
            val padV = dpToPx(8)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(0xDD0D0F14.toInt())
                cornerRadius = dpToPx(12).toFloat()
                setStroke(dpToPx(1), 0x88FF2A44.toInt())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
                } catch (_: Throwable) {
                }
            }
        }

        // Header Title
        val headerTitleView = TextView(this).apply {
            text = "⚡ DRAGON FPS METER"
            setTextColor(0xFFFF3B30.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            val padBottom = dpToPx(4)
            setPadding(0, 0, 0, padBottom)
        }
        container.addView(headerTitleView)

        // Telemetry Rows
        appRowView = createTextRow("APP: --", 0xFFE2E8F0.toInt(), 10f).also { container.addView(it) }
        fpsRowView = createTextRow("FPS: --", 0xFF00E5FF.toInt(), 13f, bold = true).also { container.addView(it) }
        cpuRowView = createTextRow("CPU: --", 0xFFFF9F0A.toInt(), 11f).also { container.addView(it) }
        batRowView = createTextRow("BAT: --", 0xFFFFD600.toInt(), 11f).also { container.addView(it) }
        gpuRowView = createTextRow("GPU: --", 0xFF00E676.toInt(), 11f).also { container.addView(it) }

        // Draggable window touch listener for panel body
        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        wm.updateViewLayout(container, params)
                        return true
                    }
                }
                return false
            }
        })

        overlayView = container
        wm.addView(container, params)
    }

    private fun updateRowIfChanged(view: TextView?, visible: Boolean, newText: String): Boolean {
        if (view == null) return false
        var changed = false
        val desiredVis = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != desiredVis) {
            view.visibility = desiredVis
            changed = true
        }
        if (visible && view.text.toString() != newText) {
            view.text = newText
            changed = true
        }
        return changed
    }

    private fun startTicker() {
        updateJob?.cancel()
        updateJob = scope.launch {
            val repository = KernelRepository.getInstance(applicationContext)
            while (isActive) {
                val cfg = repository.fpsOverlayConfigFlow.value
                val recCfg = repository.fpsRecorderConfigFlow.value
                val battery = repository.batteryFlow.value
                val cpuTemp = repository.cpuTempFlow.value
                val gpu = repository.gpuConfigFlow.value
                val fps = repository.currentFpsFlow.value

                val fgAppShort = recCfg.detectedForegroundAppName
                    .ifBlank { recCfg.targetAppName }
                    .take(18)
                val frameTimeStr = if (cfg.showFrameTimeMs) {
                    String.format(Locale.US, " (%.1fms)", recCfg.currentFrameTimeMs)
                } else ""

                var didRedraw = false
                if (updateRowIfChanged(appRowView, cfg.showForegroundApp, "APP: $fgAppShort")) didRedraw = true
                if (updateRowIfChanged(fpsRowView, cfg.showFps, "FPS: $fps$frameTimeStr")) didRedraw = true
                if (updateRowIfChanged(cpuRowView, cfg.showCpuTemp, String.format(Locale.US, "CPU: %.1f°C", cpuTemp))) didRedraw = true
                if (updateRowIfChanged(batRowView, cfg.showBatTemp, String.format(Locale.US, "BAT: %.1f°C", battery.temperatureC))) didRedraw = true
                if (updateRowIfChanged(gpuRowView, cfg.showGpuClock, "GPU: ${gpu.curFreqMhz} MHz")) didRedraw = true

                if (didRedraw) {
                    repository.notifyOverlaySelfRedraw()
                }

                delay(500L)
            }
        }
    }

    private fun createTextRow(content: String, color: Int, sizeSp: Float = 12f, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = content
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = if (bold) {
                android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            } else {
                android.graphics.Typeface.MONOSPACE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        super.onDestroy()
        updateJob?.cancel()
        overlayView?.let { windowManager?.removeView(it) }
    }
}
