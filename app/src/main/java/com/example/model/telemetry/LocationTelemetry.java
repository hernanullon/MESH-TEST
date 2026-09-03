package com.example.model.telemetry;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Locale;

/**
 * Immutable/Thread-safe data holder for GPS location telemetry.
 */
public class LocationTelemetry {
    private final double latitude;
    private final double longitude;
    private final double altitude;
    private final float speedKmh;
    private final float speedMs;
    private final float bearing;
    private final float accuracy;
    private final int satellites;
    private final String provider;
    private final long timestamp;
    private final boolean hasFix;

    public LocationTelemetry(
            double latitude,
            double longitude,
            double altitude,
            float speedMs,
            float bearing,
            float accuracy,
            int satellites,
            String provider,
            long timestamp,
            boolean hasFix
    ) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.speedMs = speedMs;
        this.speedKmh = speedMs * 3.6f;
        this.bearing = bearing;
        this.accuracy = accuracy;
        this.satellites = satellites;
        this.provider = provider != null ? provider : "unknown";
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
        this.hasFix = hasFix;
    }

    public static LocationTelemetry empty() {
        return new LocationTelemetry(0.0, 0.0, 0.0, 0f, 0f, 0f, 0, "none", System.currentTimeMillis(), false);
    }

    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public double getAltitude() { return altitude; }
    public float getSpeedKmh() { return speedKmh; }
    public float getSpeedMs() { return speedMs; }
    public float getBearing() { return bearing; }
    public float getAccuracy() { return accuracy; }
    public int getSatellites() { return satellites; }
    public String getProvider() { return provider; }
    public long getTimestamp() { return timestamp; }
    public boolean hasFix() { return hasFix; }

    public boolean isKnownPosition() {
        return Math.abs(latitude) > 0.000001 || Math.abs(longitude) > 0.000001;
    }

    public String getFixStatusDescription() {
        if (hasFix) {
            if ("gps".equalsIgnoreCase(provider)) {
                return satellites > 0 ? "GPS Locked (" + satellites + " Sats)" : "GPS Locked";
            } else if ("network".equalsIgnoreCase(provider)) {
                return "Network Fix (Cell/Wi-Fi)";
            } else if ("fused".equalsIgnoreCase(provider)) {
                return "Fused Location Fix";
            }
            return "Active Fix (" + provider + ")";
        } else if (isKnownPosition()) {
            return "Cached Fix (" + provider + ")";
        } else {
            return "Searching Fix (Awaiting Satellites)";
        }
    }

    public String getCoordinatesFormatted() {
        if (!isKnownPosition()) return "0.000000, 0.000000 (Searching...)";
        return String.format(Locale.US, "%.6f, %.6f", latitude, longitude);
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("lat", latitude);
            obj.put("lon", longitude);
            obj.put("alt", altitude);
            obj.put("speed_kmh", (double) Math.round(speedKmh * 100) / 100);
            obj.put("speed_ms", (double) Math.round(speedMs * 100) / 100);
            obj.put("bearing", (double) Math.round(bearing * 10) / 10);
            obj.put("accuracy", (double) Math.round(accuracy * 10) / 10);
            obj.put("satellites", satellites);
            obj.put("provider", provider);
            obj.put("has_fix", hasFix);
            obj.put("ts", timestamp);
        } catch (JSONException ignored) {}
        return obj;
    }
}
