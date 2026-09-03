package com.example.service.amqp

import android.content.Context
import com.example.data.local.TelemetryBufferRepository
import com.example.model.ScheduleConfig
import com.example.model.telemetry.UnifiedTelemetrySnapshot
import com.example.service.ScheduleManager
import com.example.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * High-Level Central Coordinator for Step 4 (Cloud & Messaging Layer).
 * Coordinates:
 * 1. Forced Cellular Mobile Data (SIM) Real-time AMQP Transmitter (1s Snapshots)
 * 2. Mandatory and Exclusive Wi-Fi Bulk AMQP Discharger with Publisher Confirms
 *
 * Provides a single unified reactive interface for UI observation and service lifecycle.
 */
class AmqpCloudManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val logger = AppLogger.getInstance()
    private val bufferRepository = TelemetryBufferRepository.getInstance(appContext)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val realtimeTransmitter = AmqpRealtimeTransmitter(appContext)
    val batchDischarger = AmqpBatchDischarger(appContext, bufferRepository)

    val realtimeStats: StateFlow<RealtimeStats> = realtimeTransmitter.stats
    val batchStats: StateFlow<BatchStats> = batchDischarger.stats

    @Volatile
    private var isStarted = false

    fun start() {
        if (isStarted) return
        isStarted = true

        val config = ScheduleManager.getInstance().config
        val params = AmqpConnectionParams.fromScheduleConfig(config)

        logger.s(TAG, "Starting Cloud & Messaging Layer (RabbitMQ AMQP)...")
        realtimeTransmitter.start(params)
        batchDischarger.start(params)
    }

    fun updateConfig(config: ScheduleConfig?) {
        val params = AmqpConnectionParams.fromScheduleConfig(config)
        realtimeTransmitter.updateParams(params)
        batchDischarger.updateParams(params)
    }

    /**
     * Dispatch 1-second snapshot from TelemetryEngine to Cellular AMQP Stream.
     */
    fun pushRealtimeSnapshot(snapshot: UnifiedTelemetrySnapshot) {
        if (!isStarted) start()
        realtimeTransmitter.enqueueSnapshot(snapshot)
    }

    /**
     * Recovery trigger: resets sockets and forces reconnect without app or OS reboot.
     */
    fun forceReconnectRealtime() {
        realtimeTransmitter.forceReconnect()
    }

    /**
     * Manually triggers bulk discharge of offline records over Wi-Fi.
     */
    fun triggerBatchDischarge() {
        batchDischarger.triggerBatchDischarge(forceManual = true)
    }

    /**
     * Called when the autonomous schedule opens or closes the Wi-Fi active window.
     * When Wi-Fi is active (discharge window): pauses Real-time SIM stream and engages batch discharge.
     * When Wi-Fi is inactive (field/local mesh window): resumes Real-time SIM stream.
     */
    fun onWifiWindowActive(active: Boolean) {
        if (active) {
            logger.s(TAG, "Wi-Fi discharge window active! Pausing Real-Time SIM stream & engaging bulk AMQP discharger...")
            realtimeTransmitter.pause()
            batchDischarger.onWifiWindowActive(true)
        } else {
            logger.s(TAG, "Wi-Fi discharge window ended. Disengaging bulk AMQP discharger & resuming Real-Time SIM stream...")
            batchDischarger.onWifiWindowActive(false)
            realtimeTransmitter.resume()
        }
    }

    /**
     * Purge synced records from SQLite Room database.
     */
    fun purgeSyncedRecords(onResult: ((Int) -> Unit)? = null) {
        managerScope.launch {
            val count = batchDischarger.purgeSyncedRecords()
            onResult?.invoke(count)
        }
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        logger.w(TAG, "Stopping Cloud & Messaging Layer...")
        realtimeTransmitter.stop()
        batchDischarger.stop()
    }

    companion object {
        private const val TAG = "AmqpCloudManager"

        @Volatile
        private var INSTANCE: AmqpCloudManager? = null

        @JvmStatic
        fun getInstance(context: Context): AmqpCloudManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AmqpCloudManager(context).also { INSTANCE = it }
            }
        }
    }
}
