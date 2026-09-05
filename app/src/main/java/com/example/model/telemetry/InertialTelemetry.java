package com.example.model.telemetry;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Locale;

/**
 * Immutable data holder for IMU, Accelerometer, Gyroscope, Magnetometer and Orientation.
 */
public class InertialTelemetry {
    private final float accelX;
    private final float accelY;
    private final float accelZ;
    private final float accelMagnitude;

    private final float gyroX;
    private final float gyroY;
    private final float gyroZ;

    private final float magX;
    private final float magY;
    private final float magZ;

    private final float pitch;
    private final float roll;
    private final float yaw;

    private final boolean isAccelAvailable;
    private final boolean isGyroAvailable;
    private final boolean isMagAvailable;
    private final boolean isOrientationAvailable;
    private final long timestamp;

    public InertialTelemetry(
            float accelX, float accelY, float accelZ,
            float gyroX, float gyroY, float gyroZ,
            float magX, float magY, float magZ,
            float pitch, float roll, float yaw,
            boolean isAccelAvailable, boolean isGyroAvailable, boolean isMagAvailable, boolean isOrientationAvailable,
            long timestamp
    ) {
        this.accelX = accelX;
        this.accelY = accelY;
        this.accelZ = accelZ;
        this.accelMagnitude = (float) Math.sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ);

        this.gyroX = gyroX;
        this.gyroY = gyroY;
        this.gyroZ = gyroZ;

        this.magX = magX;
        this.magY = magY;
        this.magZ = magZ;

        this.pitch = pitch;
        this.roll = roll;
        this.yaw = yaw;

        this.isAccelAvailable = isAccelAvailable;
        this.isGyroAvailable = isGyroAvailable;
        this.isMagAvailable = isMagAvailable;
        this.isOrientationAvailable = isOrientationAvailable;
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    // Backwards compatibility constructor
    public InertialTelemetry(
            float accelX, float accelY, float accelZ,
            float gyroX, float gyroY, float gyroZ,
            float pitch, float roll, float yaw,
            boolean isAccelAvailable, boolean isGyroAvailable, boolean isOrientationAvailable,
            long timestamp
    ) {
        this(accelX, accelY, accelZ, gyroX, gyroY, gyroZ, 0f, 0f, 0f, pitch, roll, yaw,
                isAccelAvailable, isGyroAvailable, false, isOrientationAvailable, timestamp);
    }

    public static InertialTelemetry empty() {
        return new InertialTelemetry(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, false, false, false, System.currentTimeMillis());
    }

    public float getAccelX() { return accelX; }
    public float getAccelY() { return accelY; }
    public float getAccelZ() { return accelZ; }
    public float getAccelMagnitude() { return accelMagnitude; }

    public float getGyroX() { return gyroX; }
    public float getGyroY() { return gyroY; }
    public float getGyroZ() { return gyroZ; }

    public float getMagX() { return magX; }
    public float getMagY() { return magY; }
    public float getMagZ() { return magZ; }

    public float getPitch() { return pitch; }
    public float getRoll() { return roll; }
    public float getYaw() { return yaw; }

    public boolean isAccelAvailable() { return isAccelAvailable; }
    public boolean isGyroAvailable() { return isGyroAvailable; }
    public boolean isMagAvailable() { return isMagAvailable; }
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

    public String getMagFormatted() {
        if (!isMagAvailable) return "N/A";
        return String.format(Locale.US, "X:%.1f Y:%.1f Z:%.1f µT", magX, magY, magZ);
    }

    public String getOrientationFormatted() {
        if (!isOrientationAvailable) return "N/A";
        return String.format(Locale.US, "Pitch:%.1f° Roll:%.1f° Yaw:%.1f°", pitch, roll, yaw);
    }

    /**
     * Serializes inertial attributes into flat JSON format:
     * accx, accy, accz, acc_mag, acc_available,
     * gyrx, gyry, gyrz, gyr_available,
     * magx, magy, magz, mag_available,
     * pitch, roll, yaw, pry_available,
     * timestamp
     */
    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            populateFlatJson(obj);
        } catch (JSONException ignored) {}
        return obj;
    }

    public void populateFlatJson(JSONObject obj) throws JSONException {
        obj.put("accx", (double) Math.round(accelX * 100) / 100);
        obj.put("accy", (double) Math.round(accelY * 100) / 100);
        obj.put("accz", (double) Math.round(accelZ * 100) / 100);
        obj.put("acc_mag", (double) Math.round(accelMagnitude * 100) / 100);
        obj.put("acc_available", isAccelAvailable);

        obj.put("gyrx", (double) Math.round(gyroX * 1000) / 1000);
        obj.put("gyry", (double) Math.round(gyroY * 1000) / 1000);
        obj.put("gyrz", (double) Math.round(gyroZ * 1000) / 1000);
        obj.put("gyr_available", isGyroAvailable);

        obj.put("magx", (double) Math.round(magX * 1000) / 1000);
        obj.put("magy", (double) Math.round(magY * 1000) / 1000);
        obj.put("magz", (double) Math.round(magZ * 1000) / 1000);
        obj.put("mag_available", isMagAvailable);

        obj.put("pitch", (double) Math.round(pitch * 10) / 10);
        obj.put("roll", (double) Math.round(roll * 10) / 10);
        obj.put("yaw", (double) Math.round(yaw * 10) / 10);
        obj.put("pry_available", isOrientationAvailable);

        obj.put("timestamp", timestamp);
    }
}
