package com.example.wifi;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.provider.Settings;
import com.example.receiver.AdminReceiver;
import com.example.utils.AppLogger;
import java.lang.reflect.Method;

/**
 * Controller for managing Wi-Fi state, hardware toggling, and network connectivity checks.
 * Supports direct hardware toggling via Device Owner (DevicePolicyManager) and standard APIs.
 */
public class WifiController {
    private static final String TAG = "WifiController";
    private final Context context;
    private final WifiManager wifiManager;
    private final ConnectivityManager connectivityManager;
    private final DevicePolicyManager devicePolicyManager;
    private final ComponentName adminComponent;
    private final AppLogger logger = AppLogger.getInstance();

    private BroadcastReceiver wifiStateReceiver;
    private ConnectivityManager.NetworkCallback networkCallback;
    private WifiStateListener stateListener;

    public interface WifiStateListener {
        void onWifiStateChanged(boolean isEnabled, String details);
    }

    public WifiController(Context context) {
        this.context = context.getApplicationContext();
        this.wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
        this.connectivityManager = (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.devicePolicyManager = (DevicePolicyManager) this.context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        this.adminComponent = new ComponentName(this.context, AdminReceiver.class);
    }

    public void setStateListener(WifiStateListener listener) {
        this.stateListener = listener;
    }

    /**
     * Checks if Device Owner or Device Admin privileges are active on this device.
     */
    public boolean isDeviceOwnerActive() {
        try {
            if (devicePolicyManager != null) {
                if (devicePolicyManager.isDeviceOwnerApp(context.getPackageName())) {
                    return true;
                }
                if (devicePolicyManager.isAdminActive(adminComponent)) {
                    return true;
                }
            }
        } catch (Throwable t) {
            logger.d(TAG, "Error comprobando estado de Device Owner: " + t.getMessage());
        }
        return false;
    }

    /**
     * Safely clears Device Owner status programmatically.
     */
    public boolean clearDeviceOwner() {
        try {
            if (devicePolicyManager != null && devicePolicyManager.isDeviceOwnerApp(context.getPackageName())) {
                devicePolicyManager.clearDeviceOwnerApp(context.getPackageName());
                logger.s(TAG, "Device Owner revocado exitosamente desde la aplicación.");
                return true;
            }
        } catch (Throwable t) {
            logger.e(TAG, "Error revocando Device Owner: " + t.getMessage());
        }
        return false;
    }

    /**
     * Checks if Wi-Fi hardware is currently enabled.
     */
    public boolean isWifiEnabled() {
        if (wifiManager != null) {
            return wifiManager.isWifiEnabled();
        }
        return false;
    }

    /**
     * Attempts to toggle Wi-Fi state programmatically according to schedule.
     * When configured with Device Owner privileges, setWifiEnabled operates directly and silently on all Android versions.
     */
    public boolean setWifiEnabled(boolean enable) {
        boolean isOwner = isDeviceOwnerActive();
        logger.i(TAG, "Conmutando Hardware Wi-Fi -> " + (enable ? "ENCENDIDO" : "APAGADO") + 
                (isOwner ? " (Modo Device Owner ACTIVO)" : " (Modo Estándar)"));

        if (wifiManager == null) {
            logger.e(TAG, "WifiManager no está disponible en este dispositivo.");
            return false;
        }

        boolean toggled = false;

        // 1. Direct hardware call (setWifiEnabled)
        try {
            boolean success = wifiManager.setWifiEnabled(enable);
            if (success) {
                logger.s(TAG, "[Hardware] Wi-Fi conmutado con éxito a: " + (enable ? "ENCENDIDO" : "APAGADO"));
                toggled = true;
            }
        } catch (SecurityException se) {
            logger.w(TAG, "Llamada directa setWifiEnabled restringida: " + se.getMessage());
        } catch (Throwable t) {
            logger.w(TAG, "Excepción en setWifiEnabled directo: " + t.getMessage());
        }

        // 2. Reflection on hidden setWifiEnabled on WifiManager
        if (!toggled) {
            try {
                Method method = wifiManager.getClass().getMethod("setWifiEnabled", boolean.class);
                Object result = method.invoke(wifiManager, enable);
                if (result instanceof Boolean && (Boolean) result) {
                    logger.s(TAG, "[Hardware] Wi-Fi conmutado vía reflexión a: " + (enable ? "ENCENDIDO" : "APAGADO"));
                    toggled = true;
                }
            } catch (Throwable t) {
                logger.d(TAG, "Reflexión setWifiEnabled: " + t.getMessage());
            }
        }

        // 3. For disabling Wi-Fi: if Wi-Fi is still enabled, force disconnect from current AP
        if (!enable) {
            try {
                wifiManager.disconnect();
                logger.i(TAG, "Desconectado de redes Wi-Fi activas.");
            } catch (Throwable ignored) {}
        }

        // 4. Fallback for Android 10+ standard network bindings
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (enable) {
                requestWifiNetworkBackground();
            } else {
                if (networkCallback != null && connectivityManager != null) {
                    try {
                        connectivityManager.unregisterNetworkCallback(networkCallback);
                        networkCallback = null;
                        logger.i(TAG, "Callback de transporte Wi-Fi liberado.");
                    } catch (Exception ignored) {}
                }
            }
        }

        return toggled;
    }

