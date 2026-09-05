package com.example.model.telemetry;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

/**
 * Unified Snapshot containing all telemetry streams (GPS, IMU, Device, and External Sensors)
 * flattened into a single-level JSON for real-time AMQP/RabbitMQ queue synchronization.
 */
public class UnifiedTelemetrySnapshot {
    private final String deviceId;
    private final long timestamp;
    private final long sequenceNumber;
    private final LocationTelemetry location;
    private final InertialTelemetry inertial;
    private final DeviceStatusTelemetry deviceStatus;
    private final Map<String, JSONObject> activeExternalSensors;

    public UnifiedTelemetrySnapshot(
            String deviceId,
            long timestamp,
            long sequenceNumber,
            LocationTelemetry location,
            InertialTelemetry inertial,
            DeviceStatusTelemetry deviceStatus,
            Map<String, JSONObject> activeExternalSensors
    ) {
        this.deviceId = (deviceId != null && !deviceId.trim().isEmpty()) ? deviceId.trim() : "NODE-UNKNOWN";
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
        this.sequenceNumber = sequenceNumber;
        this.location = location != null ? location : LocationTelemetry.empty();
        this.inertial = inertial != null ? inertial : InertialTelemetry.empty();
        this.deviceStatus = deviceStatus != null ? deviceStatus : DeviceStatusTelemetry.empty();
        this.activeExternalSensors = activeExternalSensors != null ? activeExternalSensors : Collections.emptyMap();
    }

    public UnifiedTelemetrySnapshot(
            String deviceId,
            long timestamp,
            long sequenceNumber,
            LocationTelemetry location,
            InertialTelemetry inertial,
            DeviceStatusTelemetry deviceStatus
    ) {
        this(deviceId, timestamp, sequenceNumber, location, inertial, deviceStatus, Collections.emptyMap());
    }

    public static UnifiedTelemetrySnapshot empty(String deviceId) {
        return new UnifiedTelemetrySnapshot(
                deviceId,
                System.currentTimeMillis(),
                0L,
                LocationTelemetry.empty(),
                InertialTelemetry.empty(),
                DeviceStatusTelemetry.empty(),
                Collections.emptyMap()
        );
    }

    public String getDeviceId() { return deviceId; }
    public long getTimestamp() { return timestamp; }
    public long getSequenceNumber() { return sequenceNumber; }
    public LocationTelemetry getLocation() { return location; }
    public InertialTelemetry getInertial() { return inertial; }
    public DeviceStatusTelemetry getDeviceStatus() { return deviceStatus; }
    public Map<String, JSONObject> getActiveExternalSensors() { return activeExternalSensors; }

    /**
     * Serializes to a strictly FLAT single-level JSON structure:
     * - Root fields: device_id, timestamp, seq, type ("realtime")
     * - Location fields: lat, lon, alt, speed, bearing, accuracy, satellites, provider, has_fix
     * - Inertial fields: accx, accy, accz, acc_mag, acc_available, gyrx, gyry, gyrz, gyr_available, magx, magy, magz, mag_available, pitch, roll, yaw, pry_available
     * - Device fields: soc, is_charging, temp_c, voltage_mv, health, free_ram_mb, total_ram_mb, ram_usage_pct, free_storage_gb, total_storage_gb
     * - Active external sensors fields: prefixed with the first 3 characters of their type (e.g., cli_temperature, cli_humidity)
     */
    public JSONObject toJsonObject() {
        JSONObject root = new JSONObject();
        try {
            root.put("device_id", deviceId);
            root.put("timestamp", timestamp);
            root.put("seq", sequenceNumber);
            root.put("type", "realtime");

            // 1. Location (flat) - omit location's own timestamp to avoid overwriting root timestamp
            if (location != null) {
                JSONObject locFlat = location.toJson();
                Iterator<String> keys = locFlat.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    if (!"timestamp".equals(k) && !"ts".equals(k)) {
                        root.put(k, locFlat.get(k));
                    }
                }
            }

            // 2. Inertial (flat) - omit inertial's own timestamp
            if (inertial != null) {
                JSONObject imuFlat = inertial.toJson();
                Iterator<String> keys = imuFlat.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    if (!"timestamp".equals(k) && !"ts".equals(k)) {
                        root.put(k, imuFlat.get(k));
                    }
                }
            }

            // 3. Device Status (flat) - omit device's own timestamp
            if (deviceStatus != null) {
                JSONObject devFlat = deviceStatus.toJson();
                Iterator<String> keys = devFlat.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    if (!"timestamp".equals(k) && !"ts".equals(k)) {
                        root.put(k, devFlat.get(k));
                    }
                }
            }

            // 4. Active external sensors (flat with 3-letter prefix)
            if (activeExternalSensors != null && !activeExternalSensors.isEmpty()) {
                for (Map.Entry<String, JSONObject> entry : activeExternalSensors.entrySet()) {
                    String sensorType = entry.getKey().trim().toLowerCase();
                    String prefix = sensorType.length() >= 3 ? sensorType.substring(0, 3) : sensorType;
                    JSONObject sensorObj = entry.getValue();
                    if (sensorObj != null) {
                        Iterator<String> keys = sensorObj.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            // Skip metadata fields like type, device_id, sender, recipient, ts, timestamp
                            if ("type".equalsIgnoreCase(k) || "device_id".equalsIgnoreCase(k)
                                    || "sender".equalsIgnoreCase(k) || "recipient".equalsIgnoreCase(k)
                                    || "id".equalsIgnoreCase(k) || "ts".equalsIgnoreCase(k)
                                    || "timestamp".equalsIgnoreCase(k)) {
                                continue;
                            }
                            String prefixedKey = prefix + "_" + k;
                            root.put(prefixedKey, sensorObj.get(k));
                        }
                    }
                }
            }
        } catch (JSONException ignored) {}
        return root;
    }

    public String toJsonString() {
        return toJsonObject().toString();
    }
}
