package com.example.ui

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

        setContent {
            val accentIndex by repository.accentColorIndexFlow.collectAsState()
            val isAmoledBlack by repository.isAmoledBlackFlow.collectAsState()
            val activeProfile by repository.activeProfileFlow.collectAsState()
            val customProfiles by repository.customProfilesFlow.collectAsState()
            val activeCustomProfile by repository.activeCustomProfileFlow.collectAsState()

            DragonKernelTheme(
                accentIndex = accentIndex,
                isAmoledBlack = isAmoledBlack
            ) {
                // Dim translucent scrim background that closes on click
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f))
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
                        onSelectProfile = { profile ->
                            repository.setBatteryProfile(profile)
                            Toast.makeText(this@ProfilePopupActivity, "Profile: ${profile.title}", Toast.LENGTH_SHORT).show()
                            finish()
                        },
                        onSelectCustomProfile = { cProfile ->
                            repository.setCustomBatteryProfile(cProfile)
                            Toast.makeText(this@ProfilePopupActivity, "Profile: ${cProfile.title}", Toast.LENGTH_SHORT).show()
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
}
