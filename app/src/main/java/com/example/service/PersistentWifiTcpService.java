package com.example.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.example.MainActivity;
import com.example.model.ConnectedClient;
import com.example.model.HotspotInfo;
import com.example.model.TcpPacket;
import com.example.tcp.TcpConnectionManager;
import com.example.utils.AppLogger;
import com.example.wifi.LocalHotspotManager;
import com.example.wifi.WifiController;
import java.util.List;

/**
 * Persistent Foreground Service in Java operating entirely in the background without user interaction.
 * Maintains Wi-Fi state, local ad-hoc hotspot creation/deletion, and TCP connection management with wake locks.
 */
public class PersistentWifiTcpService extends Service {
    private static final String TAG = "PersistentService";
    private static final String CHANNEL_ID = "wifi_tcp_mesh_channel";
    private static final int NOTIFICATION_ID = 1001;

    // Actions
    public static final String ACTION_START = "com.example.action.START_SERVICE";
    public static final String ACTION_STOP = "com.example.action.STOP_SERVICE";
    public static final String ACTION_CREATE_HOTSPOT = "com.example.action.CREATE_HOTSPOT";
    public static final String ACTION_STOP_HOTSPOT = "com.example.action.STOP_HOTSPOT";
    public static final String ACTION_TOGGLE_WIFI = "com.example.action.TOGGLE_WIFI";
    public static final String ACTION_SEND_BROADCAST = "com.example.action.SEND_BROADCAST";
    public static final String ACTION_CONNECT_PEER = "com.example.action.CONNECT_PEER";
    public static final String ACTION_EVALUATE_SCHEDULE = "com.example.action.EVALUATE_SCHEDULE";

    // Extras
    public static final String EXTRA_WIFI_ENABLE = "extra_wifi_enable";
    public static final String EXTRA_MESSAGE = "extra_message";
    public static final String EXTRA_TARGET_IP = "extra_target_ip";
    public static final String EXTRA_TARGET_PORT = "extra_target_port";

    private final AppLogger logger = AppLogger.getInstance();
    private final MeshStateManager stateManager = MeshStateManager.getInstance();
    private final ScheduleManager scheduleManager = ScheduleManager.getInstance();

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private WifiController wifiController;
    private LocalHotspotManager hotspotManager;
    private TcpConnectionManager tcpConnectionManager;

    private java.util.concurrent.ScheduledExecutorService schedulerExecutor;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForegroundWithProperType();

        logger.s(TAG, "Persistent Service onCreate - Initializing Autonomous Mesh Background Engine & Scheduler...");

