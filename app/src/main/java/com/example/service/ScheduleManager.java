package com.example.service;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import com.example.model.ScheduleConfig;
import com.example.model.TimeRange;
import com.example.utils.AppLogger;
import com.example.wifi.LocalHotspotManager;
import com.example.wifi.WifiController;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Autonomous background scheduler engine in Java.
 * Manages configurable time intervals for Wi-Fi module power, Local Hotspot/Network creation,
 * and passes the customized SSID, Passphrase, and Port settings.
 */
public class ScheduleManager {
    private static final String TAG = "ScheduleManager";
    private static final String PREF_NAME = "wifi_tcp_mesh_schedule_prefs";
    private static final String KEY_CONFIG = "schedule_config_json";

    private static volatile ScheduleManager instance;

    private final AppLogger logger = AppLogger.getInstance();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<ScheduleChangeListener> listeners = new CopyOnWriteArrayList<>();

    private ScheduleConfig config;
    private SharedPreferences prefs;

    // Track last applied states to prevent redundant logs/actions
    private Boolean lastAppliedWifiState = null;
    private Boolean lastAppliedHotspotState = null;
    private String lastEvaluationSummary = "";

    public interface ScheduleChangeListener {
        void onScheduleChanged(ScheduleConfig config);
        void onScheduleEvaluated(boolean wifiShouldBeOn, boolean hotspotShouldBeOn, String summary);
    }

    private ScheduleManager() {
        this.config = new ScheduleConfig();
    }

    public static ScheduleManager getInstance() {
        if (instance == null) {
            synchronized (ScheduleManager.class) {
                if (instance == null) {
                    instance = new ScheduleManager();
                }
            }
        }
        return instance;
    }

    public synchronized void init(Context context) {
        if (prefs == null && context != null) {
            prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            loadFromPreferences();
        }
    }

    public synchronized void loadFromPreferences() {
        if (prefs != null) {
            String json = prefs.getString(KEY_CONFIG, null);
            if (json != null && !json.isEmpty()) {
                this.config = ScheduleConfig.fromJson(json);
                logger.i(TAG, "Configuración de horarios cargada desde almacenamiento persistente.");
            } else {
                this.config = new ScheduleConfig();
                saveToPreferences();
                logger.i(TAG, "Configuración de horarios por defecto inicializada.");
            }
        }
    }

    public synchronized void saveToPreferences() {
        if (prefs != null && config != null) {
            prefs.edit().putString(KEY_CONFIG, config.toJson()).apply();
            notifyScheduleChanged();
        }
    }

    public synchronized ScheduleConfig getConfig() {
        return config;
    }

    public synchronized void updateConfig(ScheduleConfig newConfig) {
        if (newConfig != null) {
            this.config = newConfig;
            saveToPreferences();
            logger.s(TAG, "Nuevos horarios guardados. SSID Fijo: " + config.getCustomSsid()
                    + " | Puerto: " + config.getTcpPort()
                    + " | Wi-Fi: " + (config.isWifiScheduleEnabled() ? "Activo" : "Manual")
                    + " | Red Local: " + (config.isHotspotScheduleEnabled() ? "Activo" : "Manual"));
        }
    }

    public synchronized void resetToDefaultSchedules() {
        this.config = new ScheduleConfig();
        saveToPreferences();
        logger.i(TAG, "Horarios restaurados a valores predeterminados.");
    }

    public synchronized void setActiveDays(java.util.Collection<Integer> days) {
        this.config.setActiveDays(days);
        this.lastAppliedWifiState = null;
        this.lastAppliedHotspotState = null;
        saveToPreferences();
        logger.i(TAG, "Días de operación actualizados: " + config.getActiveDaysFormatted());
    }

    public synchronized void setDayActive(int dayOfWeek, boolean active) {
        this.config.setDayActive(dayOfWeek, active);
        this.lastAppliedWifiState = null;
        this.lastAppliedHotspotState = null;
        saveToPreferences();
        logger.i(TAG, "Día " + ScheduleConfig.getDayFullName(dayOfWeek) + (active ? " ACTIVADO" : " DESACTIVADO")
                + " | Activos: " + config.getActiveDaysFormatted());
    }

    public synchronized void setInvertedSchedule(int offStartH, int offStartM, int offEndH, int offEndM) {
        this.config.applyInvertedSchedule(offStartH, offStartM, offEndH, offEndM);
        this.lastAppliedWifiState = null;
        this.lastAppliedHotspotState = null;
        saveToPreferences();
        logger.i(TAG, "Ventana Red TCP APAGADA / Wi-Fi ON: " + config.getConfiguredOffWindowFormatted()
                + " | Complemento Red TCP ON: " + config.getHotspotScheduleFormatted());
    }

    public synchronized void setComplementarySchedule(int startH, int startM, int endH, int endM) {
        setInvertedSchedule(startH, startM, endH, endM);
    }

