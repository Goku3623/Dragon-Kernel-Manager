package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DangerRed
import java.util.Locale

@Composable
fun ThermalAlertBanner(
    batTemp: Float,
    cpuTemp: Float,
    criticalThreshold: Float,
    onActivateCooling: () -> Unit
) {
    val isWarning = (batTemp >= criticalThreshold || cpuTemp >= 75.0f)

    AnimatedVisibility(
        visible = isWarning,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(DangerRed.copy(alpha = 0.18f))
                .border(1.dp, DangerRed, RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Thermal Warning",
                    tint = DangerRed,
                    modifier = Modifier.size(28.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CRITICAL THERMAL ALERT",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        color = DangerRed
                    )
                    Text(
                        text = String.format(Locale.US, "Bat: %.1f°C | CPU: %.1f°C exceeds %.0f°C limit", batTemp, cpuTemp, criticalThreshold),
                        fontSize = 11.sp,
                        color = Color.White
                    )
                }

                Button(
                    onClick = onActivateCooling,
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                ) {
                    Text("Cool Down", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
    }
}
