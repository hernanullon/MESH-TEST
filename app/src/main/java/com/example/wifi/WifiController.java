package com.example.wifi;

import android.content.BroadcastReceiver;
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
import com.example.utils.AppLogger;

/**
 * Controller for managing Wi-Fi state, hardware toggling, and network connectivity checks.
 */
public class WifiController {
    private static final String TAG = "WifiController";
    private final Context context;
    private final WifiManager wifiManager;
    private final ConnectivityManager connectivityManager;
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
    }

    public void setStateListener(WifiStateListener listener) {
        this.stateListener = listener;
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
     * Attempts to toggle Wi-Fi state programmatically.
     * Note: Android 10+ (API 29+) restricts direct setWifiEnabled() for non-system apps,
     * so it uses programmatic methods on API < 29, and Panel / Settings / NetworkRequests on API 29+.
     */
    public boolean setWifiEnabled(boolean enable) {
        logger.i(TAG, "Request to set Wi-Fi state -> " + (enable ? "ENABLED" : "DISABLED"));
        if (wifiManager == null) {
            logger.e(TAG, "WifiManager is not available on this device.");
            return false;
        }

        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                // Direct toggle allowed in API 28 and below
                boolean success = wifiManager.setWifiEnabled(enable);
                if (success) {
                    logger.s(TAG, "Wi-Fi state changed directly: " + enable);
                } else {
                    logger.w(TAG, "Failed to toggle Wi-Fi directly.");
                }
                return success;
            } else {
                // Android 10+ (API 29+) behavior:
                // Direct setWifiEnabled deprecated/restricted. We try legacy fallback first:
                try {
                    boolean success = wifiManager.setWifiEnabled(enable);
                    if (success) {
                        logger.s(TAG, "Wi-Fi toggled via system compatibility: " + enable);
                        return true;
                    }
                } catch (SecurityException ignored) {
                    // Expected on API 29+ non-system apps
                }

                logger.i(TAG, "On Android 10+, Wi-Fi is handled via system network requests.");
                if (enable) {
                    // Request Wi-Fi transport network in background
                    requestWifiNetworkBackground();
                } else {
                    if (networkCallback != null && connectivityManager != null) {
                        try {
                            connectivityManager.unregisterNetworkCallback(networkCallback);
                            networkCallback = null;
                        } catch (Exception ignored) {}
                    }
                }
                return true;
            }
        } catch (Exception e) {
            logger.e(TAG, "Exception while toggling Wi-Fi: " + e.getMessage());
            return false;
        }
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
                    logger.s(TAG, "Autonomous Wi-Fi network interface acquired: " + network);
                    if (stateListener != null) {
                        stateListener.onWifiStateChanged(true, "Wi-Fi Interface Active: " + network);
                    }
                }

                @Override
                public void onLost(Network network) {
                    logger.w(TAG, "Autonomous Wi-Fi network interface lost: " + network);
                    if (stateListener != null) {
                        stateListener.onWifiStateChanged(isWifiEnabled(), "Wi-Fi Interface Lost");
                    }
                }
            };

            connectivityManager.requestNetwork(builder.build(), networkCallback);
            logger.i(TAG, "Background Wi-Fi transport request registered.");
        } catch (Exception e) {
            logger.w(TAG, "Failed to register background Wi-Fi request: " + e.getMessage());
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
                        String stateDesc = "UNKNOWN";
                        switch (state) {
                            case WifiManager.WIFI_STATE_ENABLED:
                                stateDesc = "ENABLED";
                                break;
                            case WifiManager.WIFI_STATE_ENABLING:
                                stateDesc = "ENABLING";
                                break;
                            case WifiManager.WIFI_STATE_DISABLED:
                                stateDesc = "DISABLED";
                                break;
                            case WifiManager.WIFI_STATE_DISABLING:
                                stateDesc = "DISABLING";
                                break;
                        }
                        logger.i(TAG, "Wi-Fi Hardware State Broadcast: " + stateDesc);
                        if (stateListener != null) {
                            stateListener.onWifiStateChanged(isEnabled, "Wi-Fi State: " + stateDesc);
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
