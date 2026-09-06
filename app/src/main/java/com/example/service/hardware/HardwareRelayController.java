package com.example.service.hardware;

import android.content.Context;
import com.example.model.ScheduleConfig;
import com.example.model.telemetry.DeviceStatusTelemetry;
import com.example.service.ScheduleManager;
import com.example.utils.AppLogger;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controller responsible for managing battery charging and thermal cooling (fans)
 * by sending raw JSON TCP command packets to the ESP32 relay driver on port 8888.
 *
 * Packets sent:
 * {"task": "charge", "action": 1}  // 1: encendido, 0: apagado
 * {"task": "fans", "action": 1}    // 1: encendido, 0: apagado
 */
public class HardwareRelayController {
    private static final String TAG = "HardwareRelayController";
    private static final int DRIVER_PORT = 8888;
    private static final int SOCKET_TIMEOUT_MS = 3500;

    private static volatile HardwareRelayController instance;

    private final Context context;
    private final AppLogger logger = AppLogger.getInstance();
    private final ExecutorService senderExecutor = Executors.newSingleThreadExecutor();

    // Relay logical states
    private final AtomicBoolean isChargeRelayOn = new AtomicBoolean(false);
    private final AtomicBoolean isFansRelayOn = new AtomicBoolean(false);

    // Rate-limiting and hysteresis tracking to prevent flapping
    private final AtomicLong lastChargeCommandTime = new AtomicLong(0);
    private final AtomicLong lastFansCommandTime = new AtomicLong(0);
    private static final long MIN_SWITCH_INTERVAL_MS = 8000; // 8s min interval between auto state transitions

    // Status message for UI diagnostics
    private volatile String lastCommandStatus = "Ready";
    private volatile long lastCommandTimestamp = 0;

    private HardwareRelayController(Context context) {
        this.context = context.getApplicationContext();
    }

    public static HardwareRelayController getInstance(Context context) {
        if (instance == null) {
            synchronized (HardwareRelayController.class) {
                if (instance == null) {
                    instance = new HardwareRelayController(context);
                }
            }
        }
        return instance;
    }

    public boolean isChargeRelayOn() {
        return isChargeRelayOn.get();
    }

    public boolean isFansRelayOn() {
        return isFansRelayOn.get();
    }

    public String getLastCommandStatus() {
        return lastCommandStatus;
    }

    public long getLastCommandTimestamp() {
        return lastCommandTimestamp;
    }

    /**
     * Evaluates smartphone device telemetry (battery level, temperature) against configured thresholds
     * and autonomously dispatches charge / fan relay commands to the ESP32 driver via TCP socket port 8888.
     */
    public void evaluateTelemetry(DeviceStatusTelemetry telemetry) {
        if (telemetry == null) return;

        ScheduleConfig config = ScheduleManager.getInstance().getConfig();
        if (config == null) return;

        int batteryMin = config.getBatteryMin(); // e.g. 20%
        int batteryMax = config.getBatteryMax(); // e.g. 80%
        int tempMin = config.getTempMin();       // e.g. 15°C
        int tempMax = config.getTempMax();       // e.g. 45°C

        int currentBattery = telemetry.getBatteryLevelPercent();
        float currentTemp = telemetry.getBatteryTemperatureC();
        long now = System.currentTimeMillis();

        // 1. EVALUATE FANS RELAY (COOLING)
        // If temperature exceeds tempMax -> Turn ON Fans
        // If temperature cools down below (tempMax - 3°C) -> Turn OFF Fans
        if (now - lastFansCommandTime.get() >= MIN_SWITCH_INTERVAL_MS) {
            if (currentTemp >= tempMax && !isFansRelayOn.get()) {
                logger.w(TAG, String.format("Overheating detected: %.1f°C >= %d°C. Activating cooling fans via ESP32!", currentTemp, tempMax));
                setFansState(1, "Auto: High temp (" + currentTemp + "°C)");
            } else if (currentTemp <= (tempMax - 3.0f) && isFansRelayOn.get()) {
                logger.i(TAG, String.format("Temperature normalized: %.1f°C <= %d°C. Deactivating cooling fans.", currentTemp, tempMax - 3));
                setFansState(0, "Auto: Temp cooled down (" + currentTemp + "°C)");
            }
        }

        // 2. EVALUATE CHARGE RELAY
        // Safety Cutoff: If device temperature is critically high (temp >= tempMax), force stop charging!
        if (currentTemp >= tempMax && isChargeRelayOn.get()) {
            logger.w(TAG, String.format("Emergency charge cutoff: Temperature %.1f°C >= %d°C!", currentTemp, tempMax));
            setChargeState(0, "Safety Cutoff: Temp " + currentTemp + "°C");
            return;
        }

        if (now - lastChargeCommandTime.get() >= MIN_SWITCH_INTERVAL_MS) {
            if (currentBattery <= batteryMin && !isChargeRelayOn.get()) {
                logger.i(TAG, String.format("Low battery: %d%% <= %d%%. Requesting ESP32 to start charging.", currentBattery, batteryMin));
                setChargeState(1, "Auto: Low battery (" + currentBattery + "%)");
            } else if (currentBattery >= batteryMax && isChargeRelayOn.get()) {
                logger.i(TAG, String.format("Target battery reached: %d%% >= %d%%. Requesting ESP32 to stop charging.", currentBattery, batteryMax));
                setChargeState(0, "Auto: Battery full/target (" + currentBattery + "%)");
            }
        }
    }

