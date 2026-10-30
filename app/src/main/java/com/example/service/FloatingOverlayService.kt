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
        }

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

                overlayView?.let { container ->
                    container.removeAllViews()

                    val header = TextView(this@FloatingOverlayService).apply {
                        text = "⚡ DRAGON HUD"
                        setTextColor(0xFFFF3B30.toInt())
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                    }
                    container.addView(header)

                    if (cfg.showFps) {
                        val tv = createTextRow("FPS: $fps / 120Hz", 0xFF00E5FF.toInt())
                        container.addView(tv)
                    }
                    if (cfg.showCpuTemp) {
                        val tv = createTextRow(String.format(Locale.US, "CPU: %.1f°C", cpuTemp), 0xFFFF9F0A.toInt())
                        container.addView(tv)
                    }
                    if (cfg.showBatTemp) {
                        val tv = createTextRow(String.format(Locale.US, "BAT: %.1f°C", battery.temperatureC), 0xFFFFD600.toInt())
                        container.addView(tv)
                    }
                    if (cfg.showGpuClock) {
                        val tv = createTextRow("GPU: ${gpu.curFreqMhz} MHz", 0xFF00E676.toInt())
                        container.addView(tv)
                    }
                    if (cfg.showRamUsage) {
                        val tv = createTextRow("RAM: 6.4 / 12 GB", 0xFFBB86FC.toInt())
                        container.addView(tv)
                    }
                    if (cfg.showDrainRate) {
                        val drainText = if (battery.isCharging) "+%.1fW".format(battery.chargeSpeedWatts) else "-%dmA".format(battery.dischargeRateMa)
                        val tv = createTextRow("DRAIN: $drainText", 0xFFFFFFFF.toInt())
                        container.addView(tv)
                    }
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
