package com.example.wifi;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.RequiresApi;
import com.example.model.HotspotInfo;
import com.example.receiver.AdminReceiver;
import com.example.utils.AppLogger;
import com.example.utils.NetworkUtils;
import java.lang.reflect.Method;
import java.util.concurrent.Executor;

/**
 * Manages the programmatic creation, credentials inspection, and deletion of a Local-Only Wi-Fi Hotspot
 * with support for custom fixed SSID/password (via Android R+ SoftApConfiguration or Device Owner mode).
 */
public class LocalHotspotManager {
    private static final String TAG = "LocalHotspotManager";
    private final Context context;
    private final WifiManager wifiManager;
    private final DevicePolicyManager dpm;
    private final AppLogger logger = AppLogger.getInstance();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WifiManager.LocalOnlyHotspotReservation hotspotReservation;
    private HotspotInfo currentHotspotInfo = HotspotInfo.disabled();
    private HotspotStateListener listener;
    private long lastFailureTimestamp = 0;
    private String lastFailureReason = "";
    private boolean isStarting = false;
    private long startingTimestamp = 0;
    private long hotspotStartTime = 0;

    // Configured desired fixed credentials
    private String preferredSsid = "Direct-Mesh-Master";
    private String preferredPassphrase = "MeshPassword123";

    public interface HotspotStateListener {
        void onHotspotStateChanged(HotspotInfo info);
    }

