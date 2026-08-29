package com.example.utils;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Utility functions for local IP detection, Wi-Fi interface queries, and network helpers.
 */
public class NetworkUtils {

    /**
     * Resolves the local IPv4 address across active network interfaces (wlan, ap, p2p, eth).
     */
    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            // Priority 1: Check ap0 / wlan0 / swlan / p2p
            for (NetworkInterface intf : interfaces) {
                if (intf.getName().toLowerCase().contains("ap") ||
                    intf.getName().toLowerCase().contains("wlan") ||
                    intf.getName().toLowerCase().contains("p2p")) {
                    Enumeration<InetAddress> addresses = intf.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
            // Priority 2: Any non-loopback IPv4
            for (NetworkInterface intf : interfaces) {
                Enumeration<InetAddress> addresses = intf.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    /**
     * Returns a formatted Wi-Fi IP address if connected as a client.
     */
    public static String getWifiClientIp(Context context) {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                WifiInfo wifiInfo = wifiManager.getConnectionInfo();
                int ip = wifiInfo.getIpAddress();
                if (ip != 0) {
                    return String.format("%d.%d.%d.%d",
                            (ip & 0xff),
                            (ip >> 8 & 0xff),
                            (ip >> 16 & 0xff),
                            (ip >> 24 & 0xff));
                }
            }
        } catch (Exception ignored) {}
        return getLocalIpAddress();
    }

    /**
     * Formats bytes into human-readable B, KB, MB.
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %cB", bytes / Math.pow(1024, exp), pre);
    }
}
