package com.example.wifi;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import androidx.annotation.RequiresApi;
import com.example.model.HotspotInfo;
import com.example.receiver.AdminReceiver;
import com.example.utils.AppLogger;
import com.example.utils.NetworkUtils;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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

    private final Runnable startingTimeoutRunnable = () -> {
        if (isStarting) {
            logger.w(TAG, "Watchdog timeout esperando activación de Hotspot (8s). Recuperando...");
            isStarting = false;
            if (hotspotReservation == null && !isHotspotActive()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        startLocalOnlyHotspotApi26();
                        return;
                    } catch (Throwable t) {
                        logger.e(TAG, "Fallback startLocalOnlyHotspotApi26 error: " + t.getMessage());
                    }
                }
                updateState(HotspotInfo.failed("Tiempo de espera agotado iniciando Hotspot"));
            }
        }
    };

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
            if (hotspotReservation != null || isLegacyApActive() || NetworkUtils.isLocalApInterfaceUp()) {
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

    /**
     * When running in Device Owner mode, automatically grants all required runtime
     * permissions without user prompts.
     */
    public void grantAllDeviceOwnerPermissions() {
        if (dpm == null) return;
        try {
            if (!dpm.isDeviceOwnerApp(context.getPackageName())) return;
            ComponentName adminComponent = new ComponentName(context, AdminReceiver.class);
            String pkg = context.getPackageName();
            String[] permissions = new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    "android.permission.NEARBY_WIFI_DEVICES",
                    "android.permission.POST_NOTIFICATIONS"
            };
            for (String perm : permissions) {
                try {
                    dpm.setPermissionGrantState(adminComponent, pkg, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
                } catch (Throwable ignored) {}
            }
            logger.s(TAG, "Permisos de tiempo de ejecución Device Owner auto-concedidos.");
        } catch (Throwable t) {
            logger.d(TAG, "grantAllDeviceOwnerPermissions: " + t.getMessage());
        }
    }

    /**
     * Programmatically forces system global / secure settings to 2.4 GHz band and Maximum Compatibility mode.
     * Android and OEM ROMs (Samsung, Xiaomi, Pixel) respect these flags when initiating Tethering / SoftAP.
     */
    public void enforce2GhzSystemSettings() {
        try {
            Settings.Global.putInt(context.getContentResolver(), "wifi_ap_band", 0); // 0 = 2.4 GHz
        } catch (Throwable ignored) {}
        try {
            Settings.Global.putInt(context.getContentResolver(), "wifi_ap_max_compatibility", 1); // 1 = Force 2.4 GHz
        } catch (Throwable ignored) {}
        try {
            Settings.Secure.putInt(context.getContentResolver(), "wifi_ap_band", 0);
        } catch (Throwable ignored) {}
        try {
            Settings.System.putInt(context.getContentResolver(), "wifi_ap_band", 0);
        } catch (Throwable ignored) {}
    }

    /**
     * Pre-configures the SoftAP system configuration for 2.4 GHz band and custom SSID/Passphrase
     * if supported by the OS (Android 11+ via setSoftApConfiguration, and legacy setWifiApConfiguration).
     */
    public void configureSoftAp2Ghz() {
        // Android 11+ SoftApConfiguration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                SoftApConfiguration.Builder builder = new SoftApConfiguration.Builder();
                try {
                    Constructor<?> copyCtor = SoftApConfiguration.Builder.class.getConstructor(SoftApConfiguration.class);
                    Method getMethod = wifiManager.getClass().getMethod("getSoftApConfiguration");
                    Object current = getMethod.invoke(wifiManager);
                    if (current instanceof SoftApConfiguration) {
                        builder = (SoftApConfiguration.Builder) copyCtor.newInstance((SoftApConfiguration) current);
                    }
                } catch (Throwable ignored) {}

                int band2Ghz = 1;
                try {
                    Field bandField = SoftApConfiguration.class.getField("BAND_2GHZ");
                    band2Ghz = bandField.getInt(null);
                } catch (Throwable ignored) {}

                Method setBandMethod = builder.getClass().getMethod("setBand", int.class);
                setBandMethod.invoke(builder, band2Ghz);

                try {
                    Method setSsid = builder.getClass().getMethod("setSsid", String.class);
                    setSsid.invoke(builder, preferredSsid);
                } catch (Throwable ignored) {}

                if (preferredPassphrase != null && preferredPassphrase.length() >= 8) {
                    builder.setPassphrase(preferredPassphrase, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
                }

                SoftApConfiguration config = builder.build();
                Method setMethod = wifiManager.getClass().getMethod("setSoftApConfiguration", SoftApConfiguration.class);
                boolean ok = (boolean) setMethod.invoke(wifiManager, config);
                logger.s(TAG, "wifiManager.setSoftApConfiguration(BAND_2GHZ) aplicado: " + ok);
            } catch (Throwable t) {
                logger.d(TAG, "configureSoftAp2Ghz (R+): " + t.getMessage());
            }
        }

        // Legacy WifiConfiguration apBand = 0
        try {
            Method setConfig = wifiManager.getClass().getMethod("setWifiApConfiguration", WifiConfiguration.class);
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = preferredSsid;
            config.preSharedKey = preferredPassphrase;
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            try {
                Field apBandField = config.getClass().getField("apBand");
                apBandField.setInt(config, 0); // 0 = 2.4 GHz
            } catch (Throwable ignored) {}
            boolean ok = (boolean) setConfig.invoke(wifiManager, config);
            logger.s(TAG, "wifiManager.setWifiApConfiguration(apBand=0) aplicado: " + ok);
        } catch (Throwable ignored) {}
    }

    public boolean isStarting() {
        if (isStarting) {
            if (System.currentTimeMillis() - startingTimestamp > 8000) {
                isStarting = false;
                if (currentHotspotInfo != null && currentHotspotInfo.getState() == HotspotInfo.State.STARTING) {
                    updateState(HotspotInfo.failed("Tiempo de espera agotado iniciando Hotspot"));
                }
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

            // If running in 5 GHz, restart to force back to 2.4 GHz
            if (currentHotspotInfo != null && currentHotspotInfo.is5Ghz() && activeDuration > 5000) {
                logger.w(TAG, "[Watchdog] Hotspot detectado en banda 5 GHz. Forzando reinicio para recuperar 2.4 GHz...");
                enforce2GhzSystemSettings();
                configureSoftAp2Ghz();
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
        mainHandler.removeCallbacks(startingTimeoutRunnable);
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

        // Cancel previous watchdog and set 8-second safety timeout
        mainHandler.removeCallbacks(startingTimeoutRunnable);
        mainHandler.postDelayed(startingTimeoutRunnable, 8000);

        // Asegurar permisos de tiempo de ejecución
        grantAllDeviceOwnerPermissions();

        // Forzar configuraciones de sistema a 2.4 GHz
        enforce2GhzSystemSettings();
        configureSoftAp2Ghz();

        // Desconectar cliente Wi-Fi para no bloquear el chip de radio en canal 5 GHz
        try {
            wifiManager.disconnect();
        } catch (Throwable ignored) {}

        // Iniciar Red Local Wi-Fi (LocalOnlyHotspot)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startHotspotApi30WithCustomConfig();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startLocalOnlyHotspotApi26();
            } else {
                startLegacySoftAp();
            }
        } catch (Throwable t) {
            mainHandler.removeCallbacks(startingTimeoutRunnable);
            isStarting = false;
            lastFailureTimestamp = System.currentTimeMillis();
            lastFailureReason = "Aviso inicio Red Local: " + t.getMessage();
            logger.w(TAG, lastFailureReason);
            updateState(HotspotInfo.failed(lastFailureReason));
        }
    }

    /**
     * On Android 11+ (API 30+), requests Local Hotspot with custom SSID, WPA2-PSK Passphrase,
     * and strictly forces the 2.4 GHz band (BAND_2GHZ) for IoT/ESP32 compatibility.
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    private void startHotspotApi30WithCustomConfig() {
        int band2Ghz = 1; // 1 << 0 = BAND_2GHZ
        try {
            Field bandField = SoftApConfiguration.class.getField("BAND_2GHZ");
            band2Ghz = bandField.getInt(null);
        } catch (Throwable ignore) {}

        // Full custom configuration (SSID + Passphrase WPA2 + 2.4 GHz Band)
        try {
            SoftApConfiguration.Builder configBuilder = new SoftApConfiguration.Builder();

            // Set SSID
            if (preferredSsid != null && !preferredSsid.isEmpty()) {
                try {
                    Method setSsidMethod = configBuilder.getClass().getMethod("setSsid", String.class);
                    setSsidMethod.invoke(configBuilder, preferredSsid);
                } catch (Throwable t) {
                    logger.d(TAG, "setSsid error: " + t.getMessage());
                }
            }

            // Set WPA2-PSK Passphrase (min 8 chars required by Android Wi-Fi spec)
            if (preferredPassphrase != null && preferredPassphrase.length() >= 8) {
                try {
                    configBuilder.setPassphrase(preferredPassphrase, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK);
                } catch (Throwable t) {
                    try {
                        Method setPassMethod = configBuilder.getClass().getMethod("setPassphrase", String.class, int.class);
                        setPassMethod.invoke(configBuilder, preferredPassphrase, 1);
                    } catch (Throwable ignored) {}
                }
            }

            // Force 2.4 GHz Band
            try {
                Method setBandMethod = configBuilder.getClass().getMethod("setBand", int.class);
                setBandMethod.invoke(configBuilder, band2Ghz);
                logger.i(TAG, "Configurando banda SoftAP a 2.4 GHz (IoT / ESP32)...");
            } catch (Throwable bandErr) {
                logger.d(TAG, "setBand fallback: " + bandErr.getMessage());
            }

            SoftApConfiguration softApConfig = configBuilder.build();

            Executor executor = mainHandler::post;
            WifiManager.LocalOnlyHotspotCallback callback = createHotspotCallback(true);

            Method method = WifiManager.class.getMethod(
                    "startLocalOnlyHotspot",
                    SoftApConfiguration.class,
                    Executor.class,
                    WifiManager.LocalOnlyHotspotCallback.class
            );
            method.invoke(wifiManager, softApConfig, executor, callback);
            logger.i(TAG, "Invocada creación de SoftAP en 2.4 GHz con SSID: [" + preferredSsid + "] y WPA2-PSK...");
            return;
        } catch (Throwable t) {
            logger.d(TAG, "startLocalOnlyHotspot con SoftApConfiguration custom falló: " + t.getMessage() + ", intentando fallback...");
        }

        // Fallback: Standard system LocalOnlyHotspot (inherits 2.4 GHz system settings)
        startLocalOnlyHotspotApi26();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startLocalOnlyHotspotApi26() {
        try {
            wifiManager.startLocalOnlyHotspot(createHotspotCallback(false), mainHandler);
            logger.i(TAG, "Invocado startLocalOnlyHotspot estándar de Android...");
        } catch (Throwable t) {
            mainHandler.removeCallbacks(startingTimeoutRunnable);
            isStarting = false;
            lastFailureTimestamp = System.currentTimeMillis();
            lastFailureReason = t.getMessage();
            logger.w(TAG, "Excepción startLocalOnlyHotspot: " + t.getMessage());
            updateState(HotspotInfo.failed(t.getMessage()));
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private WifiManager.LocalOnlyHotspotCallback createHotspotCallback(boolean isCustomConfigAttempt) {
        return new WifiManager.LocalOnlyHotspotCallback() {
            @Override
            public void onStarted(WifiManager.LocalOnlyHotspotReservation reservation) {
                super.onStarted(reservation);
                mainHandler.removeCallbacks(startingTimeoutRunnable);
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

                    String actualBand = "2.4 GHz";
                    int channelNumber = 0;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            android.net.wifi.SoftApConfiguration config = reservation.getSoftApConfiguration();
                            if (config != null) {
                                try {
                                    Method getChannelMethod = config.getClass().getMethod("getChannel");
                                    Object chResult = getChannelMethod.invoke(config);
                                    if (chResult instanceof Integer) {
                                        channelNumber = (Integer) chResult;
                                    }
                                } catch (Throwable ignore) {}

                                Method getBandMethod = config.getClass().getMethod("getBand");
                                Object bandResult = getBandMethod.invoke(config);
                                if (bandResult instanceof Integer) {
                                    int bandInt = (Integer) bandResult;
                                    if (channelNumber > 0) {
                                        if (channelNumber <= 14) {
                                            actualBand = "2.4 GHz";
                                        } else {
                                            actualBand = "5 GHz";
                                        }
                                    } else if ((bandInt & 2) != 0 && (bandInt & 1) == 0) {
                                        actualBand = "5 GHz";
                                    } else if ((bandInt & 1) != 0 && (bandInt & 2) == 0) {
                                        actualBand = "2.4 GHz";
                                    } else if ((bandInt & 2) != 0) {
                                        actualBand = "5 GHz";
                                    } else {
                                        actualBand = "2.4 GHz";
                                    }
                                }
                            }
                        } catch (Throwable ignore) {}
                    }

                    logger.s(TAG, "¡Red Wi-Fi Local CREADA exitosamente!");
                    logger.s(TAG, "SSID: [" + ssid + "] | Banda: [" + actualBand + "] | Clave: [" + (passphrase == null || passphrase.isEmpty() ? "Abierta" : passphrase) + "] | IP Nodo: " + localIp);

                    if (actualBand.contains("5 GHz")) {
                        logger.w(TAG, "⚠️ ATENCIÓN: El smartphone ha iniciado la red en banda 5 GHz (" + actualBand + ").");
                        logger.w(TAG, "⚠️ Los módulos ESP32 / IoT poseen antenas de 2.4 GHz únicamente y NO podrán conectarse.");
                        logger.w(TAG, "⚠️ Reaplicando configuraciones de radio 2.4 GHz y programando reinicio correctivo...");
                        enforce2GhzSystemSettings();
                        configureSoftAp2Ghz();
                        // Si el hardware abrió en 5 GHz, intentar auto-reinicio a 2.4 GHz en 4 segundos
                        mainHandler.postDelayed(() -> {
                            if (isHotspotActive() && currentHotspotInfo != null && currentHotspotInfo.is5Ghz()) {
                                logger.w(TAG, "Ejecutando reinicio correctivo de Hotspot para recuperar banda 2.4 GHz...");
                                forceRestartHotspot();
                            }
                        }, 4000);
                    }

                    updateState(HotspotInfo.running(ssid, passphrase != null ? passphrase : "", localIp, actualBand));
                } catch (Throwable t) {
                    logger.w(TAG, "Error procesando onStarted de Hotspot: " + t.getMessage());
                }
            }

            @Override
            public void onStopped() {
                super.onStopped();
                mainHandler.removeCallbacks(startingTimeoutRunnable);
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
                mainHandler.removeCallbacks(startingTimeoutRunnable);

                if (isCustomConfigAttempt) {
                    logger.w(TAG, "Intento con SoftApConfiguration falló (código " + reason + "). Reintentando inmediatamente con API estándar...");
                    try {
                        startLocalOnlyHotspotApi26();
                        return;
                    } catch (Throwable t) {
                        logger.w(TAG, "Fallback estándar tras onFailed falló: " + t.getMessage());
                    }
                }

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
            try {
                // apBand: 0 = 2.4 GHz, 1 = 5 GHz
                java.lang.reflect.Field apBandField = config.getClass().getField("apBand");
                apBandField.setInt(config, 0); // 2.4 GHz
            } catch (Throwable ignored) {}

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
        mainHandler.removeCallbacks(startingTimeoutRunnable);
        isStarting = false;
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

        // Try stopping via TetheringManager (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Object tm = context.getSystemService("tethering");
                if (tm != null) {
                    Method stopMethod = tm.getClass().getMethod("stopTethering", int.class);
                    stopMethod.invoke(tm, 0); // 0 = TETHERING_WIFI
                    logger.d(TAG, "TetheringManager.stopTethering(0) invocado.");
                }
            } catch (Throwable ignored) {}
        }

        // Try stopping via ConnectivityManager
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Method stopMethod = cm.getClass().getMethod("stopTethering", int.class);
                stopMethod.invoke(cm, 0);
            }
        } catch (Throwable ignored) {}

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
