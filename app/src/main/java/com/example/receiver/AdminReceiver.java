package com.example.receiver;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import com.example.utils.AppLogger;

/**
 * Receiver to enable Device Owner / Device Administrator privileges via ADB:
 * adb shell dpm set-device-owner com.example/.receiver.AdminReceiver
 */
public class AdminReceiver extends DeviceAdminReceiver {
    private static final String TAG = "AdminReceiver";

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        AppLogger.getInstance().s(TAG, "Privilegios de Administrador / Device Owner ACTIVADOS");
        Toast.makeText(context, "Modo Administrador de Dispositivo Activado", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        AppLogger.getInstance().w(TAG, "Privilegios de Administrador / Device Owner DESACTIVADOS");
    }
}
