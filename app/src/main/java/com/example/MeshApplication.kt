package com.example

import android.app.Application
import android.util.Log
import com.example.utils.AppLogger

class MeshApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Global crash interception to log fatal crashes cleanly into Logcat and AppLogger
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val errorMsg = "CRASH IN THREAD [${thread.name}]: ${throwable.javaClass.simpleName}: ${throwable.message}\n" +
                    Log.getStackTraceString(throwable)
            Log.e("MeshApplication", errorMsg)
            try {
                AppLogger.getInstance().e("CRASH", errorMsg)
            } catch (ignored: Throwable) {}

            defaultHandler?.uncaughtException(thread, throwable)
        }

        Log.i("MeshApplication", "MeshApplication initialized successfully.")
        com.example.data.local.TelemetryBufferRepository.getInstance(this)

        try {
            val hotspotMgr = com.example.wifi.LocalHotspotManager(this)
            if (hotspotMgr.isDeviceOwner) {
                hotspotMgr.grantAllDeviceOwnerPermissions()
                hotspotMgr.enforce2GhzSystemSettings()
                hotspotMgr.configureSoftAp2Ghz()
                AppLogger.getInstance().s("MeshApplication", "Device Owner detectado: Permisos y ajustes de radio 2.4 GHz inicializados.")
            }
        } catch (_: Throwable) {}
    }
}
