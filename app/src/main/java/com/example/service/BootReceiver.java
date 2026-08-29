package com.example.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.example.utils.AppLogger;

/**
 * BroadcastReceiver in Java that automatically launches the persistent background service
 * on device boot or application update without user intervention.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "";
        AppLogger.getInstance().i(TAG, "BootReceiver triggered with action: " + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) ||
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {

            AppLogger.getInstance().s(TAG, "Starting PersistentWifiTcpService autonomously from background boot...");

            Intent serviceIntent = new Intent(context, PersistentWifiTcpService.class);
            serviceIntent.setAction(PersistentWifiTcpService.ACTION_START);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}
