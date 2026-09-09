package com.example.service.amqp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import com.example.data.local.TelemetryBufferRepository
import com.example.data.local.TelemetryRecordEntity
import com.example.utils.AppLogger
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.ConfirmListener
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Date
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
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

    // Power & Wi-Fi locks to maintain transmission throughput when phone screen is locked and off
    private var dischargeWakeLock: PowerManager.WakeLock? = null
    private var dischargeWifiLock: WifiManager.WifiLock? = null

    private fun acquireDischargeLocks() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && (dischargeWakeLock == null || dischargeWakeLock?.isHeld == false)) {
                dischargeWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartBus:BatchDischargeWakeLock").apply {
                    setReferenceCounted(false)
                    acquire(15 * 60 * 1000L) // 15 minutes safety timeout
                }
                logger.i(TAG, "[Power] BatchDischarge WakeLock adquirido.")
            }
        } catch (t: Throwable) {
            logger.w(TAG, "Error adquiriendo discharge WakeLock: ${t.message}")
        }

        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && (dischargeWifiLock == null || dischargeWifiLock?.isHeld == false)) {
                dischargeWifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "SmartBus:BatchDischargeWifiLock")
                } else {
                    wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SmartBus:BatchDischargeWifiLock")
                }.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                logger.i(TAG, "[Power] BatchDischarge WifiLock (HIGH_PERF) adquirido.")
            }
        } catch (t: Throwable) {
            logger.w(TAG, "Error adquiriendo discharge WifiLock: ${t.message}")
        }
    }

    private fun releaseDischargeLocks() {
        try {
            if (dischargeWakeLock?.isHeld == true) {
                dischargeWakeLock?.release()
                logger.i(TAG, "[Power] BatchDischarge WakeLock liberado.")
            }
        } catch (ignored: Throwable) {}
        try {
            if (dischargeWifiLock?.isHeld == true) {
                dischargeWifiLock?.release()
                logger.i(TAG, "[Power] BatchDischarge WifiLock liberado.")
            }
        } catch (ignored: Throwable) {}
    }

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
            runDischargeSupervisorLoop()
        }
    }

    private fun registerWifiNetwork() {
        if (connectivityManager == null) return

        try {
            // Request Wi-Fi transport without enforcing public Internet probe
            // (allows instant binding upon AP connection even before captive-portal check)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
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

    /**
     * Supervisor loop that manages the discharge lifecycle with automatic retries upon failure
     * as long as the Wi-Fi network is connected and unsynced records remain.
     */
    private suspend fun runDischargeSupervisorLoop() {
        if (!isDischarging.compareAndSet(false, true)) return
        acquireDischargeLocks()
        _stats.value = _stats.value.copy(isDischarging = true)

        var consecutiveFailures = 0

        try {
            while (shouldRun.get() && dischargerScope.isActive) {
                val network = currentWifiNetwork
                if (network == null) {
                    logger.w(TAG, "Cannot discharge: Wi-Fi network not connected or has no internet.")
                    _stats.value = _stats.value.copy(
                        state = BatchDischargeState.AWAITING_WIFI,
                        lastError = "Awaiting active Wi-Fi connection"
                    )
                    break
                }

                // Check if there are unsynced records remaining
                val pendingProbe = bufferRepository.getUnsyncedBatch(1)
                if (pendingProbe.isEmpty()) {
                    // Purge any synced records that might still reside in SQLite
                    val purged = bufferRepository.purgeSyncedRecords()
                    if (purged > 0) {
                        logger.s(TAG, "Purged $purged already-synced records waiting in buffer.")
                    }
                    _stats.value = _stats.value.copy(
                        state = BatchDischargeState.COMPLETED,
                        lastError = null
                    )
                    logger.s(TAG, "All records synced. Discharge supervisor completed cleanly.")
                    break
                }

                val sessionSuccess = executeSingleDischargeSession(network)

                if (sessionSuccess) {
                    consecutiveFailures = 0
                    val remaining = bufferRepository.getUnsyncedBatch(1)
                    if (remaining.isEmpty()) {
                        // CASO 1: Se valida que todos los registros de la tabla contienen is_synced = 1
                        logger.i(TAG, "Validación de tabla: todos los registros están sincronizados (is_synced = 1). Ejecutando purga final de SQLite...")
                        val purged = bufferRepository.purgeSyncedRecords()
                        logger.s(TAG, "Purga por finalización de ciclo completada: $purged registros eliminados de la base de datos.")

                        _stats.value = _stats.value.copy(
                            state = BatchDischargeState.COMPLETED,
                            lastError = null
                        )
                        logger.s(TAG, "All records discharged, confirmed, and purged successfully.")
                        break
                    }
                } else {
                    consecutiveFailures++
                    logger.w(TAG, "Batch discharge attempt failed (attempt #$consecutiveFailures). Preparing retry...")

                    if (!shouldRun.get() || currentWifiNetwork == null || !dischargerScope.isActive) {
                        break
                    }

                    // Exponential backoff: 3s, 6s, 12s, max 20s
                    val backoffMs = (3000L * (1 shl minOf(consecutiveFailures - 1, 2))).coerceIn(3000L, 20000L)
                    _stats.value = _stats.value.copy(
                        state = BatchDischargeState.ERROR,
                        lastError = "Fallo en broker/red. Reintentando en ${backoffMs / 1000}s (intento #$consecutiveFailures)..."
                    )
                    delay(backoffMs)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            logger.i(TAG, "Discharge supervisor loop cancelled cleanly (e.g. Wi-Fi window expired).")
            _stats.value = _stats.value.copy(
                state = BatchDischargeState.IDLE,
                lastError = null
            )
        } finally {
            releaseDischargeLocks()
            isDischarging.set(false)
            _stats.value = _stats.value.copy(isDischarging = false)
            refreshPendingCount()
        }
    }

    /**
     * Executes a single AMQP connection and batch publishing session.
     * Returns true if all available batches completed cleanly, or false on error/timeout.
     */
    private suspend fun executeSingleDischargeSession(network: Network): Boolean {
        _stats.value = _stats.value.copy(
            state = BatchDischargeState.CONNECTING
        )

        var connection: Connection? = null
        var channel: Channel? = null

        try {
            logger.s(TAG, "Connecting to RabbitMQ over Wi-Fi for bulk discharge...")

            val factory = ConnectionFactory().apply {
                host = connectionParams.host
                port = connectionParams.port
                virtualHost = connectionParams.virtualHost
                username = connectionParams.username
                password = connectionParams.password

                // Hardware-enforced Wi-Fi socket binding
                socketFactory = BoundNetworkSocketFactory(network = network, timeoutMs = 15000)

                connectionTimeout = 15000
                handshakeTimeout = 15000
                requestedHeartbeat = 30
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

            // Enable Publisher Confirms
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

            logger.s(TAG, "Connected to RabbitMQ for bulk discharge with async ConfirmListener enabled.")

            // Configure pipeline window depth from the user parameter (at least 1,000 to maximize Wi-Fi pipeline)
            val configuredBatch = if (connectionParams.batchSize > 0) connectionParams.batchSize else 500
            val pipelineCapacity = maxOf(configuredBatch * 2, 2000)
            val routingKeyCache = java.util.HashMap<String, String>(8)
            val deviceId = connectionParams.deviceId

            // Pre-allocated static properties template and reusable headers map to avoid GC thrashing
            val reusableHeaders = java.util.HashMap<String, Any>(4)
            reusableHeaders["device_id"] = deviceId

            val basePropsBuilder = AMQP.BasicProperties.Builder()
                .deliveryMode(1) // Transient mode (enables batch filesystem cache in RabbitMQ Streams)
                .contentType("application/json")

            // Semaphore for zero-delay hardware backpressure (no arbitrary delay() calls)
            val windowSemaphore = Semaphore(pipelineCapacity)

            // Concurrent tracking map: deliveryTag -> SQLite record.id
            val inFlightTags = ConcurrentSkipListMap<Long, Long>()

            // Decoupled asynchronous channel for batch updates to SQLite
            val ackChannel = KChannel<Long>(capacity = pipelineCapacity * 2)

            // Dedicated coroutine worker to persist confirmed IDs into SQLite without blocking network threads
            val sqliteSyncJob = dischargerScope.launch(Dispatchers.IO) {
                val batchAccumulator = ArrayList<Long>(500)
                try {
                    while (isActive) {
                        val firstId = ackChannel.receiveCatching().getOrNull() ?: break
                        batchAccumulator.add(firstId)

                        // Drain any additional immediately available ACKs to form a batch
                        while (batchAccumulator.size < 500) {
                            val nextId = ackChannel.tryReceive().getOrNull() ?: break
                            batchAccumulator.add(nextId)
                        }

                        if (batchAccumulator.isNotEmpty()) {
                            bufferRepository.markAsSynced(batchAccumulator)
                            batchAccumulator.clear()
                        }
                    }
                } finally {
                    // Drain remaining on termination
                    while (true) {
                        val remainingId = ackChannel.tryReceive().getOrNull() ?: break
                        batchAccumulator.add(remainingId)
                    }
                    if (batchAccumulator.isNotEmpty()) {
                        bufferRepository.markAsSynced(batchAccumulator)
                    }
                }
            }

            channel.addConfirmListener(object : ConfirmListener {
                override fun handleAck(deliveryTag: Long, multiple: Boolean) {
                    var ackCount = 0
                    if (multiple) {
                        val headMap = inFlightTags.headMap(deliveryTag, true)
                        val iterator = headMap.entries.iterator()
                        while (iterator.hasNext()) {
                            val entry = iterator.next()
                            ackChannel.trySend(entry.value)
                            iterator.remove()
                            ackCount++
                        }
                    } else {
                        inFlightTags.remove(deliveryTag)?.let { recordId ->
                            ackChannel.trySend(recordId)
                            ackCount++
                        }
                    }

                    if (ackCount > 0) {
                        windowSemaphore.release(ackCount)
                        val total = totalDischargedCounter.addAndGet(ackCount.toLong())
                        confirmsReceivedCounter.addAndGet(ackCount.toLong())
                        _stats.value = _stats.value.copy(
                            totalRecordsDischarged = total,
                            confirmsReceived = confirmsReceivedCounter.get(),
                            lastDischargeTimestamp = System.currentTimeMillis()
                        )
                    }
                }

                override fun handleNack(deliveryTag: Long, multiple: Boolean) {
                    var nackCount = 0
                    if (multiple) {
                        val headMap = inFlightTags.headMap(deliveryTag, true)
                        nackCount = headMap.size
                        headMap.clear()
                    } else {
                        if (inFlightTags.remove(deliveryTag) != null) {
                            nackCount = 1
                        }
                    }

                    if (nackCount > 0) {
                        windowSemaphore.release(nackCount)
                        confirmsFailedCounter.addAndGet(nackCount.toLong())
                        _stats.value = _stats.value.copy(
                            confirmsFailed = confirmsFailedCounter.get(),
                            lastError = "Broker NACK received for $nackCount messages"
                        )
                        logger.w(TAG, "Broker NACK received for $nackCount messages.")
                    }
                }
            })

            // Continuous publication loop without any pauses
            try {
                while (shouldRun.get() && dischargerScope.isActive && currentWifiNetwork != null) {
                    val chunk: List<TelemetryRecordEntity> = bufferRepository.getUnsyncedBatch(configuredBatch)
                    if (chunk.isEmpty()) {
                        break
                    }

                    _stats.value = _stats.value.copy(
                        state = BatchDischargeState.DISCHARGING,
                        currentBatchSize = chunk.size
                    )

                    val chunkSize = chunk.size
                    for (i in 0 until chunkSize) {
                        if (!shouldRun.get() || !dischargerScope.isActive || currentWifiNetwork == null) {
                            break
                        }

                        // Acquire window permit; blocks native thread ONLY if pipeline is full
                        windowSemaphore.acquire()

                        val record = chunk[i]
                        val payloadBytes = record.payloadJson.toByteArray(Charsets.UTF_8)
                        val subType = resolveRecordSubType(record)

                        reusableHeaders["packet_type"] = record.packetType
                        reusableHeaders["source_type"] = record.sourceType
                        reusableHeaders["type"] = subType

                        val props = basePropsBuilder
                            .type(subType)
                            .messageId(record.id.toString())
                            .timestamp(Date(record.timestamp))
                            .headers(reusableHeaders)
                            .build()

                        val nextSeqNo = channel.nextPublishSeqNo
                        inFlightTags[nextSeqNo] = record.id

                        val routingKey = routingKeyCache.getOrPut(subType) {
                            connectionParams.getBatchRoutingKey(subType)
                        }

                        channel.basicPublish(exchange, routingKey, props, payloadBytes)
                    }
                }

                // Draining phase: Wait until all in-flight tags have received their ACK
                _stats.value = _stats.value.copy(state = BatchDischargeState.CONFIRMING)
                val drainTimeoutMs = maxOf(30_000L, inFlightTags.size * 35L)
                val drainStart = System.currentTimeMillis()

                logger.i(TAG, "Draining async pipeline: awaiting ${inFlightTags.size} remaining in-flight confirms...")

                while (inFlightTags.isNotEmpty() && (System.currentTimeMillis() - drainStart < drainTimeoutMs)) {
                    if (!shouldRun.get() || !dischargerScope.isActive || currentWifiNetwork == null) break
                    delay(20)
                }

                if (inFlightTags.isNotEmpty()) {
                    logger.w(TAG, "Pipeline drain timed out with ${inFlightTags.size} messages unconfirmed.")
                    return false
                }

                logger.s(TAG, "Async batch session completed cleanly: all messages confirmed and piped to SQLite.")
                return true
            } finally {
                ackChannel.close()
                sqliteSyncJob.join()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            logger.i(TAG, "Batch discharge session cancelled cleanly (e.g. Wi-Fi window expired).")
            throw e
        } catch (t: Throwable) {
            val rawMsg = t.message ?: t.javaClass.simpleName
            val classifiedError = AmqpErrorClassifier.classifyBatchError(t)
            logger.e(TAG, "Batch discharge failed ($classifiedError): $rawMsg")
            _stats.value = _stats.value.copy(
                state = BatchDischargeState.ERROR,
                lastError = classifiedError
            )
            return false
        } finally {
            try {
                channel?.close()
            } catch (ignored: Throwable) {}
            try {
                connection?.abort(1500)
            } catch (ignored: Throwable) {}
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

    /**
     * Called when the Wi-Fi data discharge window has elapsed / completed (Caso 2).
     * Stops active discharge sessions, purges all records marked as synced (isSynced=1),
     * and resets the window counters.
     */
    fun onWifiWindowEnded() {
        dischargeJob?.cancel()
        dischargerScope.launch {
            try {
                logger.i(TAG, "Ventana de tiempo de descarga Wi-Fi cumplida (Caso 2). Purgando registros con is_synced=1...")
                val purged = bufferRepository.purgeSyncedRecords()
                logger.s(TAG, "Purga al cumplirse la ventana Wi-Fi ejecutada: $purged registros eliminados de SQLite.")
            } catch (t: Throwable) {
                logger.e(TAG, "Error durante la purga al cumplirse la ventana Wi-Fi: ${t.message}")
            } finally {
                resetWindowCounters()
            }
        }
    }

    fun stop() {
        shouldRun.set(false)
        logger.w(TAG, "Stopping AMQP Batch Discharger...")

        try {
            connectivityManager?.unregisterNetworkCallback(wifiNetworkCallback)
        } catch (ignored: Throwable) {}

        dischargeJob?.cancel()
        releaseDischargeLocks()

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
