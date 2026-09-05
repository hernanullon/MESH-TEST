package com.example.data.local

import android.content.Context
import com.example.model.TcpPacket
import com.example.model.telemetry.DeviceStatusTelemetry
import com.example.model.telemetry.InertialTelemetry
import com.example.model.telemetry.LocationTelemetry
import com.example.service.ScheduleManager
import com.example.service.amqp.AmqpBatchDischarger
import com.example.utils.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Repository for autonomous local telemetry persistence in SQLite (Room).
 * Stores raw flat JSON records for each telemetry collection (location, inertial, device, external sensors).
 *
 * Rules:
 * 1. ts = system ingestion time (System.currentTimeMillis())
 * 2. timestamp = hardware / sensor provider time
 * 3. All JSON objects are strictly single-level (FLAT)
 * 4. External sensors: preserve existing JSON fields, strictly inject/override device_id and ts
 * 5. Wi-Fi Ingestion Inhibition: when Wi-Fi discharge window is active, buffering is paused to ensure bandwidth
 */
class TelemetryBufferRepository private constructor(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val dao = db.telemetryDao()
    private val logger = AppLogger.getInstance()

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Memory Ingestion Queue for High-Frequency Sensor Bursts
    private val ingestionQueue = ConcurrentLinkedQueue<TelemetryRecordEntity>()

    // Observable StateFlows for UI Dashboards
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

    private val _isBufferingInhibited = MutableStateFlow(false)
    val isBufferingInhibited: StateFlow<Boolean> = _isBufferingInhibited.asStateFlow()

    private var batchDrainJob: Job? = null
    private var isDraining = false

    init {
        startBatchDrainLoop()
        refreshCounters()
    }

    /**
     * Determines whether local buffering should be INHIBITED.
     * Buffering is inhibited if the scheduled Wi-Fi discharge window is currently active.
     */
    fun isWifiDischargeWindowActive(): Boolean {
        return try {
            val sched = ScheduleManager.getInstance()
            sched.isDischargeWindowActive
        } catch (t: Throwable) {
            false
        }
    }

    private fun checkAndHandleInhibition(): Boolean {
        val inhibited = isWifiDischargeWindowActive()
        if (inhibited != _isBufferingInhibited.value) {
            _isBufferingInhibited.value = inhibited
            if (inhibited) {
                logger.w(TAG, "Wi-Fi discharge window active: Local telemetry buffering is INHIBITED.")
            } else {
                logger.i(TAG, "Wi-Fi discharge window inactive: Resuming local telemetry buffering.")
            }
        }
        return inhibited
    }

    /**
     * Periodic background worker draining the concurrent memory queue to SQLite.
     * Flushes records every 500ms or when the queue exceeds 25 records.
     */
    private fun startBatchDrainLoop() {
        batchDrainJob?.cancel()
        batchDrainJob = repositoryScope.launch {
            while (isActive) {
                try {
                    delay(500)
                    drainQueueToDatabase()
                } catch (ce: CancellationException) {
                    break
                } catch (t: Throwable) {
                    logger.w(TAG, "Exception in SQLite buffer drain loop: ${t.message}")
                }
            }
        }
    }

    private suspend fun drainQueueToDatabase() {
        if (ingestionQueue.isEmpty() || isDraining) return
        isDraining = true

        val batchToInsert = mutableListOf<TelemetryRecordEntity>()
        var item = ingestionQueue.poll()
        while (item != null && batchToInsert.size < 100) {
            batchToInsert.add(item)
            item = ingestionQueue.poll()
        }

        if (batchToInsert.isNotEmpty()) {
            try {
                dao.insertBatch(batchToInsert)
                refreshCountersDirect()
            } catch (t: Throwable) {
                logger.e(TAG, "Failed inserting batch of ${batchToInsert.size} records into SQLite: ${t.message}")
                // Re-queue items at head if feasible
                ingestionQueue.addAll(batchToInsert)
            }
        }

        isDraining = false
    }

    private fun queueForIngestion(entity: TelemetryRecordEntity) {
        ingestionQueue.offer(entity)
        if (ingestionQueue.size >= 25) {
            repositoryScope.launch {
                drainQueueToDatabase()
            }
        }
    }

    /**
     * Ingest a flat Location (GPS) raw JSON record:
     * - type: "location"
     * - device_id: configured device id
     * - ts: system ingestion time (System.currentTimeMillis())
     * - lat, lon, alt, speed, bearing, accuracy, satellites, provider, has_fix
     * - timestamp: satellite/provider fix time
     */
    fun bufferLocation(deviceId: String, location: LocationTelemetry) {
        if (checkAndHandleInhibition()) {
            return
        }

        val systemTs = System.currentTimeMillis()
        val devId = deviceId.ifEmpty { "LOCAL-NODE" }

        val json = JSONObject().apply {
            put("type", "location")
            put("device_id", devId)
            put("ts", systemTs)
            location.populateFlatJson(this)
        }.toString()

        val entity = TelemetryRecordEntity(
            sourceType = "LOCATION",
            deviceId = devId,
            packetType = "LOCATION",
            timestamp = systemTs,
            payloadJson = json,
            isSynced = false
        )
        queueForIngestion(entity)
    }

    /**
     * Ingest a flat Inertial (IMU) raw JSON record:
     * - type: "inertial"
     * - device_id: configured device id
     * - ts: system ingestion time (System.currentTimeMillis())
     * - accx, accy, accz, acc_mag, acc_available
     * - gyrx, gyry, gyrz, gyr_available
     * - magx, magy, magz, mag_available
     * - pitch, roll, yaw, pry_available
     * - timestamp: sensor hardware time
     */
    fun bufferInertial(deviceId: String, inertial: InertialTelemetry) {
        if (checkAndHandleInhibition()) {
            return
        }

        val systemTs = System.currentTimeMillis()
        val devId = deviceId.ifEmpty { "LOCAL-NODE" }

        val json = JSONObject().apply {
            put("type", "inertial")
            put("device_id", devId)
            put("ts", systemTs)
            inertial.populateFlatJson(this)
        }.toString()

        val entity = TelemetryRecordEntity(
            sourceType = "INERTIAL",
            deviceId = devId,
            packetType = "INERTIAL",
            timestamp = systemTs,
            payloadJson = json,
            isSynced = false
        )
        queueForIngestion(entity)
    }

    /**
     * Ingest a flat Device Status raw JSON record:
     * - type: "device"
     * - device_id: configured device id
     * - ts: system ingestion time (System.currentTimeMillis())
     * - soc, is_charging, temp_c, voltage_mv, health
     * - free_ram_mb, total_ram_mb, ram_usage_pct, free_storage_gb, total_storage_gb
     * - timestamp: sampling hardware time
     */
    fun bufferDeviceStatus(deviceId: String, deviceStatus: DeviceStatusTelemetry) {
        if (checkAndHandleInhibition()) {
            return
        }

        val systemTs = System.currentTimeMillis()
        val devId = deviceId.ifEmpty { "LOCAL-NODE" }

        val json = JSONObject().apply {
            put("type", "device")
            put("device_id", devId)
            put("ts", systemTs)
            deviceStatus.populateFlatJson(this)
        }.toString()

        val entity = TelemetryRecordEntity(
            sourceType = "DEVICE",
            deviceId = devId,
            packetType = "DEVICE",
            timestamp = systemTs,
            payloadJson = json,
            isSynced = false
        )
        queueForIngestion(entity)
    }

    /**
     * Ingest an external TCP packet received from another mesh peer or ESP32 node.
     * Rule: Takes the JSON received from the external device and adds/overrides "device_id" and "ts".
     * Preserves all other incoming fields (e.g. type, temperature, humidity, timestamp, etc.).
     */
    fun bufferExternalTcpPacket(sourceEndpoint: String, packet: TcpPacket) {
        if (checkAndHandleInhibition()) {
            return
        }

        val effectiveType = packet.effectiveType.trim().lowercase()
        // If type is empty or internal protocol control packet, skip
        if (effectiveType.isEmpty() || effectiveType == "ping" || effectiveType == "pong" || effectiveType == "ack") {
            return
        }

        val configuredDeviceId = try {
            val cfg = ScheduleManager.getInstance().config?.deviceId?.trim()
            if (!cfg.isNullOrEmpty()) cfg else "device_001"
        } catch (t: Throwable) {
            "device_001"
        }

        val systemTs = System.currentTimeMillis()

        // Base JSON parsed directly from external payload or packet
        val finalPayloadJson = try {
            val rawPayload = packet.payload.trim()
            val baseObj = if (rawPayload.startsWith("{") && rawPayload.endsWith("}")) {
                JSONObject(rawPayload)
            } else {
                JSONObject(packet.toJson())
            }

            // Strictly inject/override device_id and ts
            baseObj.put("device_id", configuredDeviceId)
            baseObj.put("ts", systemTs)

            // Ensure type is explicitly present
            if (!baseObj.has("type") || baseObj.optString("type", "").isEmpty()) {
                baseObj.put("type", effectiveType)
            }

            baseObj.toString()
        } catch (t: Throwable) {
            // Fallback JSON in case of formatting anomalies
            val fallback = JSONObject()
            fallback.put("type", effectiveType)
            fallback.put("device_id", configuredDeviceId)
            fallback.put("ts", systemTs)
            fallback.put("payload", packet.payload)
            fallback.put("timestamp", packet.timestamp)
            fallback.toString()
        }

        val entity = TelemetryRecordEntity(
            sourceType = "EXTERNAL_TCP",
            deviceId = configuredDeviceId,
            packetType = effectiveType.uppercase(),
            timestamp = systemTs,
            payloadJson = finalPayloadJson,
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
            val loc = dao.getCountBySourceTypesDirect(listOf("LOCATION"))
            val imu = dao.getCountBySourceTypesDirect(listOf("INERTIAL"))
            val dev = dao.getCountBySourceTypesDirect(listOf("DEVICE", "DEVICE_STATUS"))
            val tcp = dao.getCountBySourceTypesDirect(listOf("EXTERNAL_TCP"))

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

    suspend fun getUnsyncedBatch(limit: Int = 50): List<TelemetryRecordEntity> {
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

    suspend fun clearBufferDirect() {
        dao.clearAll()
        refreshCountersDirect()
        logger.i(TAG, "All local buffer telemetry records erased.")
    }

    suspend fun deleteSyncedRecordsDirect(): Int {
        return purgeSyncedRecords()
    }

    companion object {
        private const val TAG = "TelemetryBufferRepo"

        @Volatile
        private var instance: TelemetryBufferRepository? = null

        @JvmStatic
        fun getInstance(context: Context): TelemetryBufferRepository {
            return instance ?: synchronized(this) {
                instance ?: TelemetryBufferRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
