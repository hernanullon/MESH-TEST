package com.example.model.telemetry;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Locale;

/**
 * Immutable data holder for Device status telemetry (Battery, Thermal, RAM, Storage).
 */
public class DeviceStatusTelemetry {
    private final int batteryLevelPercent;
    private final boolean isCharging;
    private final String chargeSource; // AC, USB, Wireless, Battery
    private final float batteryTemperatureC;
    private final int batteryVoltageMv;
    private final String batteryHealth;

    private final long freeRamMb;
    private final long totalRamMb;
    private final int ramUsagePercent;

    private final float freeStorageGb;
    private final float totalStorageGb;

    private final String thermalStatus;
    private final long timestamp;

    public DeviceStatusTelemetry(
            int batteryLevelPercent,
            boolean isCharging,
            String chargeSource,
            float batteryTemperatureC,
            int batteryVoltageMv,
            String batteryHealth,
            long freeRamMb,
            long totalRamMb,
            float freeStorageGb,
            float totalStorageGb,
            String thermalStatus,
            long timestamp
    ) {
        this.batteryLevelPercent = batteryLevelPercent;
        this.isCharging = isCharging;
        this.chargeSource = chargeSource != null ? chargeSource : "Unknown";
        this.batteryTemperatureC = batteryTemperatureC;
        this.batteryVoltageMv = batteryVoltageMv;
        this.batteryHealth = batteryHealth != null ? batteryHealth : "Good";

        this.freeRamMb = freeRamMb;
        this.totalRamMb = totalRamMb;
        this.ramUsagePercent = totalRamMb > 0 ? (int) (((totalRamMb - freeRamMb) * 100) / totalRamMb) : 0;

        this.freeStorageGb = freeStorageGb;
        this.totalStorageGb = totalStorageGb;
        this.thermalStatus = thermalStatus != null ? thermalStatus : "Normal";
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    public static DeviceStatusTelemetry empty() {
        return new DeviceStatusTelemetry(100, false, "Battery", 25.0f, 4000, "Good", 1024, 4096, 16.0f, 64.0f, "Normal", System.currentTimeMillis());
    }

    public int getBatteryLevelPercent() { return batteryLevelPercent; }
    public boolean isCharging() { return isCharging; }
    public String getChargeSource() { return chargeSource; }
    public float getBatteryTemperatureC() { return batteryTemperatureC; }
    public int getBatteryVoltageMv() { return batteryVoltageMv; }
    public String getBatteryHealth() { return batteryHealth; }

    public long getFreeRamMb() { return freeRamMb; }
    public long getTotalRamMb() { return totalRamMb; }
    public int getRamUsagePercent() { return ramUsagePercent; }

    public float getFreeStorageGb() { return freeStorageGb; }
    public float getTotalStorageGb() { return totalStorageGb; }
    public String getThermalStatus() { return thermalStatus; }
    public long getTimestamp() { return timestamp; }

    public String getBatteryFormatted() {
        return String.format(Locale.US, "%d%% (%s%s) - %.1f°C",
                batteryLevelPercent,
                isCharging ? "Charging " : "",
                chargeSource,
                batteryTemperatureC
        );
    }

    public String getMemoryFormatted() {
        return String.format(Locale.US, "RAM: %d%% (%d MB free) | Disk: %.1f/%.1f GB",
                ramUsagePercent, freeRamMb, freeStorageGb, totalStorageGb);
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            JSONObject battery = new JSONObject();
            battery.put("level_pct", batteryLevelPercent);
            battery.put("is_charging", isCharging);
            battery.put("charge_source", chargeSource);
            battery.put("temp_c", (double) Math.round(batteryTemperatureC * 10) / 10);
            battery.put("voltage_mv", batteryVoltageMv);
            battery.put("health", batteryHealth);
            obj.put("battery", battery);

            JSONObject sys = new JSONObject();
            sys.put("free_ram_mb", freeRamMb);
            sys.put("total_ram_mb", totalRamMb);
            sys.put("ram_usage_pct", ramUsagePercent);
            sys.put("free_storage_gb", (double) Math.round(freeStorageGb * 10) / 10);
            sys.put("total_storage_gb", (double) Math.round(totalStorageGb * 10) / 10);
            sys.put("thermal", thermalStatus);
            obj.put("system", sys);

            obj.put("ts", timestamp);
        } catch (JSONException ignored) {}
        return obj;
    }
}
