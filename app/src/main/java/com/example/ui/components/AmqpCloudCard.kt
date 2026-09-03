package com.example.ui.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.TelemetryBufferRepository
import com.example.service.ScheduleManager
import com.example.service.amqp.AmqpCloudManager
import com.example.service.amqp.BatchDischargeState
import com.example.service.amqp.RealtimeStreamState
import com.example.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AmqpCloudCard(
    context: Context,
    modifier: Modifier = Modifier
) {
    val cloudManager = remember { AmqpCloudManager.getInstance(context) }
    val bufferRepository = remember { TelemetryBufferRepository.getInstance(context) }

    val realtimeStats by cloudManager.realtimeStats.collectAsState()
    val batchStats by cloudManager.batchStats.collectAsState()
    val unsyncedInDb by bufferRepository.unsyncedBufferedCount.collectAsState()

    val config = remember { ScheduleManager.getInstance().config }
    val brokerHost = config?.amqpHost ?: "143.106.8.17"
    val brokerPort = config?.amqpPort ?: 5672
    val brokerExchange = config?.amqpExchange ?: "amq.direct"
    val brokerVHost = config?.amqpVirtualHost ?: "/"

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, DarkBorder),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(CyberCyanPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = CyberCyanPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Cloud & Messaging Layer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "RabbitMQ AMQP • SIM Cellular + Wi-Fi Dual Path",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }

                Surface(
                    color = CyberCyanPrimary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, CyberCyanPrimary.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "STEP 4",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberCyanPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Broker Connection Info Strip
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = DarkSurfaceVariant,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, DarkBorder.copy(alpha = 0.7f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Dns, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                        Text(
                            text = "Target Broker:",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                        Text(
                            text = "$brokerHost:$brokerPort ($brokerVHost)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Text(
                        text = "Ex: $brokerExchange",
                        style = MaterialTheme.typography.labelSmall,
                        color = CyberCyanPrimary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // ==========================================
            // SUB-SECTION 1: REAL-TIME SIM STREAM (1s)
            // ==========================================
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = DarkSurfaceVariant,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                                imageVector = Icons.Default.SignalCellularAlt,
                                contentDescription = null,
                                tint = TechTealSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Real-Time Stream (Forced SIM Cellular)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        // Realtime State Badge
                        val (realtimeBadgeText, realtimeBadgeColor) = when (realtimeStats.state) {
                            RealtimeStreamState.STREAMING -> "STREAMING (1s)" to StatusActive
                            RealtimeStreamState.AWAITING_CELLULAR -> "AWAITING SIM" to StatusWarning
                            RealtimeStreamState.CONNECTING -> "CONNECTING..." to CyberCyanPrimary
                            RealtimeStreamState.AUTHENTICATING -> "AUTHENTICATING" to CyberCyanPrimary
                            RealtimeStreamState.RETRY_BACKOFF -> "BACKOFF WAIT" to StatusWarning
                            RealtimeStreamState.PAUSED -> "STANDBY (Wi-Fi ACTIVE)" to TextMuted
                            RealtimeStreamState.AUTH_ERROR -> "AUTH REFUSED (403)" to StatusError
                            RealtimeStreamState.ERROR -> "ERROR" to StatusError
                            RealtimeStreamState.IDLE -> "IDLE" to TextMuted
                        }

                        Surface(
                            color = realtimeBadgeColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, realtimeBadgeColor.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = realtimeBadgeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = realtimeBadgeColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // Network interface binding indicator
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (realtimeStats.cellularAvailable) StatusActive else StatusWarning)
                        )
                        Text(
                            text = if (realtimeStats.cellularAvailable)
                                "Network: Bound to Cellular Mobile Data (TRANSPORT_CELLULAR)"
                            else
                                "Network: Requesting Mobile Data radio via ConnectivityManager...",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (realtimeStats.cellularAvailable) TextSecondary else StatusWarning
                        )
                    }

                    // Metrics Grid (2x2)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricMiniCard(
                            label = "Sent (1s)",
                            value = "${realtimeStats.packetsSent}",
                            tint = StatusActive,
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniCard(
                            label = "Latency",
                            value = if (realtimeStats.lastLatencyMs > 0) "${realtimeStats.lastLatencyMs}ms" else "—",
                            tint = CyberCyanPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniCard(
                            label = "Dropped",
                            value = "${realtimeStats.packetsDropped}",
                            tint = if (realtimeStats.packetsDropped > 0) StatusWarning else TextMuted,
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniCard(
                            label = "Retries",
                            value = "${realtimeStats.consecutiveFailures}",
                            tint = if (realtimeStats.consecutiveFailures > 0) StatusError else TextMuted,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Routing key or Paused status display
                    if (realtimeStats.state == RealtimeStreamState.PAUSED) {
                        Text(
                            text = "Stream paused: Wi-Fi discharge window active (TCP Mesh OFF). All telemetry persisted in Room SQLite.",
                            style = MaterialTheme.typography.labelSmall,
                            color = TechTealSecondary
                        )
                    } else if (realtimeStats.activeRoutingKey.isNotEmpty()) {
                        Text(
                            text = "Routing Key: ${realtimeStats.activeRoutingKey}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = TextMuted
                        )
                    }

                    // Error banner if applicable
                    if (realtimeStats.lastError != null && (realtimeStats.state == RealtimeStreamState.ERROR || realtimeStats.state == RealtimeStreamState.AUTH_ERROR)) {
                        Surface(
                            color = StatusError.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, StatusError.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = StatusError, modifier = Modifier.size(16.dp))
                                Text(
                                    text = realtimeStats.lastError ?: "Connection failure",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusError
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // SUB-SECTION 2: OFFLINE BULK DISCHARGER (Wi-Fi)
            // ==========================================
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = DarkSurfaceVariant,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, DarkBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
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
                                imageVector = Icons.Default.Wifi,
                                contentDescription = null,
                                tint = CyberCyanPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Bulk Discharger (Mandatory Wi-Fi)",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        // Batch State Badge
                        val (batchBadgeText, batchBadgeColor) = when (batchStats.state) {
                            BatchDischargeState.DISCHARGING -> "DISCHARGING" to StatusActive
                            BatchDischargeState.CONFIRMING -> "CONFIRMING ACKs" to CyberCyanPrimary
                            BatchDischargeState.AWAITING_WIFI -> "AWAITING WI-FI" to StatusWarning
                            BatchDischargeState.CONNECTING -> "CONNECTING" to CyberCyanPrimary
                            BatchDischargeState.COMPLETED -> "ALL SYNCED" to StatusActive
                            BatchDischargeState.AUTH_ERROR -> "AUTH REFUSED (403)" to StatusError
                            BatchDischargeState.ERROR -> "ERROR" to StatusError
                            BatchDischargeState.IDLE -> "STANDBY" to TextMuted
                        }

                        Surface(
                            color = batchBadgeColor.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, batchBadgeColor.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = batchBadgeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = batchBadgeColor,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    // Protocol description
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = TechTealSecondary, modifier = Modifier.size(14.dp))
                        Text(
                            text = "Publisher Confirms active (records marked synced only upon broker ACK)",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }

                    // Metrics Grid (2x2)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricMiniCard(
                            label = "Discharged",
                            value = "${batchStats.totalRecordsDischarged}",
                            tint = CyberCyanPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniCard(
                            label = "Broker ACKs",
                            value = "${batchStats.confirmsReceived}",
                            tint = StatusActive,
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniCard(
                            label = "Unsynced DB",
                            value = "$unsyncedInDb",
                            tint = if (unsyncedInDb > 0) StatusWarning else TextMuted,
                            modifier = Modifier.weight(1f)
                        )
                        MetricMiniCard(
                            label = "NACK / Fail",
                            value = "${batchStats.confirmsFailed}",
                            tint = if (batchStats.confirmsFailed > 0) StatusError else TextMuted,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (batchStats.lastError != null && (batchStats.state == BatchDischargeState.ERROR || batchStats.state == BatchDischargeState.AUTH_ERROR)) {
                        Surface(
                            color = StatusError.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, StatusError.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = StatusError, modifier = Modifier.size(16.dp))
                                Text(
                                    text = batchStats.lastError ?: "Batch discharge error",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusError
                                )
                            }
                        }
                    }

                    if (batchStats.lastDischargeTimestamp > 0) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Last batch: ${timeFormat.format(Date(batchStats.lastDischargeTimestamp))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Text(
                                text = "Autonomous scheduled sync",
                                style = MaterialTheme.typography.labelSmall,
                                color = TechTealSecondary
                            )
                        }
                    } else {
                        Text(
                            text = "Awaiting scheduled Wi-Fi window for batch discharge",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricMiniCard(
    label: String,
    value: String,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = DarkSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, DarkBorder.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = tint
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = TextMuted,
                maxLines = 1
            )
        }
    }
}
