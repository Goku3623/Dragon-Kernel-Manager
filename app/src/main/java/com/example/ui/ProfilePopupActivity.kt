package com.example.ui

import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.data.repository.KernelRepository
import com.example.ui.components.ProfileSelectionDialog
import com.example.ui.theme.DragonKernelTheme

class ProfilePopupActivity : ComponentActivity() {

    private val repository by lazy { KernelRepository.getInstance(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureHighRefreshRatePopupWindow()

        setContent {
            val accentIndex by repository.accentColorIndexFlow.collectAsState()
            val isAmoledBlack by repository.isAmoledBlackFlow.collectAsState()
            val activeProfile by repository.activeProfileFlow.collectAsState()
            val customProfiles by repository.customProfilesFlow.collectAsState()
            val activeCustomProfile by repository.activeCustomProfileFlow.collectAsState()
            val liveFps by repository.currentFpsFlow.collectAsState()
            val liveCpuTemp by repository.cpuTempFlow.collectAsState()
            val gpuConfig by repository.gpuConfigFlow.collectAsState()
            val themeConfig by repository.themeConfigFlow.collectAsState()
            val isRecordingFps by repository.isRecordingFpsFlow.collectAsState()
            val fpsRecorderConfig by repository.fpsRecorderConfigFlow.collectAsState()

            DragonKernelTheme(
                accentIndex = accentIndex,
                isAmoledBlack = isAmoledBlack,
                themeConfig = themeConfig
            ) {
                // Lightweight translucent scrim background that closes on outside click
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            finish()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    ProfileSelectionDialog(
                        currentProfile = activeProfile,
                        customProfiles = customProfiles,
                        activeCustomProfile = activeCustomProfile,
                        liveFps = liveFps,
                        liveCpuTemp = liveCpuTemp,
                        liveGpuMhz = gpuConfig.curFreqMhz,
                        isRecordingFps = isRecordingFps,
                        elapsedRecordingSec = fpsRecorderConfig.elapsedRecordingSec,
                        onToggleFpsRecord = {
                            repository.toggleFpsRecording()
                        },
                        onSelectProfile = { profile ->
                            repository.setBatteryProfile(profile)
                            Toast.makeText(this@ProfilePopupActivity, "HUD Mode: ${profile.title}", Toast.LENGTH_SHORT).show()
                            finish()
                        },
                        onSelectCustomProfile = { cProfile ->
                            repository.setCustomBatteryProfile(cProfile)
                            Toast.makeText(this@ProfilePopupActivity, "HUD Mode: ${cProfile.title}", Toast.LENGTH_SHORT).show()
                            finish()
                        },
                        onDeleteCustomProfile = { id ->
                            repository.deleteCustomProfile(id)
                        },
                        onDismiss = {
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun ensureHighRefreshRatePopupWindow() {
        // Do not set preferredDisplayModeId or preferredRefreshRate so the popup never alters system FPS.
    }
}

