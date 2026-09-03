package com.example.service.telemetry;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.GnssStatus;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import com.example.model.telemetry.LocationTelemetry;
import com.example.utils.AppLogger;

/**
 * Robust, non-crashing GPS & Network Location Collector.
 * Automatically recovers from signal dropouts, permissions issues, and sensor stalls.
 */
public class LocationCollector implements LocationListener {
    private static final String TAG = "LocationCollector";
    private static final long MIN_TIME_MS = 1000; // 1 second
    private static final float MIN_DISTANCE_M = 0.0f;

    private final Context context;
    private final LocationManager locationManager;
    private final AppLogger logger = AppLogger.getInstance();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile LocationTelemetry lastLocation = LocationTelemetry.empty();
    private volatile boolean isRunning = false;
    private volatile int satellitesInView = 0;
    private volatile long lastUpdateTimestamp = 0;
    private volatile int intervalSeconds = 1;

    private Object gnssCallback; // GnssStatus.Callback or GpsStatus.Listener depending on API

    public LocationCollector(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    public synchronized void setIntervalSeconds(int seconds) {
        this.intervalSeconds = Math.max(1, seconds);
        if (isRunning) {
            // Re-register providers with the new interval
            registerProviders();
        }
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        logger.i(TAG, "Starting robust location collector with interval=" + intervalSeconds + "s...");
        registerProviders();
        registerGnssStatus();
        fetchLastKnownLocation();
    }

    @SuppressLint("MissingPermission")
    private void registerProviders() {
        if (locationManager == null) {
            logger.w(TAG, "LocationManager is null on this device.");
            return;
        }

        if (!hasLocationPermission()) {
            logger.w(TAG, "Location permissions not granted yet. Waiting for user grant.");
            return;
        }

        long minTimeMs = Math.max(1, intervalSeconds) * 1000L;

        // Unregister existing listeners first if re-registering
        try {
            locationManager.removeUpdates(this);
        } catch (Throwable ignored) {}

        // 1. GPS Provider (High accuracy / Direct Satellite)
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        minTimeMs,
                        MIN_DISTANCE_M,
                        this,
                        Looper.getMainLooper()
                );
                logger.i(TAG, "Subscribed to GPS_PROVIDER updates (" + minTimeMs + "ms interval).");
            } else {
                logger.w(TAG, "GPS_PROVIDER is currently disabled in system settings.");
            }
        } catch (SecurityException se) {
            logger.w(TAG, "SecurityException registering GPS: " + se.getMessage());
        } catch (Exception e) {
            logger.e(TAG, "Error requesting GPS updates: " + e.getMessage());
        }

        // 2. Network Provider (Cell/Wi-Fi positioning - fast initial fix & works indoors)
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        minTimeMs,
                        MIN_DISTANCE_M,
                        this,
                        Looper.getMainLooper()
                );
                logger.i(TAG, "Subscribed to NETWORK_PROVIDER fallback updates (" + minTimeMs + "ms interval).");
            }
        } catch (SecurityException se) {
            logger.w(TAG, "SecurityException registering Network Location: " + se.getMessage());
        } catch (Exception e) {
            logger.w(TAG, "Network provider not available: " + e.getMessage());
        }

        // 3. Passive Provider (Captures locations requested by other apps/system)
        try {
            if (locationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.PASSIVE_PROVIDER,
                        minTimeMs,
                        MIN_DISTANCE_M,
                        this,
                        Looper.getMainLooper()
                );
                logger.i(TAG, "Subscribed to PASSIVE_PROVIDER updates.");
            }
        } catch (Throwable ignored) {}
    }

    @SuppressLint("MissingPermission")
    private void registerGnssStatus() {
        if (locationManager == null || !hasLocationPermission()) return;

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                GnssStatus.Callback callback = new GnssStatus.Callback() {
                    @Override
                    public void onSatelliteStatusChanged(GnssStatus status) {
                        if (status != null) {
                            int count = 0;
                            for (int i = 0; i < status.getSatelliteCount(); i++) {
                                if (status.usedInFix(i)) {
                                    count++;
                                }
                            }
                            satellitesInView = count > 0 ? count : status.getSatelliteCount();
                        }
                    }
                };
                locationManager.registerGnssStatusCallback(callback, mainHandler);
                gnssCallback = callback;
            }
        } catch (Throwable t) {
            logger.w(TAG, "GNSS status callback registration notice: " + t.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    private void fetchLastKnownLocation() {
        if (locationManager == null || !hasLocationPermission()) return;
        try {
            Location gpsLoc = null;
            try { gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER); } catch (Throwable ignored) {}

            Location netLoc = null;
            try { netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER); } catch (Throwable ignored) {}

            Location passiveLoc = null;
            try { passiveLoc = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER); } catch (Throwable ignored) {}

            Location fusedLoc = null;
            try { fusedLoc = locationManager.getLastKnownLocation("fused"); } catch (Throwable ignored) {}

            Location best = null;
            if (gpsLoc != null) best = gpsLoc;
            if (netLoc != null && (best == null || netLoc.getTime() > best.getTime())) best = netLoc;
            if (fusedLoc != null && (best == null || fusedLoc.getTime() > best.getTime())) best = fusedLoc;
            if (passiveLoc != null && (best == null || passiveLoc.getTime() > best.getTime())) best = passiveLoc;

            if (best != null) {
                logger.i(TAG, "Initial cached location loaded from " + best.getProvider() + " (" + best.getLatitude() + ", " + best.getLongitude() + ")");
                updateFromLocation(best, false);
            } else {
                logger.w(TAG, "No cached location found yet. Awaiting fresh GPS/Network position fix.");
            }
        } catch (Throwable t) {
            logger.w(TAG, "Error querying last known location: " + t.getMessage());
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            updateFromLocation(location, true);
        }
    }

    private void updateFromLocation(Location loc, boolean isLiveFix) {
        lastUpdateTimestamp = System.currentTimeMillis();
        double lat = loc.getLatitude();
        double lon = loc.getLongitude();
        double alt = loc.hasAltitude() ? loc.getAltitude() : 0.0;
        float speed = loc.hasSpeed() ? loc.getSpeed() : 0.0f;
        float bearing = loc.hasBearing() ? loc.getBearing() : 0.0f;
        float accuracy = loc.hasAccuracy() ? loc.getAccuracy() : 999.0f;
        String provider = loc.getProvider() != null ? loc.getProvider() : "gps";

        lastLocation = new LocationTelemetry(
                lat,
                lon,
                alt,
                speed,
                bearing,
                accuracy,
                satellitesInView,
                provider,
                loc.getTime() > 0 ? loc.getTime() : System.currentTimeMillis(),
                isLiveFix
        );
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    @Override
    public void onProviderEnabled(String provider) {
        logger.i(TAG, "Location provider enabled: " + provider);
        registerProviders();
    }

    @Override
    public void onProviderDisabled(String provider) {
        logger.w(TAG, "Location provider disabled: " + provider);
    }

    /**
     * Watchdog check: If no location updates received in the last 20 seconds, reconnect safely.
     */
    public synchronized void checkLivenessAndRestart() {
        if (!isRunning) return;
        long now = System.currentTimeMillis();
        if (lastUpdateTimestamp > 0 && (now - lastUpdateTimestamp) > 20000) {
            logger.w(TAG, "Location updates stalled (>20s). Restarting location listeners...");
            stop();
            start();
        }
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        logger.i(TAG, "Stopping location collector...");

        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Throwable t) {
                logger.w(TAG, "Error removing location updates: " + t.getMessage());
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && gnssCallback instanceof GnssStatus.Callback) {
                try {
                    locationManager.unregisterGnssStatusCallback((GnssStatus.Callback) gnssCallback);
                } catch (Throwable ignored) {}
            }
        }
    }

    public LocationTelemetry getLastLocation() {
        return lastLocation;
    }

    public boolean isRunning() {
        return isRunning;
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