    public LocalHotspotManager(Context context) {
        this.context = context.getApplicationContext();
        this.wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        this.dpm = (DevicePolicyManager) this.context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    public void setPreferredCredentials(String ssid, String passphrase) {
        if (ssid != null && !ssid.trim().isEmpty()) {
            this.preferredSsid = ssid.trim();
        }
        if (passphrase != null && !passphrase.trim().isEmpty()) {
            this.preferredPassphrase = passphrase.trim();
        }
    }

    public String getPreferredSsid() { return preferredSsid; }
    public String getPreferredPassphrase() { return preferredPassphrase; }

    public boolean isDeviceOwner() {
        if (dpm == null) return false;
        try {
            return dpm.isDeviceOwnerApp(context.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isDeviceAdmin() {
        if (dpm == null) return false;
        try {
            ComponentName adminComponent = new ComponentName(context, AdminReceiver.class);
            return dpm.isAdminActive(adminComponent);
        } catch (Throwable t) {
            return false;
        }
    }

    public void setListener(HotspotStateListener listener) {
        this.listener = listener;
        if (listener != null) {
            listener.onHotspotStateChanged(currentHotspotInfo);
        }
    }

    public HotspotInfo getCurrentHotspotInfo() {
        return currentHotspotInfo;
    }

    public boolean isHotspotActive() {
        if (currentHotspotInfo != null && currentHotspotInfo.isRunning()) {
            if (hotspotReservation != null || isLegacyApActive()) {
                return true;
            }
        }
        return false;
    }

    public boolean isLegacyApActive() {
        if (wifiManager == null) return false;
        try {
            Method method = wifiManager.getClass().getMethod("isWifiApEnabled");
            return (boolean) method.invoke(wifiManager);
        } catch (Throwable ignore) {
            return false;
        }
    }

    public boolean isStarting() {
        if (isStarting) {
            if (System.currentTimeMillis() - startingTimestamp > 12000) {
                isStarting = false;
                return false;
            }
            return true;
        }
        return currentHotspotInfo != null && currentHotspotInfo.getState() == HotspotInfo.State.STARTING;
    }

    public boolean hasRecentFailure(long windowMs) {
        return (System.currentTimeMillis() - lastFailureTimestamp) < windowMs;
    }

    public String getLastFailureReason() {
        return lastFailureReason;
    }

    /**
     * Autonomous Watchdog probe called periodically by the background scheduler.
     * 1. Detects silent drops in hardware AP interface.
     * 2. Proactively refreshes SoftAP before Android's 15-20 min idle timeout triggers.
     * 3. Automatically spins up the hotspot if stopped or failed.
     */
    public synchronized void checkAndReviveIfNeeded(int connectedClientsCount) {
        if (isStarting()) {
            return;
        }

        // 1. If currently in RUNNING state:
        if (isHotspotActive()) {
            long activeDuration = System.currentTimeMillis() - hotspotStartTime;

            // Check if hardware interface silently disappeared
            if (activeDuration > 20000 && !NetworkUtils.isLocalApInterfaceUp()) {
                logger.w(TAG, "[Watchdog] Interfaz física de SoftAP desapareció en el hardware. Reiniciando Hotspot...");
                forceRestartHotspot();
                return;
            }

            // Proactive SoftAP Refresh: Android OS tears down LocalOnlyHotspot after 10-20 min of no clients.
            // If running for > 8 minutes with 0 connected peers, refresh cleanly to reset OS idle timer.
            if (connectedClientsCount == 0 && activeDuration > 8 * 60 * 1000) {
                logger.i(TAG, "[Watchdog Keep-Alive] Refresco proactivo de Red Local (previene timeout de 20 min de Android)...");
                forceRestartHotspot();
                return;
            }
            return;
        }

        // 2. If NOT running, start hotspot automatically (respecting brief failure cooldown)
        if (!hasRecentFailure(5000)) {
            logger.i(TAG, "[Watchdog] Red Local Wi-Fi inactiva. Levantando automáticamente...");
            startLocalHotspot();
        }
    }

    /**
     * Forces a clean tear-down of any lingering OS reservation and initiates a fresh Hotspot start.
     */
    public synchronized void forceRestartHotspot() {
        logger.i(TAG, "Ejecutando reinicio completo forzado de la Red Local Wi-Fi...");
        isStarting = false;
        if (hotspotReservation != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    hotspotReservation.close();
                }
            } catch (Throwable ignored) {}
            hotspotReservation = null;
        }

        try {
            Method method = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            method.invoke(wifiManager, null, false);
        } catch (Throwable ignored) {}

        updateState(HotspotInfo.disabled());
        mainHandler.postDelayed(this::startLocalHotspot, 350);
    }

    /**
     * Programmatically creates a local Wi-Fi Hotspot network in the background.
     * Uses custom fixed SSID & password when supported by hardware/Device Owner.
     */
    public synchronized void startLocalHotspot() {
        if (isHotspotActive()) {
            logger.d(TAG, "Local Hotspot is already active: SSID=" + currentHotspotInfo.getSsid());
            return;
        }

        if (isStarting()) {
            logger.d(TAG, "Local Hotspot is currently in starting state.");
            return;
        }

        if (wifiManager == null) {
            logger.w(TAG, "WifiManager is not available on this device.");
            updateState(HotspotInfo.failed("WifiManager unavailable"));
            return;
        }

        // Clean up any stale reservation if it existed
        if (hotspotReservation != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    hotspotReservation.close();
                }
            } catch (Throwable ignore) {}
            hotspotReservation = null;
        }

        isStarting = true;
        startingTimestamp = System.currentTimeMillis();
        logger.i(TAG, "Iniciando creación de Red Local Wi-Fi (SSID deseado: " + preferredSsid + ")...");
        updateState(HotspotInfo.starting());

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startHotspotApi30WithCustomConfig();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startLocalOnlyHotspotApi26();
            } else {
                startLegacySoftAp();
            }
        } catch (Throwable t) {
            isStarting = false;
            lastFailureTimestamp = System.currentTimeMillis();
            lastFailureReason = "Aviso inicio Red Local: " + t.getMessage();
            logger.w(TAG, lastFailureReason);
            updateState(HotspotInfo.failed(lastFailureReason));
        }
    }

    /**
     * On Android 11+ (API 30+), attempts to request the Local Hotspot with custom SoftApConfiguration.
     * If security restricts custom config without NEARBY_WIFI_DEVICES/Privileged permissions, falls back to standard.
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void startHotspotApi30WithCustomConfig() {
        boolean customAttempted = false;
        try {
            SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();
            try {
                Method setSsidMethod = configBuilder.getClass().getMethod("setSsid", String.class);
                setSsidMethod.invoke(configBuilder, preferredSsid);
            } catch (Throwable ignore) {}

            if (preferredPassphrase != null && preferredPassphrase.length() >= 8) {
                configBuilder.setPassphrase(preferredPassphrase, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
            } else {
                configBuilder.setPassphrase(null, SoftApConfiguration.SECURITY_TYPE_OPEN);
            }
            SoftApConfiguration softApConfig = configBuilder.build();

            Executor executor = mainHandler::post;
            WifiManager.LocalOnlyHotspotCallback callback = createHotspotCallback();

            // Try reflection invocation of startLocalOnlyHotspot(SoftApConfiguration, Executor, Callback)
            Method method = WifiManager.class.getMethod(
                    "startLocalOnlyHotspot",
                    SoftApConfiguration.class,
                    Executor.class,
                    WifiManager.LocalOnlyHotspotCallback.class
            );
            method.invoke(wifiManager, softApConfig, executor, callback);
            customAttempted = true;
            logger.i(TAG, "Invocada creación de SoftAP con SSID y Clave fijas configuradas...");
        } catch (Throwable t) {
            logger.d(TAG, "Fallback a startLocalOnlyHotspot estándar: " + t.getMessage());
            startLocalOnlyHotspotApi26();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startLocalOnlyHotspotApi26() {
        try {
            wifiManager.startLocalOnlyHotspot(createHotspotCallback(), mainHandler);
        } catch (Throwable t) {
            isStarting = false;
            lastFailureTimestamp = System.currentTimeMillis();
            lastFailureReason = t.getMessage();
            logger.w(TAG, "Excepción startLocalOnlyHotspot: " + t.getMessage());
            updateState(HotspotInfo.failed(t.getMessage()));
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private WifiManager.LocalOnlyHotspotCallback createHotspotCallback() {
        return new WifiManager.LocalOnlyHotspotCallback() {
            @Override
            public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                super.onStarted(reservation);
                try {
                    isStarting = false;
                    hotspotStartTime = System.currentTimeMillis();
                    lastFailureTimestamp = 0;
                    lastFailureReason = "";
                    hotspotReservation = reservation;
                    String ssid = preferredSsid;
                    String passphrase = preferredPassphrase;

                    // Extract actual Wi-Fi credentials assigned by Android
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            android.net.wifi.SoftApConfiguration config = reservation.getSoftApConfiguration();
                            if (config != null) {
                                if (config.getSsid() != null && !config.getSsid().isEmpty()) {
                                    ssid = config.getSsid();
                                }
                                if (config.getPassphrase() != null && !config.getPassphrase().isEmpty()) {
                                    passphrase = config.getPassphrase();
                                }
                            }
                        } catch (Throwable t) {
                            logger.d(TAG, "SoftApConfiguration extraction fallback: " + t.getMessage());
                        }
                    }

                    if (passphrase == null || passphrase.isEmpty()) {
                        try {
                            @SuppressWarnings("deprecation")
                            WifiConfiguration wifiConfig = reservation.getWifiConfiguration();
                            if (wifiConfig != null) {
                                if (wifiConfig.SSID != null) ssid = wifiConfig.SSID.replace("\"", "");
                                if (wifiConfig.preSharedKey != null) passphrase = wifiConfig.preSharedKey.replace("\"", "");
                            }
                        } catch (Throwable t) {
                            logger.d(TAG, "WifiConfiguration extraction fallback: " + t.getMessage());
                        }
                    }

                    String localIp = NetworkUtils.getLocalIpAddress();
                    if (localIp == null || localIp.equals("127.0.0.1")) {
                        localIp = "192.168.43.1";
                    }

                    logger.s(TAG, "¡Red Wi-Fi Local CREADA exitosamente!");
                    logger.s(TAG, "SSID: [" + ssid + "] | Clave: [" + (passphrase == null || passphrase.isEmpty() ? "Abierta" : passphrase) + "] | IP Nodo: " + localIp);

                    updateState(HotspotInfo.running(ssid, passphrase != null ? passphrase : "", localIp));
                } catch (Throwable t) {
                    logger.w(TAG, "Error procesando onStarted de Hotspot: " + t.getMessage());
                }
            }

            @Override
            public void onStopped() {
                super.onStopped();
                try {
                    isStarting = false;
                    hotspotStartTime = 0;
                    logger.w(TAG, "Local Wi-Fi Hotspot was STOPPED by system (idle timeout or Wi-Fi sleep). Watchdog will recover.");
                    hotspotReservation = null;
                    updateState(HotspotInfo.disabled());
                } catch (Throwable t) {
                    logger.w(TAG, "Error in onStopped: " + t.getMessage());
                }
            }

            @Override
            public void onFailed(int reason) {
                super.onFailed(reason);
                try {
                    isStarting = false;
                    hotspotStartTime = 0;
                    lastFailureTimestamp = System.currentTimeMillis();
                    String reasonText;
                    switch (reason) {
                        case ERROR_NO_CHANNEL:
                            reasonText = "No Wi-Fi channels available for SoftAP";
                            break;
                        case ERROR_GENERIC:
                            reasonText = "SoftAP hardware busy or temporarily unavailable";
                            break;
                        case ERROR_INCOMPATIBLE_MODE:
                            reasonText = "Wi-Fi mode incompatible or AP in use";
                            break;
                        case ERROR_TETHERING_DISALLOWED:
                            reasonText = "Tethering restricted by system policy";
                            break;
                        default:
                            reasonText = "Hotspot error (code " + reason + ")";
                            break;
                    }
                    lastFailureReason = reasonText;
                    logger.w(TAG, "Local Hotspot Failed: " + reasonText);
                    hotspotReservation = null;
                    updateState(HotspotInfo.failed(reasonText));
                } catch (Throwable t) {
                    logger.w(TAG, "Error in onFailed: " + t.getMessage());
                }
            }
        };
    }

    /**
     * Fallback for older Android versions or devices using reflection on setWifiApEnabled.
     */
    private void startLegacySoftAp() {
        try {
            Method method = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = preferredSsid;
            config.preSharedKey = preferredPassphrase;
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);

            boolean success = (boolean) method.invoke(wifiManager, config, true);
            if (success) {
                String localIp = "192.168.43.1";
                logger.s(TAG, "Legacy SoftAP started successfully: SSID=" + config.SSID);
                updateState(HotspotInfo.running(config.SSID, config.preSharedKey, localIp));
            } else {
                updateState(HotspotInfo.failed("Failed to enable legacy SoftAP"));
            }
        } catch (Exception e) {
            logger.e(TAG, "Reflection SoftAP error: " + e.getMessage());
            updateState(HotspotInfo.failed("Legacy SoftAP not supported: " + e.getMessage()));
        }
    }

    /**
     * Programmatically destroys / stops the local Wi-Fi Hotspot network.
     */
    public synchronized void stopLocalHotspot() {
        logger.i(TAG, "Stopping and removing Local Wi-Fi network...");
        if (hotspotReservation != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    hotspotReservation.close();
                }
                logger.s(TAG, "Local Hotspot reservation closed.");
            } catch (Exception e) {
                logger.e(TAG, "Error closing hotspot reservation: " + e.getMessage());
            } finally {
                hotspotReservation = null;
            }
        }

        // Try reflection stop for legacy AP if applicable
        try {
            Method method = wifiManager.getClass().getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            method.invoke(wifiManager, null, false);
        } catch (Exception ignored) {}

        updateState(HotspotInfo.disabled());
    }

    private void updateState(HotspotInfo info) {
        this.currentHotspotInfo = info;
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onHotspotStateChanged(info);
            }
        });
    }
}
