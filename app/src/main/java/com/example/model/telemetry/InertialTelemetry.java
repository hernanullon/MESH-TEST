package com.example.model.telemetry;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Locale;

/**
 * Immutable data holder for IMU, Accelerometer, Gyroscope and Orientation.
 */
public class InertialTelemetry {
    private final float accelX;
    private final float accelY;
    private final float accelZ;
    private final float accelMagnitude;

    private final float gyroX;
    private final float gyroY;
    private final float gyroZ;

    private final float pitch;
    private final float roll;
    private final float yaw;

    private final boolean isAccelAvailable;
    private final boolean isGyroAvailable;
    private final boolean isOrientationAvailable;
    private final long timestamp;

    public InertialTelemetry(
            float accelX, float accelY, float accelZ,
            float gyroX, float gyroY, float gyroZ,
            float pitch, float roll, float yaw,
            boolean isAccelAvailable, boolean isGyroAvailable, boolean isOrientationAvailable,
            long timestamp
    ) {
        this.accelX = accelX;
        this.accelY = accelY;
        this.accelZ = accelZ;
        this.accelMagnitude = (float) Math.sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ);

        this.gyroX = gyroX;
        this.gyroY = gyroY;
        this.gyroZ = gyroZ;

        this.pitch = pitch;
        this.roll = roll;
        this.yaw = yaw;

        this.isAccelAvailable = isAccelAvailable;
        this.isGyroAvailable = isGyroAvailable;
        this.isOrientationAvailable = isOrientationAvailable;
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    public static InertialTelemetry empty() {
        return new InertialTelemetry(0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, false, System.currentTimeMillis());
    }

    public float getAccelX() { return accelX; }
    public float getAccelY() { return accelY; }
    public float getAccelZ() { return accelZ; }
    public float getAccelMagnitude() { return accelMagnitude; }

    public float getGyroX() { return gyroX; }
    public float getGyroY() { return gyroY; }
    public float getGyroZ() { return gyroZ; }

    public float getPitch() { return pitch; }
    public float getRoll() { return roll; }
    public float getYaw() { return yaw; }

    public boolean isAccelAvailable() { return isAccelAvailable; }
    public boolean isGyroAvailable() { return isGyroAvailable; }
    public boolean isOrientationAvailable() { return isOrientationAvailable; }
    public long getTimestamp() { return timestamp; }

    public String getAccelFormatted() {
        if (!isAccelAvailable) return "N/A";
        return String.format(Locale.US, "X:%.1f Y:%.1f Z:%.1f m/s² (|G|:%.1f)", accelX, accelY, accelZ, accelMagnitude);
    }

    public String getGyroFormatted() {
        if (!isGyroAvailable) return "N/A";
        return String.format(Locale.US, "X:%.2f Y:%.2f Z:%.2f rad/s", gyroX, gyroY, gyroZ);
    }

    public String getOrientationFormatted() {
        if (!isOrientationAvailable) return "N/A";
        return String.format(Locale.US, "Pitch:%.1f° Roll:%.1f° Yaw:%.1f°", pitch, roll, yaw);
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            JSONObject accel = new JSONObject();
            accel.put("x", (double) Math.round(accelX * 100) / 100);
            accel.put("y", (double) Math.round(accelY * 100) / 100);
            accel.put("z", (double) Math.round(accelZ * 100) / 100);
            accel.put("mag", (double) Math.round(accelMagnitude * 100) / 100);
            accel.put("available", isAccelAvailable);
            obj.put("accel", accel);

            JSONObject gyro = new JSONObject();
            gyro.put("x", (double) Math.round(gyroX * 1000) / 1000);
            gyro.put("y", (double) Math.round(gyroY * 1000) / 1000);
            gyro.put("z", (double) Math.round(gyroZ * 1000) / 1000);
            gyro.put("available", isGyroAvailable);
            obj.put("gyro", gyro);

            JSONObject orient = new JSONObject();
            orient.put("pitch", (double) Math.round(pitch * 10) / 10);
            orient.put("roll", (double) Math.round(roll * 10) / 10);
            orient.put("yaw", (double) Math.round(yaw * 10) / 10);
            orient.put("available", isOrientationAvailable);
            obj.put("orientation", orient);

            obj.put("ts", timestamp);
        } catch (JSONException ignored) {}
        return obj;
    }
}
