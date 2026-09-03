package com.example.model.telemetry;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Unified Snapshot containing all 3 local telemetry streams (GPS, IMU, Device)
 * ready for real-time UI display, TCP mesh broadcasting, and AMQP/RabbitMQ queue synchronization.
 */
public class UnifiedTelemetrySnapshot {
    private final String deviceId;
    private final long timestamp;
    private final long sequenceNumber;
    private final LocationTelemetry location;
    private final InertialTelemetry inertial;
    private final DeviceStatusTelemetry deviceStatus;

    public UnifiedTelemetrySnapshot(
            String deviceId,
            long timestamp,
            long sequenceNumber,
            LocationTelemetry location,
            InertialTelemetry inertial,
            DeviceStatusTelemetry deviceStatus
    ) {
        this.deviceId = (deviceId != null && !deviceId.trim().isEmpty()) ? deviceId.trim() : "NODE-UNKNOWN";
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
        this.sequenceNumber = sequenceNumber;
        this.location = location != null ? location : LocationTelemetry.empty();
        this.inertial = inertial != null ? inertial : InertialTelemetry.empty();
        this.deviceStatus = deviceStatus != null ? deviceStatus : DeviceStatusTelemetry.empty();
    }

    public static UnifiedTelemetrySnapshot empty(String deviceId) {
        return new UnifiedTelemetrySnapshot(
                deviceId,
                System.currentTimeMillis(),
                0L,
                LocationTelemetry.empty(),
                InertialTelemetry.empty(),
                DeviceStatusTelemetry.empty()
        );
    }

    public String getDeviceId() { return deviceId; }
    public long getTimestamp() { return timestamp; }
    public long getSequenceNumber() { return sequenceNumber; }
    public LocationTelemetry getLocation() { return location; }
    public InertialTelemetry getInertial() { return inertial; }
    public DeviceStatusTelemetry getDeviceStatus() { return deviceStatus; }

    public JSONObject toJsonObject() {
        JSONObject root = new JSONObject();
        try {
            root.put("device_id", deviceId);
            root.put("timestamp", timestamp);
            root.put("seq", sequenceNumber);
            root.put("type", "TELEMETRY_SNAPSHOT");
            root.put("location", location.toJson());
            root.put("imu", inertial.toJson());
            root.put("device", deviceStatus.toJson());
        } catch (JSONException ignored) {}
        return root;
    }

    public String toJsonString() {
        return toJsonObject().toString();
    }
}