        try {
            // Initialize Schedule Manager with persistent preferences
            scheduleManager.init(this);

            // Acquire WakeLock and WifiLock to keep CPU and Wi-Fi chip powered in background
            acquireWakeLock();
            acquireWifiLock();

            // Initialize Core Subsystems in Java
            wifiController = new WifiController(this);
            hotspotManager = new LocalHotspotManager(this);
            tcpConnectionManager = new TcpConnectionManager();

            setupListeners();

            // Start monitoring Wi-Fi hardware broadcasts
            wifiController.startMonitoring();

            // Start background scheduler loop and alarm watchdog
            startAutonomousSchedulerLoop();
            scheduleNextAlarmWatchdog();

            // Update initial state
            stateManager.setServiceRunning(true);
            stateManager.setWifiHardwareEnabled(wifiController.isWifiEnabled(), "Initialized");
        } catch (Throwable t) {
            logger.e(TAG, "Error in PersistentWifiTcpService.onCreate: " + t.getMessage());
        }
    }

    private void startAutonomousSchedulerLoop() {
        if (schedulerExecutor == null || schedulerExecutor.isShutdown()) {
            schedulerExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            schedulerExecutor.scheduleWithFixedDelay(() -> {
                try {
                    scheduleManager.evaluateAndApply(wifiController, hotspotManager, tcpConnectionManager);
                } catch (Exception e) {
                    logger.w(TAG, "Error evaluating schedules in background: " + e.getMessage());
                }
            }, 1, 5, java.util.concurrent.TimeUnit.SECONDS);
            logger.i(TAG, "Bucle autónomo de verificación de horarios iniciado (cada 5s).");
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WiFiTcpMesh::WakeLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
                logger.i(TAG, "Partial WakeLock acquired (prevents CPU sleep).");
            }
        } catch (Exception e) {
            logger.w(TAG, "WakeLock error: " + e.getMessage());
        }
    }

    private void acquireWifiLock() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "WiFiTcpMesh::WifiLock");
                } else {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "WiFiTcpMesh::WifiLock");
                }
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
                logger.i(TAG, "High-Performance WifiLock acquired (prevents Wi-Fi radio sleep).");
            }
        } catch (Exception e) {
            logger.w(TAG, "WifiLock error: " + e.getMessage());
        }
    }

    private void scheduleNextAlarmWatchdog() {
        try {
            android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                Intent intent = new Intent(this, PersistentWifiTcpService.class);
                intent.setAction(ACTION_EVALUATE_SCHEDULE);
                PendingIntent pi = PendingIntent.getService(
                        this, 999, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
                long triggerAtMillis = System.currentTimeMillis() + 30000; // 30 seconds
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
                } else {
                    alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, triggerAtMillis, pi);
                }
            }
        } catch (Throwable ignore) {}
    }

    private void cancelAlarmWatchdog() {
        try {
            android.app.AlarmManager alarmManager = (android.app.AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                Intent intent = new Intent(this, PersistentWifiTcpService.class);
                intent.setAction(ACTION_EVALUATE_SCHEDULE);
                PendingIntent pi = PendingIntent.getService(
                        this, 999, intent,
                        PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
                );
                if (pi != null) {
                    alarmManager.cancel(pi);
                }
            }
        } catch (Throwable ignore) {}
    }

    private void setupListeners() {
        // 1. Wi-Fi Hardware Listener
        wifiController.setStateListener((isEnabled, details) -> {
            stateManager.setWifiHardwareEnabled(isEnabled, details);
            updateNotification();
        });

        // 2. Hotspot State Listener
        hotspotManager.setListener(info -> {
            stateManager.setHotspotInfo(info);
            updateNotification();
        });

        // 3. TCP Connection Manager Listener
        tcpConnectionManager.registerListener(new TcpConnectionManager.TcpNetworkEventListener() {
            @Override
            public void onServerStatusChanged(boolean running, int port) {
                stateManager.setTcpServerRunning(running, port);
                updateNotification();
            }

            @Override
            public void onClientStatusChanged(boolean connected, String endpoint, long latencyMs) {
                stateManager.setTcpClientConnected(connected, endpoint, latencyMs);
                updateNotification();
            }

            @Override
            public void onClientsListUpdated(List<ConnectedClient> clients) {
                stateManager.setConnectedClients(clients);
                updateNotification();
            }

            @Override
            public void onPacketReceived(TcpPacket packet, String source) {
                stateManager.setPacketsReceivedCount(tcpConnectionManager.getTotalPacketsReceived());
                stateManager.setTotalBytesTransferred(tcpConnectionManager.getTotalBytes());
                stateManager.notifyPacketReceived(packet, source);
            }

            @Override
            public void onPacketSent(TcpPacket packet, String destination) {
                stateManager.setPacketsSentCount(tcpConnectionManager.getTotalPacketsSent());
                stateManager.setTotalBytesTransferred(tcpConnectionManager.getTotalBytes());
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        startForegroundWithProperType();

        String action = intent != null ? intent.getAction() : ACTION_START;
        if (action == null) action = ACTION_START;

        logger.i(TAG, "onStartCommand Action: " + action);

        switch (action) {
            case ACTION_START:
                // Autonomous background operation without user interaction:
                // 1. Start TCP Server on port 8888
                tcpConnectionManager.startServer(8888);

                // 2. Automatically spin up Local-Only Hotspot if not already active
                if (!hotspotManager.isHotspotActive()) {
                    hotspotManager.startLocalHotspot();
                }
                break;

            case ACTION_CREATE_HOTSPOT:
                hotspotManager.forceRestartHotspot();
                break;

            case ACTION_STOP_HOTSPOT:
                hotspotManager.stopLocalHotspot();
                break;

            case ACTION_TOGGLE_WIFI:
                boolean enable = intent.getBooleanExtra(EXTRA_WIFI_ENABLE, true);
                wifiController.setWifiEnabled(enable);
                break;

            case ACTION_SEND_BROADCAST:
                String message = intent.getStringExtra(EXTRA_MESSAGE);
                if (message != null) {
                    tcpConnectionManager.broadcast(message);
                }
                break;

            case ACTION_CONNECT_PEER:
                String peerIp = intent.getStringExtra(EXTRA_TARGET_IP);
                int peerPort = intent.getIntExtra(EXTRA_TARGET_PORT, 8888);
                if (peerIp != null && !peerIp.isEmpty()) {
                    tcpConnectionManager.connectToPeer(peerIp, peerPort);
                }
                break;

            case ACTION_EVALUATE_SCHEDULE:
                scheduleManager.evaluateAndApply(wifiController, hotspotManager, tcpConnectionManager);
                scheduleNextAlarmWatchdog();
                break;

            case ACTION_STOP:
                cancelAlarmWatchdog();
                stopSelf();
                return START_NOT_STICKY;
        }

        return START_STICKY; // Rescheduled automatically if killed by Android
    }

    private void startForegroundWithProperType() {
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+: Use SPECIAL_USE with the declared manifest property
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29-33: Use CONNECTED_DEVICE
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            logger.w(TAG, "Notice starting foreground with type: " + t.getMessage() + ", applying fallback");
            try {
                startForeground(NOTIFICATION_ID, notification);
            } catch (Throwable fallbackFatal) {
                logger.e(TAG, "Fatal startForeground fallback: " + fallbackFatal.getMessage());
            }
        }
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIFICATION_ID, buildNotification());
            }
        } catch (Exception ignored) {}
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        String hotspotStatus = stateManager.getHotspotInfo().isRunning()
                ? "Hotspot: " + stateManager.getHotspotInfo().getSsid()
                : "Hotspot: Standby";

        String tcpStatus = stateManager.isTcpServerRunning()
                ? "TCP: Port " + stateManager.getTcpServerPort() + " (" + stateManager.getConnectedClients().size() + " peers)"
                : "TCP: Inactive";

        String contentText = hotspotStatus + " | " + tcpStatus;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WiFi & TCP Mesh (Background Service)")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "WiFi & TCP Local Mesh Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintains autonomous background Wi-Fi and TCP mesh connection without internet.");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        logger.w(TAG, "Persistent Service onDestroy - Shutting down mesh background components.");

        cancelAlarmWatchdog();
        stateManager.setServiceRunning(false);

        if (schedulerExecutor != null && !schedulerExecutor.isShutdown()) {
            schedulerExecutor.shutdownNow();
            schedulerExecutor = null;
        }

        if (hotspotManager != null) {
            hotspotManager.stopLocalHotspot();
        }

        if (tcpConnectionManager != null) {
            tcpConnectionManager.stopServer();
            tcpConnectionManager.disconnectFromPeer();
        }

        if (wifiController != null) {
            wifiController.stopMonitoring();
        }

        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }

        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            wifiLock = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
