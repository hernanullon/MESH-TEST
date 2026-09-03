package com.example.service.telemetry;

import android.content.Context;
import com.example.data.local.TelemetryBufferRepository;
import com.example.model.telemetry.DeviceStatusTelemetry;
import com.example.model.telemetry.InertialTelemetry;
import com.example.model.telemetry.LocationTelemetry;
import com.example.model.telemetry.UnifiedTelemetrySnapshot;
import com.example.service.MeshStateManager;
import com.example.service.ScheduleManager;
import com.example.utils.AppLogger;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central Coordinator & Engine for Telemetry (GPS, IMU, Device Status).
 * Operates inside PersistentWifiTcpService with independent sampling tasks per sensor group:
 * - LocationCollector: GPS / Network location sampled at locationIntervalSeconds (e.g. 1s)
 * - InertialCollector: IMU Accelerometer / Gyro / Orientation sampled at inertialIntervalMs (e.g. 200ms)
 * - DeviceStatusCollector: Battery / RAM / Thermal sampled at 5s
 * Each group generates independent raw JSON records stored in Room SQLite with its own "type" field.
 */
public class TelemetryEngine {
    private static final String TAG = "TelemetryEngine";

    private final Context context;
    private final AppLogger logger = AppLogger.getInstance();
    private final MeshStateManager stateManager = MeshStateManager.getInstance();

    private final LocationCollector locationCollector;
    private final InertialCollector inertialCollector;
    private final DeviceStatusCollector deviceStatusCollector;

    private ScheduledExecutorService engineExecutor;
    private final AtomicLong sequenceGenerator = new AtomicLong(0);

    private volatile boolean isRunning = false;
    private volatile UnifiedTelemetrySnapshot latestSnapshot;

    public TelemetryEngine(Context context) {
        this.context = context.getApplicationContext();
        this.locationCollector = new LocationCollector(this.context);
        this.inertialCollector = new InertialCollector(this.context);
        this.deviceStatusCollector = new DeviceStatusCollector(this.context);
        this.latestSnapshot = UnifiedTelemetrySnapshot.empty("NODE-01");
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;

        int locRateSec = 1;
        int imuRateMs = 200;
        try {
            if (ScheduleManager.getInstance().getConfig() != null) {
                locRateSec = ScheduleManager.getInstance().getConfig().getLocationIntervalSeconds();
                imuRateMs = ScheduleManager.getInstance().getConfig().getInertialIntervalMs();
            }
        } catch (Throwable ignored) {}

        logger.s(TAG, "Starting Independent Telemetry Engine (GPS: " + locRateSec + "s, IMU: " + imuRateMs + "ms)...");

        // 1. Configure and Start Subsystem Collectors
        try {
            locationCollector.setIntervalSeconds(locRateSec);
            locationCollector.start();
        } catch (Throwable t) {
            logger.e(TAG, "Failed to start LocationCollector: " + t.getMessage());
        }

        try {
            inertialCollector.setIntervalMs(imuRateMs);
            inertialCollector.start();
        } catch (Throwable t) {
            logger.e(TAG, "Failed to start InertialCollector: " + t.getMessage());
        }

        // 2. Start Independent Sampling Loops
        if (engineExecutor == null || engineExecutor.isShutdown()) {
            engineExecutor = Executors.newScheduledThreadPool(3);

            // Location sampling task at configured rate (locRateSec)
            engineExecutor.scheduleWithFixedDelay(
                    this::sampleAndBufferLocation,
                    1,
                    Math.max(1, locRateSec),
                    TimeUnit.SECONDS
            );

            // Inertial sampling task at configured rate (imuRateMs)
            engineExecutor.scheduleWithFixedDelay(
                    this::sampleAndBufferInertial,
                    200,
                    Math.max(20, imuRateMs),
                    TimeUnit.MILLISECONDS
            );

            // Device status sampling task at 5-second interval
            engineExecutor.scheduleWithFixedDelay(
                    this::sampleAndBufferDeviceStatus,
                    2,
                    5,
                    TimeUnit.SECONDS
            );

            // UI Snapshot & Supervisor cycle at 1 Hz
            engineExecutor.scheduleWithFixedDelay(
                    this::updateSnapshotAndWatchdog,
                    1,
                    1,
                    TimeUnit.SECONDS
            );
        }

        logger.i(TAG, "Telemetry Engine started with independent raw group buffering.");
    }

