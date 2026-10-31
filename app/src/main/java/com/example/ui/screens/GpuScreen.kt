package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.KernelRepository
import com.example.ui.theme.DangerRed
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GpuScreen(repository: KernelRepository) {
    val gpuConfig by repository.gpuConfigFlow.collectAsState()
    var expandedGovDropdown by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 90.dp)
    ) {
        item {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "GPU FREQUENCY & VOLTAGE SCALING",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${gpuConfig.modelName} (250 - 1100 MHz)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        text = "Load: ${gpuConfig.loadPercent}%",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            // GPU Overview & Max 1100 MHz Clock Speed Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SportsEsports, contentDescription = "GPU", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Adreno 735 Clock Scaling (250 - 1100 MHz)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "LOWEST CLOCK", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "${gpuConfig.minFreqMhz} MHz",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(text = "CURRENT CLOCK", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "${gpuConfig.curFreqMhz} MHz",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(text = "MAX FREQ CAP", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = "${gpuConfig.maxFreqMhz} MHz",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                color = if (gpuConfig.maxFreqMhz >= 1100) DangerRed else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { (gpuConfig.curFreqMhz.toFloat() / 1100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Governor dropdown
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Devfreq Governor:", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Box {
                            OutlinedButton(
                                onClick = { expandedGovDropdown = true },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                            ) {
                                Text(gpuConfig.governor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "dropdown", modifier = Modifier.size(16.dp))
                            }
                            DropdownMenu(
                                expanded = expandedGovDropdown,
                                onDismissRequest = { expandedGovDropdown = false }
                            ) {
                                gpuConfig.availableGovernors.forEach { gov ->
                                    DropdownMenuItem(
                                        text = { Text(gov) },
                                        onClick = {
                                            repository.updateGpuGovernor(gov)
                                            expandedGovDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    Spacer(modifier = Modifier.height(12.dp))

                    // Lowest GPU Frequency Profile (Minimum 250 MHz)
                    Text(
                        text = "Lowest GPU Frequency (Idling & Power-Saving Floor - Min 250 MHz):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(250, 450, 600, 750).forEach { freq ->
                            val isSelected = (gpuConfig.minFreqMhz == freq)
                            Button(
                                onClick = { repository.updateGpuMinClock(freq) },
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 34.dp),
                                contentPadding = PaddingValues(2.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    text = "$freq",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Max Frequency Selection up to 1100 MHz
                    Text(
                        text = "Max GPU Frequency Profile (Up to 1100 MHz Extreme):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        gpuConfig.availableFrequenciesMhz.forEach { freq ->
                            val isSelected = (gpuConfig.maxFreqMhz == freq)
                            Button(
                                onClick = { repository.updateGpuMaxClock(freq) },
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 36.dp),
                                contentPadding = PaddingValues(2.dp),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) {
                                        if (freq >= 1100) DangerRed else MaterialTheme.colorScheme.primary
                                    } else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    text = "$freq",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            // GPU Boost (AdrenoBoost) Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.RocketLaunch, contentDescription = "GPU Boost", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Adreno GPU Boost", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(
                                    "Accelerates devfreq ramp-up aggressiveness during 3D workloads",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = gpuConfig.gpuBoostEnabled,
                            onCheckedChange = { enabled ->
                                repository.updateGpuBoost(enabled, gpuConfig.gpuBoostLevel)
                            }
                        )
                    }

                    if (gpuConfig.gpuBoostEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "GPU Boost Intensity Profile (`/sys/class/kgsl/kgsl-3d0/devfreq/adrenoboost`):",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("LOW", "MEDIUM", "DYNAMIC", "AGGRESSIVE").forEach { level ->
                                val isSelected = gpuConfig.gpuBoostLevel == level
                                Button(
                                    onClick = { repository.updateGpuBoost(true, level) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 36.dp),
                                    contentPadding = PaddingValues(2.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) {
                                            if (level == "AGGRESSIVE") DangerRed else MaterialTheme.colorScheme.primary
                                        } else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Text(
                                        text = level,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.Black else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            // GPU Hardware Power Level Card (default_pwrlevel)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Speed, contentDescription = "GPU Power Level", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("GPU Power Level (PwrLevel)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                Text(
                                    "Configures Qualcomm KGSL hardware power state (`default_pwrlevel`)",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                text = "Level ${gpuConfig.powerLevel}",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val powerLevels = listOf(
                        Triple(0, "Level 0 • Turbo Peak", "1100 MHz Max Performance State"),
                        Triple(1, "Level 1 • High Performance", "900 MHz Sustained Gaming State"),
                        Triple(2, "Level 2 • Balanced", "600 MHz Daily Efficiency State"),
                        Triple(3, "Level 3 • Power Save", "250 MHz Ultra-Low Power Floor")
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        powerLevels.forEach { (lvl, title, desc) ->
                            val isSelected = gpuConfig.powerLevel == lvl
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                        RoundedCornerShape(12.dp)
                                    ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                ),
                                onClick = { repository.updateGpuPowerLevel(lvl) }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = { repository.updateGpuPowerLevel(lvl) }
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column {
                                            Text(
                                                text = title,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = desc,
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Badge(
                                        containerColor = if (lvl == 0) DangerRed.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface,
                                        contentColor = if (lvl == 0) DangerRed else MaterialTheme.colorScheme.onSurface
                                    ) {
                                        Text("PWR $lvl", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            // Custom Voltage Control Sliders (Undervolting)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.ElectricBolt, contentDescription = "Voltage", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Custom GPU Voltage Control Sliders", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }

                    Text(
                        text = "Fine-tune per-frequency voltage offsets (mV) to eliminate thermal throttling in Adreno 735.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    gpuConfig.availableFrequenciesMhz.reversed().forEach { freq ->
                        val currentOffset = gpuConfig.customVoltageOffsets[freq] ?: -25
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "$freq MHz",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (freq >= 1100) DangerRed else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (freq >= 1100) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Badge(containerColor = DangerRed.copy(alpha = 0.2f), contentColor = DangerRed) {
                                            Text("TURBO MAX", fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                                Badge(
                                    containerColor = if (currentOffset < 0) SuccessGreen.copy(alpha = 0.2f) else WarningOrange.copy(alpha = 0.2f),
                                    contentColor = if (currentOffset < 0) SuccessGreen else WarningOrange
                                ) {
                                    Text(
                                        text = "$currentOffset mV",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }

                            Slider(
                                value = currentOffset.toFloat(),
                                onValueChange = { newVal ->
                                    repository.updateGpuVoltageOffset(freq, newVal.toInt())
                                },
                                valueRange = -100f..30f,
                                steps = 25
                            )
                        }
                        if (freq != 250) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }
    }
}
