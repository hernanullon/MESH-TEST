package com.example.service.telemetry;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import com.example.model.telemetry.DeviceStatusTelemetry;
import com.example.utils.AppLogger;
import java.io.File;

/**
 * Collector for Device hardware status: Battery level/temp/voltage, RAM usage, Storage, and Thermal status.
 */
public class DeviceStatusCollector {
    private static final String TAG = "DeviceStatusCollector";

    private final Context context;
    private final AppLogger logger = AppLogger.getInstance();
    private volatile DeviceStatusTelemetry lastSnapshot = DeviceStatusTelemetry.empty();

    public DeviceStatusCollector(Context context) {
        this.context = context.getApplicationContext();
    }

    public DeviceStatusTelemetry sample() {
        try {
            // 1. Battery Telemetry via sticky Intent
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryIntent = context.registerReceiver(null, filter);

            int level = 100;
            int scale = 100;
            int status = BatteryManager.BATTERY_STATUS_UNKNOWN;
            int chargePlug = 0;
            int tempTenths = 250; // 25.0 C
            int voltageMv = 4000;
            int health = BatteryManager.BATTERY_HEALTH_GOOD;

            if (batteryIntent != null) {
                level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                chargePlug = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
                tempTenths = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250);
                voltageMv = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 4000);
                health = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_GOOD);
            }

            int batteryPercent = (level >= 0 && scale > 0) ? (int) ((level / (float) scale) * 100) : 100;
            boolean isCharging = (status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);

            String chargeSource = "Battery";
            if (chargePlug == BatteryManager.BATTERY_PLUGGED_AC) chargeSource = "AC";
            else if (chargePlug == BatteryManager.BATTERY_PLUGGED_USB) chargeSource = "USB";
            else if (chargePlug == BatteryManager.BATTERY_PLUGGED_WIRELESS) chargeSource = "Wireless";

            float batteryTempC = tempTenths / 10.0f;

            String batteryHealthStr = "Good";
            switch (health) {
                case BatteryManager.BATTERY_HEALTH_OVERHEAT: batteryHealthStr = "Overheat"; break;
                case BatteryManager.BATTERY_HEALTH_DEAD: batteryHealthStr = "Dead"; break;
                case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: batteryHealthStr = "OverVoltage"; break;
                case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: batteryHealthStr = "Failure"; break;
                case BatteryManager.BATTERY_HEALTH_COLD: batteryHealthStr = "Cold"; break;
            }

            // 2. RAM Telemetry
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
            long freeRamMb = 512;
            long totalRamMb = 2048;
            if (activityManager != null) {
                activityManager.getMemoryInfo(memInfo);
                freeRamMb = memInfo.availMem / (1024 * 1024);
                totalRamMb = memInfo.totalMem / (1024 * 1024);
            }

            // 3. Storage Telemetry
            File path = Environment.getDataDirectory();
            StatFs stat = new StatFs(path.getPath());
            long blockSize = stat.getBlockSizeLong();
            long availableBlocks = stat.getAvailableBlocksLong();
            long totalBlocks = stat.getBlockCountLong();

            float freeStorageGb = (availableBlocks * blockSize) / (1024.0f * 1024.0f * 1024.0f);
            float totalStorageGb = (totalBlocks * blockSize) / (1024.0f * 1024.0f * 1024.0f);

            // 4. Thermal Status (API 29+)
            String thermalStatus = "Normal";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    int tStatus = pm.getCurrentThermalStatus();
                    switch (tStatus) {
                        case PowerManager.THERMAL_STATUS_LIGHT: thermalStatus = "Light"; break;
                        case PowerManager.THERMAL_STATUS_MODERATE: thermalStatus = "Moderate"; break;
                        case PowerManager.THERMAL_STATUS_SEVERE: thermalStatus = "Severe"; break;
                        case PowerManager.THERMAL_STATUS_CRITICAL: thermalStatus = "Critical"; break;
                        case PowerManager.THERMAL_STATUS_EMERGENCY: thermalStatus = "Emergency"; break;
                        case PowerManager.THERMAL_STATUS_SHUTDOWN: thermalStatus = "Shutdown"; break;
                    }
                }
            }

            lastSnapshot = new DeviceStatusTelemetry(
                    batteryPercent,
                    isCharging,
                    chargeSource,
                    batteryTempC,
                    voltageMv,
                    batteryHealthStr,
                    freeRamMb,
                    totalRamMb,
                    freeStorageGb,
                    totalStorageGb,
                    thermalStatus,
                    System.currentTimeMillis()
            );
        } catch (Throwable t) {
            logger.w(TAG, "Error sampling device telemetry: " + t.getMessage());
        }

        return lastSnapshot;
    }

    public DeviceStatusTelemetry getLastSnapshot() {
        return lastSnapshot;
    }
}