    /**
     * Autonomous background network request forcing Wi-Fi interface binding without internet requirement.
     */
    private void requestWifiNetworkBackground() {
        if (connectivityManager == null) return;
        try {
            if (networkCallback != null) {
                try {
                    connectivityManager.unregisterNetworkCallback(networkCallback);
                } catch (Exception ignored) {}
            }

            NetworkRequest.Builder builder = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI);

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    logger.s(TAG, "Interfaz de red Wi-Fi enlazada en segundo plano: " + network);
                    if (stateListener != null) {
                        stateListener.onWifiStateChanged(true, "Wi-Fi Activo: " + network);
                    }
                }

                @Override
                public void onLost(Network network) {
                    logger.w(TAG, "Interfaz de red Wi-Fi desconectada: " + network);
                    if (stateListener != null) {
                        stateListener.onWifiStateChanged(isWifiEnabled(), "Wi-Fi Desconectado");
                    }
                }
            };

            connectivityManager.requestNetwork(builder.build(), networkCallback);
            logger.i(TAG, "Petición de interfaz Wi-Fi registrada.");
        } catch (Exception e) {
            logger.w(TAG, "No se pudo registrar petición de Wi-Fi: " + e.getMessage());
        }
    }

    /**
     * Starts listening for Wi-Fi state broadcast changes.
     */
    public void startMonitoring() {
        if (wifiStateReceiver == null) {
            wifiStateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(intent.getAction())) {
                        int state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);
                        boolean isEnabled = (state == WifiManager.WIFI_STATE_ENABLED);
                        String stateDesc = "DESCONOCIDO";
                        switch (state) {
                            case WifiManager.WIFI_STATE_ENABLED:
                                stateDesc = "ENCENDIDO";
                                break;
                            case WifiManager.WIFI_STATE_ENABLING:
                                stateDesc = "ENCENDIENDO...";
                                break;
                            case WifiManager.WIFI_STATE_DISABLED:
                                stateDesc = "APAGADO";
                                break;
                            case WifiManager.WIFI_STATE_DISABLING:
                                stateDesc = "APAGANDO...";
                                break;
                        }
                        logger.i(TAG, "[Hardware Broadcast] Estado Wi-Fi: " + stateDesc);
                        if (stateListener != null) {
                            stateListener.onWifiStateChanged(isEnabled, "Wi-Fi: " + stateDesc);
                        }
                    }
                }
            };
            context.registerReceiver(wifiStateReceiver, new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION));
        }
    }

    /**
     * Unregisters broadcast and network callbacks.
     */
    public void stopMonitoring() {
        if (wifiStateReceiver != null) {
            try {
                context.unregisterReceiver(wifiStateReceiver);
            } catch (Exception ignored) {}
            wifiStateReceiver = null;
        }
        if (networkCallback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {}
            networkCallback = null;
        }
    }
}

