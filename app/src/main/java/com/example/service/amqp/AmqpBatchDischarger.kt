package com.example.service.amqp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.data.local.TelemetryBufferRepository
import com.example.data.local.TelemetryRecordEntity
import com.example.utils.AppLogger
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Offline Bulk Telemetry Discharger over Mandatory and Exclusive Wi-Fi with Publisher Confirms.
 *
 * PROTOCOL GUARANTEES:
 * 1. Forced Wi-Fi Binding: Binds sockets strictly to the Wi-Fi interface (TRANSPORT_WIFI).
 * 2. Publisher Confirms (confirmSelect()): Ensures zero data loss by requiring broker ACKs before marking synced.
 * 3. Batch Ingestion: Queries local Room SQLite buffer in chunks (e.g. 50-100 records).
 * 4. Store-and-Forward Safety: Operates during scheduled or on-demand Wi-Fi active windows.
 */
class AmqpBatchDischarger(
    private val context: Context,
    private val bufferRepository: TelemetryBufferRepository
) {

    private val logger = AppLogger.getInstance()
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val dischargerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var dischargeJob: Job? = null

    private val _stats = MutableStateFlow(BatchStats())
    val stats: StateFlow<BatchStats> = _stats.asStateFlow()

    @Volatile
    private var currentWifiNetwork: Network? = null

    private var sharedExecutor: ExecutorService? = null

    private val isDischarging = AtomicBoolean(false)
    private val shouldRun = AtomicBoolean(false)

    private val totalDischargedCounter = AtomicLong(0)
    private val confirmsReceivedCounter = AtomicLong(0)
    private val confirmsFailedCounter = AtomicLong(0)

    @Volatile
    private var connectionParams: AmqpConnectionParams = AmqpConnectionParams.fromScheduleConfig(null)

    // Wi-Fi Network Callback
    private val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager?.getNetworkCapabilities(network)
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            logger.s(TAG, "Wi-Fi network AVAILABLE for bulk AMQP discharge (id=$network, isWifi=$isWifi)")

            currentWifiNetwork = network
            _stats.value = _stats.value.copy(
                wifiAvailable = true,
                state = if (isDischarging.get()) BatchDischargeState.CONNECTING else BatchDischargeState.IDLE
            )

            // Auto-trigger discharge if Wi-Fi window is active and there are pending records
            if (shouldRun.get() && !isDischarging.get()) {
                triggerBatchDischarge()
            }
        }

        override fun onLost(network: Network) {
            logger.w(TAG, "Wi-Fi network LOST for bulk AMQP discharge (id=$network)")
            if (currentWifiNetwork == network) {
                currentWifiNetwork = null
            }
            _stats.value = _stats.value.copy(
                wifiAvailable = false,
                state = BatchDischargeState.AWAITING_WIFI
            )
        }

        override fun onUnavailable() {
            logger.w(TAG, "Wi-Fi network UNAVAILABLE")
            _stats.value = _stats.value.copy(
                wifiAvailable = false,
                state = BatchDischargeState.AWAITING_WIFI
            )
        }
    }

    fun start(params: AmqpConnectionParams) {
        this.connectionParams = params
        shouldRun.set(true)
        logger.i(TAG, "Starting AMQP Batch Discharger (Exclusive Wi-Fi)...")

        registerWifiNetwork()
        refreshPendingCount()
    }

    fun updateParams(params: AmqpConnectionParams) {
        this.connectionParams = params
    }

    fun refreshPendingCount() {
        dischargerScope.launch {
            try {
                val pending = bufferRepository.unsyncedBufferedCount.value
                _stats.value = _stats.value.copy(pendingRecordsInDb = pending)
            } catch (ignored: Throwable) {}
        }
    }

    /**
     * Manually triggers bulk discharge of all unsynced records via Wi-Fi.
     */
    fun triggerBatchDischarge() {
        if (!shouldRun.get()) return
        if (isDischarging.get()) {
            logger.i(TAG, "Batch discharge already in progress.")
            return
        }

        dischargeJob?.cancel()
        dischargeJob = dischargerScope.launch {
            executeDischargeSession()
        }
    }

    private fun registerWifiNetwork() {
        if (connectivityManager == null) return

        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            _stats.value = _stats.value.copy(state = BatchDischargeState.AWAITING_WIFI)
            connectivityManager.requestNetwork(request, wifiNetworkCallback)
            logger.i(TAG, "Wi-Fi network request registered for bulk discharge.")
        } catch (t: Throwable) {
            logger.e(TAG, "Failed to register Wi-Fi network request: ${t.message}")
            _stats.value = _stats.value.copy(
                lastError = "Wi-Fi registration error: ${t.message}",
                state = BatchDischargeState.ERROR
            )
        }
    }

    private suspend fun executeDischargeSession() {
        if (!isDischarging.compareAndSet(false, true)) return

        _stats.value = _stats.value.copy(
            isDischarging = true,
            state = BatchDischargeState.CONNECTING
        )

        var connection: Connection? = null
        var channel: Channel? = null

        try {
            val network = currentWifiNetwork
            if (network == null) {
                logger.w(TAG, "Cannot discharge: Wi-Fi network not connected or has no internet.")
                _stats.value = _stats.value.copy(
                    state = BatchDischargeState.AWAITING_WIFI,
                    lastError = "Awaiting active Wi-Fi connection"
                )
                return
            }

            logger.s(TAG, "Connecting to RabbitMQ over Wi-Fi for bulk discharge...")

            val factory = ConnectionFactory().apply {
                host = connectionParams.host
                port = connectionParams.port
                virtualHost = connectionParams.virtualHost
                username = connectionParams.username
                password = connectionParams.password

                // Hardware-enforced Wi-Fi socket binding
                socketFactory = BoundNetworkSocketFactory(network = network, timeoutMs = 10000)

                connectionTimeout = 10000
                handshakeTimeout = 10000
                requestedHeartbeat = 15
                isAutomaticRecoveryEnabled = false

                if (connectionParams.sslEnabled) {
                    useSslProtocol()
                }

                if (sharedExecutor == null || sharedExecutor!!.isShutdown) {
                    sharedExecutor = Executors.newSingleThreadExecutor { r ->
                        Thread(r, "AmqpBatchDischarger").apply { isDaemon = true }
                    }
                }
                setSharedExecutor(sharedExecutor)
            }

            connection = factory.newConnection("LocalMesh-BatchDischarge-${connectionParams.deviceId}")
            channel = connection.createChannel()

            // Enable Publisher Confirms!
            channel.confirmSelect()

            val exchange = connectionParams.exchange

            // Passive check or declare exchange
            try {
                channel.exchangeDeclarePassive(exchange)
            } catch (t: Throwable) {
                logger.i(TAG, "Declaring exchange '$exchange'...")
                try {
                    val freshCh = connection.createChannel()
                    freshCh.exchangeDeclare(exchange, "direct", true)
                } catch (ignored: Throwable) {}
            }

            logger.s(TAG, "Connected to RabbitMQ for bulk discharge with Publisher Confirms enabled.")

            val batchSize = 50
            var hasMore = true

            while (hasMore && shouldRun.get() && dischargerScope.isActive) {
                val unsyncedList: List<TelemetryRecordEntity> = bufferRepository.getUnsyncedBatch(batchSize)
                if (unsyncedList.isEmpty()) {
                    hasMore = false
                    break
                }

                _stats.value = _stats.value.copy(
                    state = BatchDischargeState.DISCHARGING,
                    currentBatchSize = unsyncedList.size
                )

                logger.i(TAG, "Publishing chunk of ${unsyncedList.size} records with Publisher Confirms...")

                // Publish each record in batch
                for (i in unsyncedList.indices) {
                    val record = unsyncedList[i]
                    val payloadBytes = record.payloadJson.toByteArray(Charsets.UTF_8)
                    
                    // Determine exact sub-type for queue routing (e.g. location, inertial, device, can, data)
                    val subType = resolveRecordSubType(record)
                    val specificRoutingKey = connectionParams.getBatchRoutingKey(subType)

                    val props = AMQP.BasicProperties.Builder()
                        .deliveryMode(2) // Persistent delivery
                        .contentType("application/json")
                        .type(subType)
                        .messageId(record.id.toString())
                        .timestamp(Date(record.timestamp))
                        .headers(mapOf(
                            "device_id" to record.deviceId,
                            "packet_type" to record.packetType.lowercase(),
                            "source_type" to record.sourceType.lowercase(),
                            "type" to subType
                        ))
                        .build()

                    channel.basicPublish(exchange, specificRoutingKey, props, payloadBytes)
                }

                _stats.value = _stats.value.copy(state = BatchDischargeState.CONFIRMING)

                // Wait for broker ACKs (Publisher Confirms)
                val confirmed = channel.waitForConfirms(12000)

                if (confirmed) {
                    val ids: List<Long> = unsyncedList.map { entity -> entity.id }
                    bufferRepository.markAsSynced(ids)

                    val total = totalDischargedCounter.addAndGet(unsyncedList.size.toLong())
                    confirmsReceivedCounter.addAndGet(unsyncedList.size.toLong())

                    // Purge confirmed records from SQLite Room database so local storage is released and counters updated
                    val purged = bufferRepository.purgeSyncedRecords()

                    _stats.value = _stats.value.copy(
                        totalRecordsDischarged = total,
                        confirmsReceived = confirmsReceivedCounter.get(),
                        lastDischargeTimestamp = System.currentTimeMillis()
                    )

                    logger.s(TAG, "Batch of ${unsyncedList.size} records ACKed by broker and purged from SQLite ($purged deleted). (Total: $total)")
                } else {
                    confirmsFailedCounter.addAndGet(unsyncedList.size.toLong())
                    _stats.value = _stats.value.copy(
                        confirmsFailed = confirmsFailedCounter.get(),
                        lastError = "Broker NACK or confirm timeout on batch"
                    )
                    logger.w(TAG, "Publisher confirms failed or timed out for batch. Records remain unsynced for retry.")
                    break
                }

                // Small pause to avoid starving I/O
                delay(50)
            }

            refreshPendingCount()
            _stats.value = _stats.value.copy(
                state = BatchDischargeState.COMPLETED,
                lastError = null
            )
            logger.s(TAG, "Batch discharge session finished cleanly.")
        } catch (t: Throwable) {
            val rawMsg = t.message ?: t.javaClass.simpleName
            val classifiedError = AmqpErrorClassifier.classifyBatchError(t)
            logger.e(TAG, "Batch discharge failed ($classifiedError): $rawMsg")
            _stats.value = _stats.value.copy(
                state = BatchDischargeState.ERROR,
                lastError = classifiedError
            )
        } finally {
            try {
                channel?.close()
            } catch (ignored: Throwable) {}
            try {
                connection?.abort(1500)
            } catch (ignored: Throwable) {}

            isDischarging.set(false)
            _stats.value = _stats.value.copy(isDischarging = false)
            refreshPendingCount()
        }
    }

    /**
     * Purge synced records from SQLite Room database to free storage after discharge.
     */
    suspend fun purgeSyncedRecords(): Int {
        val deleted = bufferRepository.purgeSyncedRecords()
        refreshPendingCount()
        logger.i(TAG, "Purged $deleted synced records from Room buffer.")
        return deleted
    }

    /**
     * Encerar los contadores de discharge y broker (discharged, ACKs, NACKs)
     * al finalizar cada ventana de descarga de datos Wi-Fi.
     */
    fun resetWindowCounters() {
        totalDischargedCounter.set(0)
        confirmsReceivedCounter.set(0)
        confirmsFailedCounter.set(0)
        _stats.value = _stats.value.copy(
            totalRecordsDischarged = 0,
            confirmsReceived = 0,
            confirmsFailed = 0
        )
        refreshPendingCount()
        logger.i(TAG, "Bulk Discharger: Contadores de discharge y broker encerados a 0 al cumplirse la ventana Wi-Fi.")
    }

    fun stop() {
        shouldRun.set(false)
        logger.w(TAG, "Stopping AMQP Batch Discharger...")

        try {
            connectivityManager?.unregisterNetworkCallback(wifiNetworkCallback)
        } catch (ignored: Throwable) {}

        dischargeJob?.cancel()

        sharedExecutor?.shutdownNow()
        sharedExecutor = null

        _stats.value = BatchStats(state = BatchDischargeState.IDLE)
    }

    private fun resolveRecordSubType(record: TelemetryRecordEntity): String {
        // 1. Try to extract "type" directly from JSON payload if present
        try {
            val json = org.json.JSONObject(record.payloadJson)
            val jsonType = json.optString("type", "").trim().lowercase()
            if (jsonType.isNotEmpty() && jsonType != "batch" && jsonType != "realtime") {
                return jsonType
            }
        } catch (ignored: Throwable) {}

        // 2. Fallback to record metadata
        val src = record.sourceType.trim().uppercase()
        val pkt = record.packetType.trim().lowercase()

        return when {
            src == "LOCATION" -> "location"
            src == "INERTIAL" -> "inertial"
            src == "DEVICE" || src == "DEVICE_STATUS" -> "device"
            pkt.isNotEmpty() -> pkt
            src.isNotEmpty() -> src.lowercase()
            else -> "batch"
        }
    }

    companion object {
        private const val TAG = "AmqpBatchDischarger"
    }
}
