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
import com.example.data.model.CpuCluster
import com.example.data.repository.KernelRepository
import com.example.ui.theme.DangerRed
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CpuScreen(repository: KernelRepository) {
    val cpuClusters by repository.cpuClustersFlow.collectAsState()
    val coresStatus by repository.coresStatusFlow.collectAsState()
    val cpuTemp by repository.cpuTempFlow.collectAsState()
    val cpuLoad by repository.cpuLoadFlow.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 90.dp)
    ) {
        item {
            // Screen Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "CPU FREQUENCY & CLUSTER TUNER",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Snapdragon 8s Gen 3 (SM8635)",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        text = "Avg Load: $cpuLoad%",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            // Core-Specific Management Tools Section
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
                            Icon(Icons.Default.Tune, contentDescription = "Cores", tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Core-Specific Management", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Text(
                            text = "8 Cores Total",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        text = "Toggle individual CPU cores online/offline to optimize thermal headroom & battery life.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Grid of 8 cores
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        coresStatus.chunked(2).forEach { rowCores ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowCores.forEach { core ->
                                    val clusterType = when (core.clusterId) {
                                        0 -> "Silver (A520)"
                                        1 -> "Gold (A720)"
                                        else -> "Prime (X4)"
                                    }
                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .border(
                                                1.dp,
                                                if (core.isOnline) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                                else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                                                RoundedCornerShape(12.dp)
                                            ),
                                        colors = CardDefaults.cardColors(
                                            containerColor = if (core.isOnline) MaterialTheme.colorScheme.surfaceVariant
                                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
                                        )
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(
                                                        text = "Core #${core.id}",
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp,
                                                        color = if (core.isOnline) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Text(
                                                        text = clusterType,
                                                        fontSize = 9.sp,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                Switch(
                                                    checked = core.isOnline,
                                                    onCheckedChange = { online ->
                                                        repository.toggleCoreOnline(core.id, online)
                                                    },
                                                    modifier = Modifier.height(26.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(6.dp))

                                            if (core.isOnline) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Text(
                                                        text = "${core.curFreqKhz / 1000} MHz",
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Text(
                                                        text = "${core.loadPercent}%",
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                LinearProgressIndicator(
                                                    progress = { (core.loadPercent / 100f).coerceIn(0f, 1f) },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(4.dp)
                                                        .clip(RoundedCornerShape(2.dp)),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    trackColor = MaterialTheme.colorScheme.surface
                                                )
                                            } else {
                                                Text(
                                                    text = "OFFLINE (Sleeping)",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = DangerRed
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Detailed Cluster Freq & Undervolting Controls
        items(cpuClusters.size) { index ->
            val cluster = cpuClusters[index]
            ClusterTuningCard(
                cluster = cluster,
                onUpdateGovernor = { gov -> repository.updateClusterGovernor(cluster.id, gov) },
                onUpdateFreq = { minF, maxF -> repository.updateClusterMinMaxFreq(cluster.id, minF, maxF) },
                onUpdateUndervolt = { offset -> repository.updateClusterUndervolt(cluster.id, offset) }
            )
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClusterTuningCard(
    cluster: CpuCluster,
    onUpdateGovernor: (String) -> Unit,
    onUpdateFreq: (Int, Int) -> Unit,
    onUpdateUndervolt: (Int) -> Unit
) {
    var expandedGovDropdown by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = cluster.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${cluster.archName} • ${cluster.coreRange}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        text = "${cluster.curFreqKhz / 1000} MHz",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Governor Selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "CPU Scaling Governor:",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Box {
                    OutlinedButton(
                        onClick = { expandedGovDropdown = true },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                    ) {
                        Text(cluster.governor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "dropdown", modifier = Modifier.size(16.dp))
                    }

                    DropdownMenu(
                        expanded = expandedGovDropdown,
                        onDismissRequest = { expandedGovDropdown = false }
                    ) {
                        cluster.availableGovernors.forEach { gov ->
                            DropdownMenuItem(
                                text = { Text(gov, fontWeight = if (gov == cluster.governor) FontWeight.Bold else FontWeight.Normal) },
                                onClick = {
                                    onUpdateGovernor(gov)
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

            // Max Frequency Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Max Frequency Cap:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${cluster.maxFreqKhz / 1000} MHz",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val maxIndex = cluster.availableFrequencies.indexOf(cluster.maxFreqKhz).takeIf { it >= 0 } ?: (cluster.availableFrequencies.size - 1)
            Slider(
                value = maxIndex.toFloat(),
                onValueChange = { newVal ->
                    val chosenFreq = cluster.availableFrequencies[newVal.toInt().coerceIn(0, cluster.availableFrequencies.size - 1)]
                    val minF = if (cluster.minFreqKhz > chosenFreq) chosenFreq else cluster.minFreqKhz
                    onUpdateFreq(minF, chosenFreq)
                },
                valueRange = 0f..(cluster.availableFrequencies.size - 1).toFloat(),
                steps = cluster.availableFrequencies.size - 2
            )

            // Min Frequency Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Min Scaling Frequency:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "${cluster.minFreqKhz / 1000} MHz",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val minIndex = cluster.availableFrequencies.indexOf(cluster.minFreqKhz).takeIf { it >= 0 } ?: 0
            Slider(
                value = minIndex.toFloat(),
                onValueChange = { newVal ->
                    val chosenFreq = cluster.availableFrequencies[newVal.toInt().coerceIn(0, cluster.availableFrequencies.size - 1)]
                    val maxF = if (cluster.maxFreqKhz < chosenFreq) chosenFreq else cluster.maxFreqKhz
                    onUpdateFreq(chosenFreq, maxF)
                },
                valueRange = 0f..(cluster.availableFrequencies.size - 1).toFloat(),
                steps = cluster.availableFrequencies.size - 2
            )

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(modifier = Modifier.height(10.dp))

            // Undervolting Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Cluster Undervolt Offset",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Lowers voltage supply table to suppress thermal throttling",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Badge(
                    containerColor = if (cluster.undervoltOffsetMv < 0) SuccessGreen.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (cluster.undervoltOffsetMv < 0) SuccessGreen else MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = "${cluster.undervoltOffsetMv} mV",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Slider(
                value = cluster.undervoltOffsetMv.toFloat(),
                onValueChange = { onUpdateUndervolt(it.toInt()) },
                valueRange = -100f..50f,
                steps = 29 // 5mV steps
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("-100 mV (Max Undervolt)", fontSize = 9.sp, color = SuccessGreen)
                Text("0 mV (Stock)", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("+50 mV (Overvolt)", fontSize = 9.sp, color = WarningOrange)
            }
        }
    }
}