    private String getEffectiveDeviceId() {
        String deviceId = "NODE-01";
        try {
            if (ScheduleManager.getInstance().getConfig() != null) {
                deviceId = ScheduleManager.getInstance().getConfig().getDeviceId();
            }
        } catch (Throwable ignored) {}
        return deviceId;
    }

    /**
     * Samples Location at its independent rate and buffers raw JSON record
     */
    private void sampleAndBufferLocation() {
        if (!isRunning) return;
        try {
            LocationTelemetry loc = locationCollector.getLastLocation();
            if (loc != null) {
                String devId = getEffectiveDeviceId();
                TelemetryBufferRepository.getInstance(context).bufferLocation(devId, loc);
            }
        } catch (Throwable t) {
            logger.w(TAG, "Error buffering raw location: " + t.getMessage());
        }
    }

    /**
     * Samples Inertial IMU at its independent rate (e.g., 200ms / 5Hz) and buffers raw JSON record
     */
    private void sampleAndBufferInertial() {
        if (!isRunning) return;
        try {
            InertialTelemetry imu = inertialCollector.getLastSnapshot();
            if (imu != null) {
                String devId = getEffectiveDeviceId();
                TelemetryBufferRepository.getInstance(context).bufferInertial(devId, imu);
            }
        } catch (Throwable t) {
            logger.w(TAG, "Error buffering raw inertial: " + t.getMessage());
        }
    }

    /**
     * Samples Device Status (Battery/RAM/Thermal) and buffers raw JSON record
     */
    private void sampleAndBufferDeviceStatus() {
        if (!isRunning) return;
        try {
            DeviceStatusTelemetry dev = deviceStatusCollector.sample();
            if (dev != null) {
                String devId = getEffectiveDeviceId();
                TelemetryBufferRepository.getInstance(context).bufferDeviceStatus(devId, dev);
            }
        } catch (Throwable t) {
            logger.w(TAG, "Error buffering raw device status: " + t.getMessage());
        }
    }

    /**
     * Maintains current composite snapshot for live UI and runs watchdog checks
     */
    private void updateSnapshotAndWatchdog() {
        if (!isRunning) return;
        try {
            String deviceId = getEffectiveDeviceId();
            LocationTelemetry loc = locationCollector.getLastLocation();
            InertialTelemetry imu = inertialCollector.getLastSnapshot();
            DeviceStatusTelemetry dev = deviceStatusCollector.sample();

            long seq = sequenceGenerator.incrementAndGet();
            long now = System.currentTimeMillis();

            UnifiedTelemetrySnapshot snapshot = new UnifiedTelemetrySnapshot(
                    deviceId, now, seq, loc, imu, dev
            );
            this.latestSnapshot = snapshot;
            stateManager.setLatestTelemetrySnapshot(snapshot);

            // Supervisor check every 15 seconds
            if (seq % 15 == 0) {
                locationCollector.checkLivenessAndRestart();
                inertialCollector.checkLivenessAndRestart();
            }
        } catch (Throwable t) {
            logger.w(TAG, "Error in snapshot cycle: " + t.getMessage());
        }
    }

    public synchronized UnifiedTelemetrySnapshot sampleSnapshotNow() {
        String deviceId = getEffectiveDeviceId();
        LocationTelemetry loc = locationCollector.getLastLocation();
        InertialTelemetry imu = inertialCollector.getLastSnapshot();
        DeviceStatusTelemetry dev = deviceStatusCollector.sample();

        long seq = sequenceGenerator.incrementAndGet();
        long now = System.currentTimeMillis();

        UnifiedTelemetrySnapshot snapshot = new UnifiedTelemetrySnapshot(
                deviceId, now, seq, loc, imu, dev
        );
        this.latestSnapshot = snapshot;
        stateManager.setLatestTelemetrySnapshot(snapshot);
        return snapshot;
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        logger.w(TAG, "Stopping Telemetry Engine...");

        if (engineExecutor != null && !engineExecutor.isShutdown()) {
            engineExecutor.shutdownNow();
            engineExecutor = null;
        }

        try {
            locationCollector.stop();
        } catch (Throwable ignored) {}

        try {
            inertialCollector.stop();
        } catch (Throwable ignored) {}
    }

    public UnifiedTelemetrySnapshot getLatestSnapshot() {
        return latestSnapshot;
    }

    public LocationCollector getLocationCollector() {
        return locationCollector;
    }

    public InertialCollector getInertialCollector() {
        return inertialCollector;
    }

    public DeviceStatusCollector getDeviceStatusCollector() {
        return deviceStatusCollector;
    }

    public boolean isRunning() {
        return isRunning;
    }
}