    public synchronized void updateNetworkCredentials(String ssid, String passphrase, int port) {
        this.config.setCustomSsid(ssid);
        this.config.setCustomPassphrase(passphrase);
        this.config.setTcpPort(port);
        saveToPreferences();
    }

    public synchronized void addWifiRange(TimeRange range) {
        config.addWifiRange(range);
        saveToPreferences();
    }

    public synchronized void removeWifiRange(String rangeId) {
        config.removeWifiRange(rangeId);
        saveToPreferences();
    }

    public synchronized void addHotspotRange(TimeRange range) {
        config.addHotspotRange(range);
        saveToPreferences();
    }

    public synchronized void removeHotspotRange(String rangeId) {
        config.removeHotspotRange(rangeId);
        saveToPreferences();
    }

    public synchronized void setWifiScheduleEnabled(boolean enabled) {
        config.setWifiScheduleEnabled(enabled);
        saveToPreferences();
    }

    public synchronized void setHotspotScheduleEnabled(boolean enabled) {
        config.setHotspotScheduleEnabled(enabled);
        saveToPreferences();
    }

    /**
     * Autonomous Evaluation Loop: Checks current time against configured schedules
     * and automatically turns ON/OFF the Wi-Fi hardware, Local Hotspot and TCP Server.
     * STRICT MUTUAL EXCLUSIVITY: Wi-Fi and Local Hotspot/TCP will NEVER run simultaneously.
     */
     public synchronized void evaluateAndApply(WifiController wifiController, LocalHotspotManager hotspotManager) {
         evaluateAndApply(wifiController, hotspotManager, null);
     }

