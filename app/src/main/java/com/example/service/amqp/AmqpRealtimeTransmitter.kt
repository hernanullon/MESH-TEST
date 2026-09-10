package com.example.service.amqp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.model.telemetry.UnifiedTelemetrySnapshot
import com.example.utils.AppLogger
import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel as CoroutineChannel
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
import kotlin.random.Random

/**
 * Ultra-resilient Real-Time AMQP Telemetry Streamer strictly routed through SIM Cellular Mobile Data.
 *
 * ROBUSTNESS GUARANTEES (Eliminates need for app or smartphone reboots):
 * 1. Forced Cellular Binding: Requests Cellular via ConnectivityManager and binds TCP sockets directly to SIM network.
 * 2. Non-blocking Ingestion: 1-second snapshots enter a bounded dropping buffer (never blocks sensor collection).
 * 3. Thread-Leak Prevention: Uses a managed single-thread executor for AMQP dispatch (never leaks native threads).
 * 4. Aggressive Timeouts & Heartbeats: 8s connect timeout, 8s handshake timeout, 10s heartbeat.
 * 5. Forced Socket Abort: Uses connection.abort() rather than close() to immediately unblock hung kernel TCP sockets.
 * 6. Autonomous Reconnect & Watchdog: Self-healing loop with exponential backoff and jitter, plus periodic liveness checks.
 */
class AmqpRealtimeTransmitter(private val context: Context) {

    private val logger = AppLogger.getInstance()
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val transmitterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watchdogJob: Job? = null

