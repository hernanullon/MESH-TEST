package com.example.data.local

import android.content.Context
import com.example.model.TcpPacket
import com.example.model.telemetry.DeviceStatusTelemetry
import com.example.model.telemetry.InertialTelemetry
import com.example.model.telemetry.LocationTelemetry
import com.example.service.MeshStateManager
import com.example.service.ScheduleManager
import com.example.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe, high-throughput ingestion repository and buffer for local Room SQLite persistence.
 * Stores raw independent JSON records per sensor group (LOCATION, INERTIAL, DEVICE_STATUS, EXTERNAL_TCP).
 * Each group has its own distinct type field in its JSON payload and operates at its own independent sampling rate.
 *
 * STORE-AND-FORWARD ARCHITECTURAL INHIBITION:
 * During the window where Wi-Fi is active (discharge / AMQP sync phase), insertion of new records
 * into the local SQLite buffer is strictly paused/inhibited to prevent endless loop collisions
 * between the purging process and new incoming samples.
 */
class TelemetryBufferRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val dao = database.telemetryDao()
    private val logger = AppLogger.getInstance()
    private val stateManager = MeshStateManager.getInstance()

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ingestionChannel = Channel<TelemetryRecordEntity>(capacity = 10000)

    // Cached Counts for on-demand inspection (no continuous re-querying overhead)
    private val _totalBufferedCount = MutableStateFlow(0)
    val totalBufferedCount: StateFlow<Int> = _totalBufferedCount.asStateFlow()

    private val _unsyncedBufferedCount = MutableStateFlow(0)
    val unsyncedBufferedCount: StateFlow<Int> = _unsyncedBufferedCount.asStateFlow()

    private val _locationCount = MutableStateFlow(0)
    val locationCount: StateFlow<Int> = _locationCount.asStateFlow()

    private val _inertialCount = MutableStateFlow(0)
    val inertialCount: StateFlow<Int> = _inertialCount.asStateFlow()

    private val _deviceStatusCount = MutableStateFlow(0)
    val deviceStatusCount: StateFlow<Int> = _deviceStatusCount.asStateFlow()

    private val _externalTcpCount = MutableStateFlow(0)
    val externalTcpCount: StateFlow<Int> = _externalTcpCount.asStateFlow()

    private val _lastBufferedRecord = MutableStateFlow<TelemetryRecordEntity?>(null)
    val lastBufferedRecord: StateFlow<TelemetryRecordEntity?> = _lastBufferedRecord.asStateFlow()

    // Flag indicating if buffering is paused due to Wi-Fi active discharge window
    private val _isBufferingInhibited = MutableStateFlow(false)
    val isBufferingInhibited: StateFlow<Boolean> = _isBufferingInhibited.asStateFlow()

    private val insertionCounter = AtomicLong(0)

    init {
        // Start background worker to ingest from channel in micro-batches
        startIngestionWorker()
        refreshCounters()
    }

    /**
     * Checks if the Wi-Fi interface is currently active or in the scheduled discharge window.
     * When Wi-Fi is ON, database ingestion MUST be inhibited to allow clean flushing to AMQP/Cloud.
     */
    fun isWifiDischargeWindowActive(): Boolean {
        return try {
            // 1. Check direct hardware state reported by WifiController / MeshStateManager
            if (stateManager.isWifiHardwareEnabled) {
                return true
            }

            // 2. Check current time evaluation in ScheduleManager if active
            val sched = ScheduleManager.getInstance()
            val config = sched.config
            if (config != null && config.isConfigured && config.isWifiScheduleEnabled) {
                val now = java.util.Calendar.getInstance()
                val dayOfWeek = now.get(java.util.Calendar.DAY_OF_WEEK)
                val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
                val minute = now.get(java.util.Calendar.MINUTE)
                if (config.isDayActive(dayOfWeek) && config.shouldWifiBeActive(hour, minute)) {
                    return true
                }
            }
            false
        } catch (t: Throwable) {
            false
        }
    }

    private fun startIngestionWorker() {
        repositoryScope.launch {
            val batch = mutableListOf<TelemetryRecordEntity>()
            while (true) {
                val item = ingestionChannel.receive()
                batch.add(item)

                // Drain all immediately available items up to batch size 50
                while (batch.size < 50) {
                    val poll = ingestionChannel.tryReceive().getOrNull() ?: break
                    batch.add(poll)
                }

                try {
                    dao.insertBatch(batch)
                    val count = insertionCounter.addAndGet(batch.size.toLong())

                    // Periodic circular retention trim every 1000 insertions to prevent flash wear
                    if (count % 1000 == 0L) {
                        dao.trimOldRecords(50000) // Keep max 50,000 records
                    }
                } catch (t: Throwable) {
                    logger.e(TAG, "Error inserting batch into Room: ${t.message}")
                } finally {
                    batch.clear()
                }
            }
        }
    }

    /**
     * Ingest an independent Location (GPS) raw JSON record.
     * Contains "type": "LOCATION" and "device_id" inside the JSON payload.
     * INHIBITED if Wi-Fi discharge window is active.
     */
    fun bufferLocation(deviceId: String, location: LocationTelemetry) {
        if (checkAndHandleInhibition()) {
            return
        }

        val devId = deviceId.ifEmpty { "LOCAL-NODE" }
        val json = JSONObject().apply {
            put("type", "LOCATION")
            put("device_id", devId)
            put("timestamp", location.timestamp)
            put("data", location.toJson())
        }.toString()

        val entity = TelemetryRecordEntity(
            sourceType = "LOCATION",
            deviceId = devId,
            packetType = "LOCATION",
            timestamp = location.timestamp,
            payloadJson = json,
            isSynced = false
        )
        queueForIngestion(entity)
    }

    /**
     * Ingest an independent Inertial (IMU) raw JSON record.
     * Contains "type": "INERTIAL" and "device_id" inside the JSON payload.
     * INHIBITED if Wi-Fi discharge window is active.
     */
    fun bufferInertial(deviceId: String, inertial: InertialTelemetry) {
        if (checkAndHandleInhibition()) {
            return
        }

        val devId = deviceId.ifEmpty { "LOCAL-NODE" }
        val json = JSONObject().apply {
            put("type", "INERTIAL")
            put("device_id", devId)
            put("timestamp", inertial.timestamp)
            put("data", inertial.toJson())
        }.toString()

        val entity = TelemetryRecordEntity(
            sourceType = "INERTIAL",
            deviceId = devId,
            packetType = "INERTIAL",
            timestamp = inertial.timestamp,
            payloadJson = json,
            isSynced = false
        )
        queueForIngestion(entity)
    }

    /**
     * Ingest an independent Device Status (Battery/RAM/Thermal) raw JSON record.
     * Contains "type": "DEVICE_STATUS" and "device_id" inside the JSON payload.
     * INHIBITED if Wi-Fi discharge window is active.
     */
    fun bufferDeviceStatus(deviceId: String, deviceStatus: DeviceStatusTelemetry) {
        if (checkAndHandleInhibition()) {
            return
        }

        val devId = deviceId.ifEmpty { "LOCAL-NODE" }
        val json = JSONObject().apply {
            put("type", "DEVICE_STATUS")
            put("device_id", devId)
            put("timestamp", deviceStatus.timestamp)
            put("data", deviceStatus.toJson())
        }.toString()

        val entity = TelemetryRecordEntity(
            sourceType = "DEVICE_STATUS",
            deviceId = devId,
            packetType = "DEVICE_STATUS",
            timestamp = deviceStatus.timestamp,
            payloadJson = json,
            isSynced = false
        )
        queueForIngestion(entity)
    }

    /**
     * Ingest an external TCP packet received from another mesh peer or ESP32 node.
     * INHIBITED if Wi-Fi discharge window is active.
     */
    fun bufferExternalTcpPacket(sourceEndpoint: String, packet: TcpPacket) {
        if (checkAndHandleInhibition()) {
            return
        }

        val sender = packet.senderId ?: ""
        val devId = if (sender.isNotEmpty()) sender else sourceEndpoint
        val entity = TelemetryRecordEntity(
            sourceType = "EXTERNAL_TCP",
            deviceId = devId,
            packetType = packet.type?.name ?: "DATA",
            timestamp = packet.timestamp,
            payloadJson = packet.toJson(),
            isSynced = false
        )
        queueForIngestion(entity)
    }

    /**
     * Ingest a generic raw telemetry JSON payload.
     * INHIBITED if Wi-Fi discharge window is active.
     */
    fun bufferRawPayload(sourceType: String, deviceId: String, packetType: String, json: String, timestamp: Long) {
        if (checkAndHandleInhibition()) {
            return
        }

        val entity = TelemetryRecordEntity(
            sourceType = sourceType,
            deviceId = deviceId,
            packetType = packetType,
            timestamp = timestamp,
            payloadJson = json,
            isSynced = false
        )
        queueForIngestion(entity)
    }

    /**
     * Helper to verify if Wi-Fi discharge is running, updating UI state flow and inhibiting insertion.
     * Returns true if insertion should be BLOCKED.
     */
    private fun checkAndHandleInhibition(): Boolean {
        val inhibited = isWifiDischargeWindowActive()
        if (_isBufferingInhibited.value != inhibited) {
            _isBufferingInhibited.value = inhibited
            if (inhibited) {
                logger.i(TAG, "SQLite persistence paused: Active Wi-Fi discharge window in progress.")
            } else {
                logger.i(TAG, "SQLite persistence resumed: Wi-Fi disabled / Collection mode active.")
            }
        }
        return inhibited
    }

    private fun queueForIngestion(entity: TelemetryRecordEntity) {
        val result = ingestionChannel.trySend(entity)
        if (!result.isSuccess) {
            repositoryScope.launch {
                ingestionChannel.send(entity)
            }
        }
    }

    /**
     * On-demand counter refresh (triggered on button click or screen enter)
     */
    fun refreshCounters() {
        repositoryScope.launch {
            refreshCountersDirect()
        }
    }

    private suspend fun refreshCountersDirect() {
        try {
            _isBufferingInhibited.value = isWifiDischargeWindowActive()

            val total = dao.getTotalCountDirect()
            val unsynced = dao.getUnsyncedCountDirect()
            val loc = dao.getCountBySourceTypeDirect("LOCATION")
            val imu = dao.getCountBySourceTypeDirect("INERTIAL")
            val dev = dao.getCountBySourceTypeDirect("DEVICE_STATUS")
            val tcp = dao.getCountBySourceTypeDirect("EXTERNAL_TCP")

            _totalBufferedCount.value = total
            _unsyncedBufferedCount.value = unsynced
            _locationCount.value = loc
            _inertialCount.value = imu
            _deviceStatusCount.value = dev
            _externalTcpCount.value = tcp
        } catch (t: Throwable) {
            logger.w(TAG, "Error refreshing buffer counters: ${t.message}")
        }
    }

    // Direct Batch Operations (for UI inspection & future AMQP Cloud Sync)
    suspend fun getRecentRecordsDirect(limit: Int = 50): List<TelemetryRecordEntity> {
        return dao.getRecentRecordsDirect(limit)
    }

    suspend fun getUnsyncedBatch(limit: Int = 100): List<TelemetryRecordEntity> {
        return dao.getUnsyncedBatch(limit)
    }

    suspend fun markAsSynced(ids: List<Long>): Int {
        val updated = dao.markAsSynced(ids)
        refreshCountersDirect()
        return updated
    }

    suspend fun purgeSyncedRecords(): Int {
        val deleted = dao.deleteSyncedRecords()
        refreshCountersDirect()
        return deleted
    }

    suspend fun clearAllBuffer(): Int {
        val deleted = dao.clearAll()
        refreshCountersDirect()
        return deleted
    }

    companion object {
        private const val TAG = "TelemetryBufferRepo"

        @Volatile
        private var INSTANCE: TelemetryBufferRepository? = null

        @JvmStatic
        fun getInstance(context: Context): TelemetryBufferRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TelemetryBufferRepository(context).also { INSTANCE = it }
            }
        }

        @JvmStatic
        fun get(): TelemetryBufferRepository? {
            return INSTANCE
        }
    }
}
