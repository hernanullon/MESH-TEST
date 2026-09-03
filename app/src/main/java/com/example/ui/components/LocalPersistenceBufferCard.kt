package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.TelemetryBufferRepository
import com.example.data.local.TelemetryRecordEntity
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun LocalPersistenceBufferCard(
    context: Context,
    modifier: Modifier = Modifier
) {
    val repository = remember { TelemetryBufferRepository.getInstance(context) }
    val totalCount by repository.totalBufferedCount.collectAsState()
    val unsyncedCount by repository.unsyncedBufferedCount.collectAsState()
    val locationCount by repository.locationCount.collectAsState()
    val inertialCount by repository.inertialCount.collectAsState()
    val deviceStatusCount by repository.deviceStatusCount.collectAsState()
    val externalTcpCount by repository.externalTcpCount.collectAsState()
    val isBufferingInhibited by repository.isBufferingInhibited.collectAsState()

    val coroutineScope = rememberCoroutineScope()
    var showInspectDialog by remember { mutableStateOf(false) }
    var inspectRecords by remember { mutableStateOf<List<TelemetryRecordEntity>>(emptyList()) }
    var isInspecting by remember { mutableStateOf(false) }

    // Refresh counters on initial view
    LaunchedEffect(Unit) {
        repository.refreshCounters()
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, TechTealSecondary.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with On-Demand Refresh Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Storage,
                        contentDescription = null,
                        tint = TechTealSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Independent Local Buffer (SQLite Room)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "Raw ungrouped multi-sensor storage",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }

                // On-demand refresh button
                IconButton(
                    onClick = {
                        repository.refreshCounters()
                        Toast.makeText(context, "Buffer counts refreshed", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(32.dp).testTag("btn_refresh_buffer_stats")
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh Counts",
                        tint = CyberCyanPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Inhibition / Pause Banner during Wi-Fi Active Discharge
            if (isBufferingInhibited) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = StatusWarning.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.PauseCircle,
                            contentDescription = null,
                            tint = StatusWarning,
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = "Persistence Paused (Wi-Fi Discharge Active)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = StatusWarning
                            )
                            Text(
                                text = "Wi-Fi is active for AMQP discharge. Ingestion to SQLite is paused to avoid infinite loops.",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            // Group Counts Grid (Independent Rates)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Total Buffer
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkSurfaceVariant,
                        border = BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("Total Records", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontSize = 10.sp)
                            Text("$totalCount", fontWeight = FontWeight.Bold, color = CyberCyanPrimary, fontSize = 16.sp)
                            Text("In SQLite DB", fontSize = 9.sp, color = TextMuted)
                        }
                    }

                    // Unsynced
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkSurfaceVariant,
                        border = BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("Pending Sync", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontSize = 10.sp)
                            Text("$unsyncedCount", fontWeight = FontWeight.Bold, color = TechTealSecondary, fontSize = 16.sp)
                            Text("For AMQP Cloud", fontSize = 9.sp, color = TextMuted)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Location Group
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkBackground,
                        border = BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Text("GPS/Location", fontSize = 10.sp, color = TechTealSecondary, fontWeight = FontWeight.Bold)
                            Text("$locationCount", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Text("type: LOCATION", fontSize = 8.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                        }
                    }

                    // Inertial Group
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkBackground,
                        border = BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Text("IMU/Inertial", fontSize = 10.sp, color = CyberCyanPrimary, fontWeight = FontWeight.Bold)
                            Text("$inertialCount", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Text("type: INERTIAL", fontSize = 8.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                        }
                    }

                    // Device Status Group
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkBackground,
                        border = BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Text("Device/Bat", fontSize = 10.sp, color = StatusActive, fontWeight = FontWeight.Bold)
                            Text("$deviceStatusCount", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Text("type: DEVICE", fontSize = 8.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                        }
                    }

                    // TCP Group
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = DarkBackground,
                        border = BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(6.dp)) {
                            Text("TCP Mesh", fontSize = 10.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                            Text("$externalTcpCount", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                            Text("type: TCP/RAW", fontSize = 8.sp, color = TextMuted, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            // Diagnostic Buffer Inspection
            OutlinedButton(
                onClick = {
                    coroutineScope.launch {
                        isInspecting = true
                        inspectRecords = repository.getRecentRecordsDirect(30)
                        repository.refreshCounters()
                        isInspecting = false
                        showInspectDialog = true
                    }
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCyanPrimary),
                border = BorderStroke(1.dp, CyberCyanPrimary.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier.fillMaxWidth().testTag("btn_inspect_buffer")
            ) {
                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Inspect Buffered Records (Diagnostic View)", style = MaterialTheme.typography.labelMedium, fontSize = 12.sp)
            }
        }
    }

    if (showInspectDialog) {
        TelemetryBufferInspectionDialog(
            records = inspectRecords,
            onDismiss = { showInspectDialog = false },
            context = context
        )
    }
}

@Composable
fun TelemetryBufferInspectionDialog(
    records: List<TelemetryRecordEntity>,
    onDismiss: () -> Unit,
    context: Context
) {
    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Storage, contentDescription = null, tint = TechTealSecondary)
                Text("Local Buffer Inspector (SQLite)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp)) {
                Text(
                    text = "Displaying the last ${records.size} records in Room database (ungrouped by independent type):",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (records.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No records found in local buffer.", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(records.size) { index ->
                            val record = records[index]
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkSurfaceVariant,
                                border = BorderStroke(1.dp, DarkBorder),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "#${record.id} • ${record.sourceType}",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = when (record.sourceType) {
                                                "LOCATION" -> TechTealSecondary
                                                "INERTIAL" -> CyberCyanPrimary
                                                "DEVICE_STATUS" -> StatusActive
                                                else -> TextPrimary
                                            }
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (record.isSynced) StatusActive.copy(alpha = 0.2f) else StatusWarning.copy(alpha = 0.2f)
                                        ) {
                                            Text(
                                                text = if (record.isSynced) "SYNCED" else "PENDING",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                color = if (record.isSynced) StatusActive else StatusWarning,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Node: ${record.deviceId}", fontSize = 10.sp, color = TextMuted)
                                        Text(record.formattedDate, fontSize = 10.sp, color = TextMuted)
                                    }

                                    Text(
                                        text = record.payloadJson,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextPrimary,
                                        maxLines = 5
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        IconButton(
                                            onClick = {
                                                clipboardManager?.setPrimaryClip(ClipData.newPlainText("Telemetry JSON", record.payloadJson))
                                                Toast.makeText(context, "JSON copied to clipboard", Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy JSON", tint = CyberCyanPrimary, modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyanPrimary, contentColor = DarkBackground)
            ) {
                Text("Close", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = DarkSurface,
        shape = RoundedCornerShape(16.dp)
    )
}