     public synchronized void evaluateAndApply(WifiController wifiController, LocalHotspotManager hotspotManager, com.example.tcp.TcpConnectionManager tcpManager) {
         Calendar now = Calendar.getInstance();
         int dayOfWeek = now.get(Calendar.DAY_OF_WEEK);
         int hour = now.get(Calendar.HOUR_OF_DAY);
         int minute = now.get(Calendar.MINUTE);
         int second = now.get(Calendar.SECOND);

         String currentTimeStr = String.format(Locale.US, "%02d:%02d:%02d", hour, minute, second);
         String currentDayName = ScheduleConfig.getDayFullName(dayOfWeek);

         // Sync configured fixed credentials to HotspotManager
         if (hotspotManager != null && config != null) {
             hotspotManager.setPreferredCredentials(config.getCustomSsid(), config.getCustomPassphrase());
         }

         int targetPort = config != null ? config.getTcpPort() : 8888;

         // Check if TODAY is an active operating day
         boolean isOperatingDay = config != null && config.isDayActive(dayOfWeek);

         // If today is NOT an operating day, SHUT DOWN ALL MODULES (Wi-Fi, Hotspot, TCP Server)
         if (!isOperatingDay) {
             if (hotspotManager != null && (hotspotManager.isHotspotActive() || hotspotManager.isStarting())) {
                 logger.i(TAG, String.format(Locale.US,
                         "[Schedule] %s -> %s (Inactive). Stopping Hotspot...", currentTimeStr, currentDayName));
                 try {
                     hotspotManager.stopLocalHotspot();
                 } catch (Throwable t) {
                     logger.w(TAG, "Error stopping hotspot: " + t.getMessage());
                 }
             }

             if (tcpManager != null && tcpManager.isServerRunning()) {
                 logger.i(TAG, String.format(Locale.US,
                         "[Schedule] %s -> %s (Inactive). Stopping TCP server...", currentTimeStr, currentDayName));
                 try {
                     tcpManager.stopServer();
                 } catch (Throwable t) {
                     logger.w(TAG, "Error stopping TCP server: " + t.getMessage());
                 }
             }

             if (wifiController != null && (lastAppliedWifiState == null || lastAppliedWifiState || wifiController.isWifiEnabled())) {
                 logger.i(TAG, String.format(Locale.US,
                         "[Schedule] %s -> %s (Inactive). Disabling Wi-Fi...", currentTimeStr, currentDayName));
                 try {
                     wifiController.setWifiEnabled(false);
                 } catch (Throwable t) {
                     logger.w(TAG, "Error disabling Wi-Fi: " + t.getMessage());
                 }
             }

             lastAppliedWifiState = false;
             lastAppliedHotspotState = false;

             lastEvaluationSummary = String.format(Locale.US,
                     "Time: %s | %s: Inactive",
                     currentTimeStr, currentDayName);

             notifyScheduleEvaluated(false, false, lastEvaluationSummary);
             return;
         }

         // Primary schedule: Is Hotspot / TCP active right now?
         boolean hotspotTargetState = config.isHotspotScheduleEnabled() && config.shouldHotspotBeActive(hour, minute);

         // Wi-Fi target state is strictly mutually exclusive with Hotspot
         boolean wifiTargetState = !hotspotTargetState && config.isWifiScheduleEnabled() && config.shouldWifiBeActive(hour, minute);

         // 1. If Local TCP Network should be ON: Stop Wi-Fi first, start Hotspot & TCP Server
         if (hotspotTargetState) {
             // Ensure Wi-Fi is turned OFF to avoid hardware conflict
             if (wifiController != null && (lastAppliedWifiState == null || lastAppliedWifiState)) {
                 logger.i(TAG, String.format(Locale.US,
                         "[Schedule] %s (%s) -> Starting TCP Network. Wi-Fi OFF.", currentTimeStr, currentDayName));
                 try {
                     wifiController.setWifiEnabled(false);
                 } catch (Throwable t) {
                     logger.d(TAG, "Notice disabling Wi-Fi: " + t.getMessage());
                 }
                 lastAppliedWifiState = false;
             }

             // Start or revive Hotspot using active interface probe and keep-alive watchdog
             if (hotspotManager != null) {
                 int clientCount = (tcpManager != null && tcpManager.getConnectedClients() != null)
                         ? tcpManager.getConnectedClients().size() : 0;
                 hotspotManager.checkAndReviveIfNeeded(clientCount);
                 lastAppliedHotspotState = true;
             }

             // Ensure TCP server is active on target port
             if (tcpManager != null && !tcpManager.isServerRunning()) {
                 try {
                     tcpManager.startServer(targetPort);
                 } catch (Throwable t) {
                     logger.w(TAG, "Error ensuring TCP server: " + t.getMessage());
                 }
             }
         } else {
             // 2. If Local TCP Network should be OFF: Stop Hotspot and TCP Server first, then enable Wi-Fi if requested
             if (hotspotManager != null) {
                 boolean currentHotspot = hotspotManager.isHotspotActive();
                 if (lastAppliedHotspotState == null || lastAppliedHotspotState) {
                     logger.i(TAG, String.format(Locale.US,
                             "[Schedule] %s (%s) -> Stopping TCP Network...", currentTimeStr, currentDayName));
                     if (currentHotspot || hotspotManager.isStarting()) {
                         try {
                             hotspotManager.stopLocalHotspot();
                         } catch (Throwable t) {
                             logger.w(TAG, "Error stopping hotspot: " + t.getMessage());
                         }
                     }
                     if (tcpManager != null && tcpManager.isServerRunning()) {
                         try {
                             tcpManager.stopServer();
                         } catch (Throwable t) {
                             logger.w(TAG, "Error stopping TCP server: " + t.getMessage());
                         }
                     }
                     lastAppliedHotspotState = false;
                 }
             }

             if (tcpManager != null && tcpManager.isServerRunning()) {
                 try {
                     tcpManager.stopServer();
                 } catch (Throwable t) {
                     logger.w(TAG, "Error stopping TCP server: " + t.getMessage());
                 }
             }

             // Enable Wi-Fi if target state is ON
             if (wifiController != null) {
                 if (lastAppliedWifiState == null || lastAppliedWifiState != wifiTargetState) {
                     logger.s(TAG, String.format(Locale.US,
                             "[Schedule] %s (%s) -> Wi-Fi: %s",
                             currentTimeStr, currentDayName, (wifiTargetState ? "ON" : "OFF")));
                     try {
                         wifiController.setWifiEnabled(wifiTargetState);
                     } catch (Throwable t) {
                         logger.w(TAG, "Error setting Wi-Fi: " + t.getMessage());
                     }
                     lastAppliedWifiState = wifiTargetState;
                 }
             }
         }

         // Format summary status for UI telemetry
         lastEvaluationSummary = String.format(Locale.US,
                 "Time: %s | %s | %s",
                 currentTimeStr,
                 currentDayName,
                 (hotspotTargetState ? "TCP ON" : (wifiTargetState ? "Wi-Fi ON" : "Standby"))
         );

         notifyScheduleEvaluated(wifiTargetState, hotspotTargetState, lastEvaluationSummary);
     }

    public String getLastEvaluationSummary() {
        return lastEvaluationSummary;
    }

    public void registerListener(ScheduleChangeListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
            listener.onScheduleChanged(config);
        }
    }

    public void unregisterListener(ScheduleChangeListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private void notifyScheduleChanged() {
        mainHandler.post(() -> {
            for (ScheduleChangeListener listener : listeners) {
                try {
                    listener.onScheduleChanged(config);
                } catch (Throwable ignored) {}
            }
        });
    }

    private void notifyScheduleEvaluated(boolean wifiState, boolean hotspotState, String summary) {
        mainHandler.post(() -> {
            for (ScheduleChangeListener listener : listeners) {
                try {
                    listener.onScheduleEvaluated(wifiState, hotspotState, summary);
                } catch (Throwable ignored) {}
            }
        });
    }
}