    /**
     * Sends command to turn Charge relay on (1) or off (0).
     */
    public void setChargeState(int action, String reason) {
        boolean newState = (action == 1);
        isChargeRelayOn.set(newState);
        lastChargeCommandTime.set(System.currentTimeMillis());

        sendCommandAsync("charge", action, reason);
    }

    /**
     * Sends command to turn Fans relay on (1) or off (0).
     */
    public void setFansState(int action, String reason) {
        boolean newState = (action == 1);
        isFansRelayOn.set(newState);
        lastFansCommandTime.set(System.currentTimeMillis());

        sendCommandAsync("fans", action, reason);
    }

    /**
     * Dispatches TCP socket packet on port 8888 in background thread to ipDriver:
     * {"task": "<task>", "action": <action>}
     */
    private void sendCommandAsync(String task, int action, String reason) {
        senderExecutor.execute(() -> {
            ScheduleConfig config = ScheduleManager.getInstance().getConfig();
            String host = (config != null) ? config.getIpDriver() : "192.168.43.100";
            if (host == null || host.trim().isEmpty()) {
                host = "192.168.43.100";
            }
            host = host.trim();

            String jsonPayload;
            try {
                JSONObject obj = new JSONObject();
                obj.put("task", task);
                obj.put("action", action);
                jsonPayload = obj.toString() + "\n";
            } catch (Exception e) {
                jsonPayload = "{\"task\":\"" + task + "\",\"action\":" + action + "}\n";
            }

            Socket socket = null;
            try {
                logger.i(TAG, String.format("Sending to ESP32 (%s:%d): %s [Reason: %s]", host, DRIVER_PORT, jsonPayload.trim(), reason));
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, DRIVER_PORT), SOCKET_TIMEOUT_MS);
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);

                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                writer.write(jsonPayload);
                writer.flush();

                lastCommandStatus = String.format("OK: %s=%d sent to %s (%s)", task, action, host, reason);
                lastCommandTimestamp = System.currentTimeMillis();
                logger.s(TAG, "Command delivered successfully to ESP32: " + jsonPayload.trim());
            } catch (Exception e) {
                lastCommandStatus = String.format("Err: %s (%s:%d)", e.getMessage() != null ? e.getMessage() : "Timeout", host, DRIVER_PORT);
                lastCommandTimestamp = System.currentTimeMillis();
                logger.w(TAG, String.format("Failed to deliver command to ESP32 at %s:%d - %s", host, DRIVER_PORT, e.getMessage()));
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {}
                }
            }
        });
    }
}
