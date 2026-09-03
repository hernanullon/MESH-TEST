package com.example.service.telemetry;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import com.example.model.telemetry.InertialTelemetry;
import com.example.utils.AppLogger;

/**
 * High-performance, fault-tolerant Inertial (IMU) Collector.
 * Gathers Tri-Axial Accelerometer, Gyroscope and Pitch/Roll/Yaw Orientation.
 * Applies low-pass filtering and safeguards against missing hardware sensors.
 */
public class InertialCollector implements SensorEventListener {
    private static final String TAG = "InertialCollector";
    private static final float ALPHA = 0.15f; // Low-pass filter coefficient (smoothing)

    private final Context context;
    private final SensorManager sensorManager;
    private final AppLogger logger = AppLogger.getInstance();

    private Sensor accelerometer;
    private Sensor gyroscope;
    private Sensor rotationVector;
    private Sensor magnetometer;

    private volatile boolean isRunning = false;
    private volatile long lastEventTimestamp = 0;
    private volatile int intervalMs = 200;

    // Filtered Telemetry Values
    private float accelX = 0f, accelY = 0f, accelZ = 9.8f;
    private float gyroX = 0f, gyroY = 0f, gyroZ = 0f;
    private float pitch = 0f, roll = 0f, yaw = 0f;

    private boolean isAccelActive = false;
    private boolean isGyroActive = false;
    private boolean isOrientActive = false;

    // Matrix buffers for orientation calculation
    private final float[] rotationMatrix = new float[9];
    private final float[] orientationAngles = new float[3];
    private final float[] lastGravity = new float[3];
    private final float[] lastGeomagnetic = new float[3];
    private boolean hasGravity = false;
    private boolean hasGeomagnetic = false;

    private volatile InertialTelemetry lastSnapshot = InertialTelemetry.empty();

    public InertialCollector(Context context) {
        this.context = context.getApplicationContext();
        this.sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
    }

    public synchronized void setIntervalMs(int intervalMs) {
        this.intervalMs = Math.max(20, intervalMs);
        if (isRunning) {
            stop();
            start();
        }
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        logger.i(TAG, "Starting robust IMU inertial collector with interval=" + intervalMs + "ms...");

        if (sensorManager == null) {
            logger.w(TAG, "SensorManager unavailable on this hardware platform.");
            return;
        }

        int delayUs = Math.max(20, intervalMs) * 1000;

        // 1. Accelerometer
        try {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accelerometer != null) {
                isAccelActive = sensorManager.registerListener(this, accelerometer, delayUs);
                logger.i(TAG, "Accelerometer registered: " + accelerometer.getName() + " (" + delayUs + "µs)");
            } else {
                logger.w(TAG, "No Accelerometer sensor found on device.");
            }
        } catch (Throwable t) {
            logger.w(TAG, "Error registering accelerometer: " + t.getMessage());
        }

        // 2. Gyroscope
        try {
            gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (gyroscope != null) {
                isGyroActive = sensorManager.registerListener(this, gyroscope, delayUs);
                logger.i(TAG, "Gyroscope registered: " + gyroscope.getName() + " (" + delayUs + "µs)");
            } else {
                logger.w(TAG, "No Gyroscope sensor found on device (ignoring gracefully).");
            }
        } catch (Throwable t) {
            logger.w(TAG, "Error registering gyroscope: " + t.getMessage());
        }

        // 3. Rotation Vector (Fused Orientation)
        try {
            rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            if (rotationVector != null) {
                isOrientActive = sensorManager.registerListener(this, rotationVector, delayUs);
                logger.i(TAG, "Rotation Vector sensor registered for 3D orientation (" + delayUs + "µs)");
            } else {
                // Fallback to Magnetometer for orientation calculation
                magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
                if (magnetometer != null) {
                    sensorManager.registerListener(this, magnetometer, delayUs);
                    logger.i(TAG, "Magnetometer registered as orientation fallback (" + delayUs + "µs)");
                }
            }
        } catch (Throwable t) {
            logger.w(TAG, "Error registering orientation sensors: " + t.getMessage());
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null) return;
        lastEventTimestamp = System.currentTimeMillis();

        int type = event.sensor.getType();

        if (type == Sensor.TYPE_ACCELEROMETER) {
            accelX = accelX + ALPHA * (event.values[0] - accelX);
            accelY = accelY + ALPHA * (event.values[1] - accelY);
            accelZ = accelZ + ALPHA * (event.values[2] - accelZ);

            lastGravity[0] = accelX;
            lastGravity[1] = accelY;
            lastGravity[2] = accelZ;
            hasGravity = true;

            if (hasGravity && hasGeomagnetic && rotationVector == null) {
                calculateOrientationFromMagnetic();
            }
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            gyroX = gyroX + ALPHA * (event.values[0] - gyroX);
            gyroY = gyroY + ALPHA * (event.values[1] - gyroY);
            gyroZ = gyroZ + ALPHA * (event.values[2] - gyroZ);
        } else if (type == Sensor.TYPE_ROTATION_VECTOR) {
            try {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                SensorManager.getOrientation(rotationMatrix, orientationAngles);

                yaw = (float) Math.toDegrees(orientationAngles[0]);
                pitch = (float) Math.toDegrees(orientationAngles[1]);
                roll = (float) Math.toDegrees(orientationAngles[2]);
                isOrientActive = true;
            } catch (Throwable ignored) {}
        } else if (type == Sensor.TYPE_MAGNETIC_FIELD) {
            lastGeomagnetic[0] = event.values[0];
            lastGeomagnetic[1] = event.values[1];
            lastGeomagnetic[2] = event.values[2];
            hasGeomagnetic = true;

            if (hasGravity && hasGeomagnetic && rotationVector == null) {
                calculateOrientationFromMagnetic();
            }
        }

        // Periodically refresh the cached immutable snapshot
        lastSnapshot = new InertialTelemetry(
                accelX, accelY, accelZ,
                gyroX, gyroY, gyroZ,
                pitch, roll, yaw,
                isAccelActive, isGyroActive, isOrientActive,
                lastEventTimestamp
        );
    }

    private void calculateOrientationFromMagnetic() {
        try {
            boolean success = SensorManager.getRotationMatrix(rotationMatrix, null, lastGravity, lastGeomagnetic);
            if (success) {
                SensorManager.getOrientation(rotationMatrix, orientationAngles);
                yaw = (float) Math.toDegrees(orientationAngles[0]);
                pitch = (float) Math.toDegrees(orientationAngles[1]);
                roll = (float) Math.toDegrees(orientationAngles[2]);
                isOrientActive = true;
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    public synchronized void checkLivenessAndRestart() {
        if (!isRunning) return;
        long now = System.currentTimeMillis();
        if (lastEventTimestamp > 0 && (now - lastEventTimestamp) > 15000) {
            logger.w(TAG, "Inertial events stalled (>15s). Re-registering sensor listeners...");
            stop();
            start();
        }
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        logger.i(TAG, "Stopping IMU inertial collector...");

        if (sensorManager != null) {
            try {
                sensorManager.unregisterListener(this);
            } catch (Throwable t) {
                logger.w(TAG, "Error unregistering sensors: " + t.getMessage());
            }
        }
    }

    public InertialTelemetry getLastSnapshot() {
        return lastSnapshot;
    }

    public boolean isRunning() {
        return isRunning;
    }
}
