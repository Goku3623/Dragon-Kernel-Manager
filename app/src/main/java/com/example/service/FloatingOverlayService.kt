package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
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

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(10)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(0xEE12131A.toInt())
                cornerRadius = dpToPx(12).toFloat()
                setStroke(dpToPx(1), 0xFFFF2A44.toInt())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE)
                } catch (_: Throwable) {
                }
            }
        }

        val header = TextView(this).apply {
            text = "⚡ DRAGON HUD"
            setTextColor(0xFFFF3B30.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        container.addView(header)

        fpsRowView = createTextRow("FPS: --", 0xFF00E5FF.toInt()).also { container.addView(it) }
        cpuRowView = createTextRow("CPU: --", 0xFFFF9F0A.toInt()).also { container.addView(it) }
        batRowView = createTextRow("BAT: --", 0xFFFFD600.toInt()).also { container.addView(it) }
        gpuRowView = createTextRow("GPU: --", 0xFF00E676.toInt()).also { container.addView(it) }

        // Draggable touch listener
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

    private fun startTicker() {
        updateJob?.cancel()
        updateJob = scope.launch {
            val repository = KernelRepository.getInstance(applicationContext)
            while (isActive) {
                val cfg = repository.fpsOverlayConfigFlow.value
                val battery = repository.batteryFlow.value
                val cpuTemp = repository.cpuTempFlow.value
                val gpu = repository.gpuConfigFlow.value
                val fps = repository.currentFpsFlow.value

                fpsRowView?.apply {
                    visibility = if (cfg.showFps) View.VISIBLE else View.GONE
                    if (cfg.showFps) text = "FPS: $fps"
                }
                cpuRowView?.apply {
                    visibility = if (cfg.showCpuTemp) View.VISIBLE else View.GONE
                    if (cfg.showCpuTemp) text = String.format(Locale.US, "CPU: %.1f°C", cpuTemp)
                }
                batRowView?.apply {
                    visibility = if (cfg.showBatTemp) View.VISIBLE else View.GONE
                    if (cfg.showBatTemp) text = String.format(Locale.US, "BAT: %.1f°C", battery.temperatureC)
                }
                gpuRowView?.apply {
                    visibility = if (cfg.showGpuClock) View.VISIBLE else View.GONE
                    if (cfg.showGpuClock) text = "GPU: ${gpu.curFreqMhz} MHz"
                }

                delay(1000L)
            }
        }
    }

    private fun createTextRow(content: String, color: Int): TextView {
        return TextView(this).apply {
            text = content
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.MONOSPACE
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
