package com.example.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.data.repository.KernelRepository

class DragonAccessibilityMonitorService : AccessibilityService() {

    private val TAG = "DragonAccessibility"

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Dragon Accessibility Monitor Service Connected.")
        KernelRepository.getInstance(applicationContext).onAccessibilityServiceStateChanged(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            val className = event.className?.toString() ?: ""
            KernelRepository.getInstance(applicationContext).onForegroundAppChanged(pkg, className)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Dragon Accessibility Monitor Service Interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        KernelRepository.getInstance(applicationContext).onAccessibilityServiceStateChanged(false)
        Log.i(TAG, "Dragon Accessibility Monitor Service Destroyed.")
    }
}
