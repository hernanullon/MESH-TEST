package com.example.data.local

import android.content.Context
import com.example.model.TcpPacket
import com.example.model.telemetry.DeviceStatusTelemetry
import com.example.model.telemetry.InertialTelemetry
import com.example.model.telemetry.LocationTelemetry
import com.example.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
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
 */
class TelemetryBufferRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(appContext)
    private val dao = database.telemetryDao()
    private val logger = AppLogger.getInstance()

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

    private val insertionCounter = AtomicLong(0)

    init {
        // Start background worker to ingest from channel in micro-batches
        startIngestionWorker()
        refreshCounters()
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
                    val last = batch.lastOrNull()
                    if (last != null) {
                        _lastBufferedRecord.value = last
                    }
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
     */
    fun bufferLocation(deviceId: String, location: LocationTelemetry) {
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
     */
    fun bufferInertial(deviceId: String, inertial: InertialTelemetry) {
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
     */
    fun bufferDeviceStatus(deviceId: String, deviceStatus: DeviceStatusTelemetry) {
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
     * Ingest an external TCP packet received from another mesh peer or ESP32 node
     */
    fun bufferExternalTcpPacket(sourceEndpoint: String, packet: TcpPacket) {
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
     * Ingest a generic raw telemetry JSON payload
     */
    fun bufferRawPayload(sourceType: String, deviceId: String, packetType: String, json: String, timestamp: Long) {
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
