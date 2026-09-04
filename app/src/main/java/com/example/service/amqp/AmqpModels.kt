package com.example.service.amqp

import com.example.model.ScheduleConfig

/**
 * State of the Real-time Telemetry AMQP Stream over Cellular Mobile Data (SIM).
 */
enum class RealtimeStreamState {
    IDLE,
    AWAITING_CELLULAR,
    CONNECTING,
    AUTHENTICATING,
    STREAMING,
    RETRY_BACKOFF,
    PAUSED,
    ERROR
}

/**
 * State of the Offline Batch AMQP Discharger over Wi-Fi.
 */
enum class BatchDischargeState {
    IDLE,
    AWAITING_WIFI,
    CONNECTING,
    DISCHARGING,
    CONFIRMING,
    COMPLETED,
    ERROR
}

/**
 * Real-time Stream Metrics for UI & Monitoring.
 */
data class RealtimeStats(
    val state: RealtimeStreamState = RealtimeStreamState.IDLE,
    val cellularAvailable: Boolean = false,
    val networkInterfaceName: String = "None",
    val packetsSent: Long = 0,
    val packetsDropped: Long = 0,
    val errorCount: Long = 0,
    val lastSentTimestamp: Long = 0,
    val lastLatencyMs: Long = 0,
    val lastError: String? = null,
    val brokerEndpoint: String = "",
    val activeRoutingKey: String = "",
    val consecutiveFailures: Int = 0
)

/**
 * Batch Discharger Metrics for UI & Monitoring.
 */
data class BatchStats(
    val state: BatchDischargeState = BatchDischargeState.IDLE,
    val wifiAvailable: Boolean = false,
    val totalRecordsDischarged: Long = 0,
    val currentBatchSize: Int = 0,
    val confirmsReceived: Long = 0,
    val confirmsFailed: Long = 0,
    val lastDischargeTimestamp: Long = 0,
    val lastError: String? = null,
    val pendingRecordsInDb: Int = 0,
    val isDischarging: Boolean = false
)

/**
 * Immutable snapshot of AMQP Connection & Routing Parameters.
 */
data class AmqpConnectionParams(
    val host: String,
    val port: Int,
    val virtualHost: String,
    val username: String,
    val password: String,
    val exchange: String,
    val baseRoutingKey: String,
    val queue: String,
    val sslEnabled: Boolean,
    val deviceId: String
) {
    companion object {
        fun fromScheduleConfig(config: ScheduleConfig?): AmqpConnectionParams {
            if (config == null) {
                return AmqpConnectionParams(
                    host = "143.106.8.17",
                    port = 5672,
                    virtualHost = "/",
                    username = "guest",
                    password = "guest",
                    exchange = "amq.direct",
                    baseRoutingKey = "unicamp.campinas.",
                    queue = "",
                    sslEnabled = false,
                    deviceId = "NODE-01"
                )
            }
            return AmqpConnectionParams(
                host = config.amqpHost,
                port = config.amqpPort,
                virtualHost = config.amqpVirtualHost,
                username = config.amqpUsername,
                password = config.amqpPassword,
                exchange = config.amqpExchange,
                baseRoutingKey = config.amqpRoutingKey,
                queue = config.amqpQueue,
                sslEnabled = config.isAmqpSslEnabled,
                deviceId = config.deviceId ?: "NODE-01"
            )
        }
    }

    fun getRealtimeRoutingKey(): String {
        val cleanKey = baseRoutingKey.trim()
        val cleanDev = deviceId.trim().ifEmpty { "NODE-01" }
        return if (cleanKey.endsWith(".")) {
            "${cleanKey}realtime.$cleanDev"
        } else if (cleanKey.isNotEmpty()) {
            "$cleanKey.realtime.$cleanDev"
        } else {
            "telemetry.realtime.$cleanDev"
        }
    }

    fun getBatchRoutingKey(): String {
        val cleanKey = baseRoutingKey.trim()
        val cleanDev = deviceId.trim().ifEmpty { "NODE-01" }
        return if (cleanKey.endsWith(".")) {
            "${cleanKey}batch.$cleanDev"
        } else if (cleanKey.isNotEmpty()) {
            "$cleanKey.batch.$cleanDev"
        } else {
            "telemetry.batch.$cleanDev"
        }
    }
}