    // Single-thread dispatcher for immediate realtime network send (no buffer queues)
    private val sendExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "AmqpRealtimeDirectSend").apply { isDaemon = true }
    }
    private val isSendingNow = AtomicBoolean(false)

    // Current State & Metrics
    private val _stats = MutableStateFlow(RealtimeStats())
    val stats: StateFlow<RealtimeStats> = _stats.asStateFlow()

    @Volatile
    private var currentCellularNetwork: Network? = null

    @Volatile
    private var amqpConnection: Connection? = null

    @Volatile
    private var amqpChannel: Channel? = null

    private var sharedExecutor: ExecutorService? = null

    private val isConnecting = AtomicBoolean(false)
    private val shouldRun = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    private val packetsSentCounter = AtomicLong(0)
    private val packetsDroppedCounter = AtomicLong(0)
    private val errorCounter = AtomicLong(0)

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var isNetworkRegistered = false

    @Volatile
    private var isRecyclingCellularNetwork = false

    @Volatile
    private var connectionParams: AmqpConnectionParams = AmqpConnectionParams.fromScheduleConfig(null)

    // Network Callback for Cellular Interface
    private val cellularNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val caps = connectivityManager?.getNetworkCapabilities(network)
            val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            logger.s(TAG, "Cellular network AVAILABLE (id=$network, isCellular=$isCellular)")

            currentCellularNetwork = network
            _stats.value = _stats.value.copy(
                cellularAvailable = true,
                networkInterfaceName = "Cellular (SIM)"
            )

            // Reset backoff upon network recovery and attempt immediate connection (unless paused by Wi-Fi window)
            consecutiveFailures = 0
            if (!isPaused.get()) {
                triggerConnectionAttempt(forceImmediate = true)
            }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            logger.i(TAG, "Cellular capabilities changed: internet=$hasInternet, validated=$isValidated")

            // If Android just validated the network (e.g., after user topped up data), immediately attempt connection!
            if (hasInternet && !isPaused.get() && amqpConnection?.isOpen != true) {
                logger.s(TAG, "Cellular network validation updated by OS. Triggering connection attempt...")
                triggerConnectionAttempt(forceImmediate = true)
            }
        }

        override fun onLost(network: Network) {
            logger.w(TAG, "Cellular network LOST (id=$network)")
            if (currentCellularNetwork == network) {
                currentCellularNetwork = null
            }
            _stats.value = _stats.value.copy(
                cellularAvailable = false,
                networkInterfaceName = "Disconnected",
                state = RealtimeStreamState.AWAITING_CELLULAR
            )
            abortConnectionSafely()
        }

        override fun onUnavailable() {
            logger.w(TAG, "Cellular network UNAVAILABLE")
            _stats.value = _stats.value.copy(
                cellularAvailable = false,
                networkInterfaceName = "Unavailable",
                state = RealtimeStreamState.AWAITING_CELLULAR
            )
            abortConnectionSafely()
        }
    }

    fun start(params: AmqpConnectionParams) {
        if (shouldRun.getAndSet(true)) {
            updateParams(params)
            return
        }

        this.connectionParams = params
        logger.s(TAG, "Starting AMQP Real-time Transmitter for target: ${params.host}:${params.port} (SIM Cellular)...")

        registerCellularNetwork()
        startWatchdogLoop()
    }

    fun updateParams(params: AmqpConnectionParams) {
        val changed = this.connectionParams != params
        this.connectionParams = params
        if (changed) {
            logger.i(TAG, "AMQP connection params updated. Reconnecting to ${params.host}:${params.port}...")
            triggerConnectionAttempt(forceImmediate = true)
        }
    }

    /**
     * Immediate 1-second pulse entry point from TelemetryEngine:
     * No queues, no buffers. Checks network and connection availability;
     * if busy or offline, drops immediately and moves on to next second.
     */
    fun enqueueSnapshot(snapshot: UnifiedTelemetrySnapshot) {
        if (!shouldRun.get() || isPaused.get()) return

        // 1. Quick check: Is connection and channel ready?
        val connection = amqpConnection
        val channel = amqpChannel
        if (connection == null || channel == null || !connection.isOpen || !channel.isOpen) {
            // Not ready/offline: discard immediately (raw data safely kept in SQLite)
            packetsDroppedCounter.incrementAndGet()
            _stats.value = _stats.value.copy(packetsDropped = packetsDroppedCounter.get())
            return
        }

        // 2. Concurrency check: If a previous transmission is still in-flight on the socket,
        // drop this snapshot immediately. Never queue or burst into the next second.
        if (!isSendingNow.compareAndSet(false, true)) {
            packetsDroppedCounter.incrementAndGet()
            _stats.value = _stats.value.copy(packetsDropped = packetsDroppedCounter.get())
            return
        }

        try {
            sendExecutor.execute {
                try {
                    transmitSnapshot(snapshot)
                } finally {
                    isSendingNow.set(false)
                }
            }
        } catch (t: Throwable) {
            isSendingNow.set(false)
            packetsDroppedCounter.incrementAndGet()
            _stats.value = _stats.value.copy(packetsDropped = packetsDroppedCounter.get())
        }
    }

    /**
     * Autonomous pause when Wi-Fi discharge window is active (Wi-Fi ON, TCP Mesh OFF).
     * Aborts active AMQP SIM socket and ceases all background reconnect loops and snapshot drops.
     */
    fun pause() {
        if (isPaused.getAndSet(true)) return
        logger.s(TAG, "Wi-Fi discharge window active: Pausing Real-Time Cellular AMQP stream.")

        abortConnectionSafely()
        _stats.value = _stats.value.copy(
            state = RealtimeStreamState.PAUSED,
            lastError = null
        )
    }

    /**
     * Autonomous resume when Field/Local TCP Mesh window is active (Wi-Fi OFF, Hotspot/TCP ON).
     * Re-arms cellular socket and reconnects to broker.
     */
    fun resume() {
        if (!isPaused.getAndSet(false)) return
        logger.s(TAG, "Local TCP Mesh window active: Resuming Real-Time Cellular AMQP stream.")
        consecutiveFailures = 0
        triggerConnectionAttempt(forceImmediate = true)
    }

    /**
     * User or Watchdog forced recovery: immediately resets sockets, executors and retries.
     * Completely eliminates the need to restart the application or device!
     */
    fun forceReconnect() {
        logger.s(TAG, "Manual/Forced Reconnection requested. Resetting AMQP sockets & state...")
        consecutiveFailures = 0
        abortConnectionSafely()
        recycleCellularNetwork()
    }

    /**
     * Unregisters and re-requests the Cellular network from Android OS.
     * This forces Android to negotiate fresh PDP/APN context and re-evaluate carrier data access.
     */
    private fun recycleCellularNetwork() {
        if (connectivityManager == null) return
        if (isRecyclingCellularNetwork) return
        isRecyclingCellularNetwork = true

        transmitterScope.launch {
            try {
                logger.w(TAG, "AUTONOMIC RECOVERY: Recycling cellular network registration with Android OS...")
                
                // Report to Android that current cellular network connectivity is dead,
                // forcing Android's internal NetworkMonitor to re-probe the carrier captive portal / DNS.
                currentCellularNetwork?.let { net ->
                    try {
                        connectivityManager.reportNetworkConnectivity(net, false)
                        logger.i(TAG, "Reported network connectivity failure to OS for network $net")
                    } catch (ignored: Throwable) {}
                }

                if (isNetworkRegistered) {
                    try {
                        connectivityManager.unregisterNetworkCallback(cellularNetworkCallback)
                    } catch (ignored: Throwable) {}
                    isNetworkRegistered = false
                }

                currentCellularNetwork = null
                _stats.value = _stats.value.copy(
                    cellularAvailable = false,
                    state = RealtimeStreamState.AWAITING_CELLULAR,
                    networkInterfaceName = "Refreshing Radio..."
                )

                // Give Android OS stack 2 seconds to release the stale PDP context
                delay(2000)

                registerCellularNetwork()
            } catch (t: Throwable) {
                logger.e(TAG, "Error while recycling cellular network: ${t.message}")
            } finally {
                isRecyclingCellularNetwork = false
            }
        }
    }

    private fun registerCellularNetwork() {
        if (connectivityManager == null) {
            logger.e(TAG, "ConnectivityManager unavailable!")
            return
        }

        try {
            if (isNetworkRegistered) {
                try {
                    connectivityManager.unregisterNetworkCallback(cellularNetworkCallback)
                } catch (ignored: Throwable) {}
            }

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            _stats.value = _stats.value.copy(state = RealtimeStreamState.AWAITING_CELLULAR)
            connectivityManager.requestNetwork(request, cellularNetworkCallback)
            isNetworkRegistered = true
            logger.i(TAG, "Cellular network request registered with ConnectivityManager.")
        } catch (t: Throwable) {
            logger.e(TAG, "Failed to request cellular network: ${t.message}")
            isNetworkRegistered = false
            _stats.value = _stats.value.copy(
                lastError = "[Mobile Network] Cellular radio unavailable on device",
                state = RealtimeStreamState.ERROR
            )
        }
    }

    private fun transmitSnapshot(snapshot: UnifiedTelemetrySnapshot) {
        val connection = amqpConnection
        val channel = amqpChannel

        if (connection == null || channel == null || !connection.isOpen || !channel.isOpen) {
            // Connection is currently offline; drop packet (data is persisted in SQLite anyway)
            packetsDroppedCounter.incrementAndGet()
            _stats.value = _stats.value.copy(packetsDropped = packetsDroppedCounter.get())
            return
        }

        val startNs = System.nanoTime()
        val routingKey = connectionParams.getRealtimeRoutingKey()
        val exchange = connectionParams.exchange

        try {
            val payloadBytes = snapshot.toJsonString().toByteArray(Charsets.UTF_8)
            val props = AMQP.BasicProperties.Builder()
                .deliveryMode(1) // Non-persistent for real-time snapshots (SQLite holds raw persistent records)
                .contentType("application/json")
                .type("realtime")
                .timestamp(Date(snapshot.timestamp))
                .appId("LocalMesh-Android")
                .build()

            channel.basicPublish(exchange, routingKey, props, payloadBytes)

            val latencyMs = (System.nanoTime() - startNs) / 1_000_000
            val sent = packetsSentCounter.incrementAndGet()

            _stats.value = _stats.value.copy(
                state = RealtimeStreamState.STREAMING,
                packetsSent = sent,
                lastSentTimestamp = System.currentTimeMillis(),
                lastLatencyMs = latencyMs,
                activeRoutingKey = routingKey,
                brokerEndpoint = "${connectionParams.host}:${connectionParams.port}"
            )
        } catch (t: Throwable) {
            logger.e(TAG, "Publishing failed on AMQP channel: ${t.message}")
            errorCounter.incrementAndGet()
            _stats.value = _stats.value.copy(
                errorCount = errorCounter.get(),
                lastError = "Publish error: ${t.message}",
                state = RealtimeStreamState.ERROR
            )
            abortConnectionSafely()
            triggerConnectionAttempt(forceImmediate = false)
        }
    }

    private fun triggerConnectionAttempt(forceImmediate: Boolean) {
        if (!shouldRun.get() || isPaused.get()) return
        if (isConnecting.get()) return

        transmitterScope.launch {
            if (!forceImmediate) {
                // Exponential backoff with random jitter: 2s, 4s, 8s, 16s, up to 30s + jitter
                val baseDelay = (2L shl consecutiveFailures.coerceAtMost(4)) * 1000L
                val jitter = Random.nextLong(0, 1500)
                val totalDelay = (baseDelay + jitter).coerceAtMost(30000L)

                _stats.value = _stats.value.copy(
                    state = RealtimeStreamState.RETRY_BACKOFF,
                    consecutiveFailures = consecutiveFailures
                )
                logger.i(TAG, "Backoff wait before AMQP reconnect: ${totalDelay}ms (failure count: $consecutiveFailures)")
                delay(totalDelay)
            }

            connectInternal()
        }
    }

    private suspend fun connectInternal() {
        if (!shouldRun.get()) return
        if (!isConnecting.compareAndSet(false, true)) return

        try {
            val network = currentCellularNetwork
            if (network == null) {
                logger.w(TAG, "Cannot connect: Cellular network not currently available.")
                _stats.value = _stats.value.copy(
                    state = RealtimeStreamState.AWAITING_CELLULAR,
                    lastError = "Waiting for Cellular SIM connection"
                )
                return
            }

            _stats.value = _stats.value.copy(
                state = RealtimeStreamState.CONNECTING,
                brokerEndpoint = "${connectionParams.host}:${connectionParams.port}"
            )

            // Clean up any stale sockets/connections
            abortConnectionSafely()

            logger.i(TAG, "Connecting to RabbitMQ at ${connectionParams.host}:${connectionParams.port} (vhost: ${connectionParams.virtualHost}) via SIM Cellular...")

            val factory = ConnectionFactory().apply {
                host = connectionParams.host
                port = connectionParams.port
                virtualHost = connectionParams.virtualHost
                username = connectionParams.username
                password = connectionParams.password

                // Hardware-enforced cellular socket binding
                socketFactory = BoundNetworkSocketFactory(network = network, timeoutMs = 8000)

                // Resilient connection timeouts & heartbeats
                connectionTimeout = 8000
                handshakeTimeout = 8000
                requestedHeartbeat = 10 // 10-second heartbeat to detect silent dead links quickly
                isAutomaticRecoveryEnabled = false // Manual supervision prevents recovery deadlocks

                if (connectionParams.sslEnabled) {
                    useSslProtocol()
                }

                // Dedicated thread executor to prevent thread leak
                if (sharedExecutor == null || sharedExecutor!!.isShutdown) {
                    sharedExecutor = Executors.newSingleThreadExecutor { r ->
                        Thread(r, "AmqpRealtimeDispatch").apply { isDaemon = true }
                    }
                }
                setSharedExecutor(sharedExecutor)
            }

            _stats.value = _stats.value.copy(state = RealtimeStreamState.AUTHENTICATING)

            val connection = factory.newConnection("LocalMesh-RealtimeStream-${connectionParams.deviceId}")
            val channel = connection.createChannel()

            // Passive check or declaration of exchange
            try {
                channel.exchangeDeclarePassive(connectionParams.exchange)
            } catch (t: Throwable) {
                // If exchange doesn't exist, declare as direct exchange
                logger.i(TAG, "Exchange '${connectionParams.exchange}' does not exist, declaring direct exchange...")
                try {
                    val freshChannel = connection.createChannel()
                    freshChannel.exchangeDeclare(connectionParams.exchange, "direct", true)
                } catch (ignored: Throwable) {}
            }

            this.amqpConnection = connection
            this.amqpChannel = channel
            this.consecutiveFailures = 0

            logger.s(TAG, "SUCCESS! AMQP Real-time connection established via SIM Cellular Mobile Data.")

            _stats.value = _stats.value.copy(
                state = RealtimeStreamState.STREAMING,
                lastError = null,
                consecutiveFailures = 0,
                activeRoutingKey = connectionParams.getRealtimeRoutingKey()
            )
        } catch (t: Throwable) {
            consecutiveFailures++
            errorCounter.incrementAndGet()
            val rawMsg = t.message ?: t.javaClass.simpleName
            val classifiedError = AmqpErrorClassifier.classifyRealtimeError(t)
            logger.e(TAG, "AMQP connection attempt failed ($classifiedError): $rawMsg")

            _stats.value = _stats.value.copy(
                state = RealtimeStreamState.ERROR,
                errorCount = errorCounter.get(),
                lastError = classifiedError,
                consecutiveFailures = consecutiveFailures
            )

            abortConnectionSafely()

            // Autonomic Escalation:
            // If we have failed multiple consecutive times (e.g. 4+ times), the carrier PDP context
            // or socket binding is likely stale (e.g. user recently refilled balance/data plan).
            // Recycle cellular network with Android OS instead of an ordinary socket retry.
            if (consecutiveFailures % 4 == 0) {
                logger.w(TAG, "Autonomic Escalation: $consecutiveFailures consecutive failures. Cycling cellular registration...")
                recycleCellularNetwork()
            } else {
                triggerConnectionAttempt(forceImmediate = false)
            }
        } finally {
            isConnecting.set(false)
        }
    }

    private fun startWatchdogLoop() {
        watchdogJob?.cancel()
        watchdogJob = transmitterScope.launch {
            while (isActive && shouldRun.get()) {
                delay(8000)
                try {
                    runWatchdogCheck()
                } catch (t: Throwable) {
                    logger.w(TAG, "Watchdog check error: ${t.message}")
                }
            }
        }
    }

    private fun runWatchdogCheck() {
        if (!shouldRun.get() || isPaused.get()) return

        val network = currentCellularNetwork
        val conn = amqpConnection
        val ch = amqpChannel

        if (network != null) {
            val isBroken = conn == null || ch == null || !conn.isOpen || !ch.isOpen
            if (isBroken && !isConnecting.get() && _stats.value.state != RealtimeStreamState.RETRY_BACKOFF) {
                logger.w(TAG, "Watchdog detected disconnected/dead AMQP connection while Cellular is up. Reviving...")
                triggerConnectionAttempt(forceImmediate = true)
            }
        }
    }

    private fun abortConnectionSafely() {
        try {
            amqpChannel?.close()
        } catch (ignored: Throwable) {}
        amqpChannel = null

        try {
            // connection.abort() forcibly terminates the socket file descriptor instantly without waiting for broker FIN
            amqpConnection?.abort(1500)
        } catch (ignored: Throwable) {}
        amqpConnection = null
    }

    fun stop() {
        shouldRun.set(false)
        logger.w(TAG, "Stopping AMQP Real-time Transmitter...")

        try {
            connectivityManager?.unregisterNetworkCallback(cellularNetworkCallback)
        } catch (ignored: Throwable) {}

        watchdogJob?.cancel()

        abortConnectionSafely()

        sharedExecutor?.shutdownNow()
        sharedExecutor = null
        sendExecutor.shutdownNow()

        _stats.value = RealtimeStats(state = RealtimeStreamState.IDLE)
    }

    companion object {
        private const val TAG = "AmqpRealtimeTransmitter"
    }
}
