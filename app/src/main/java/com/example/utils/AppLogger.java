package com.example.utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.example.model.NetworkLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Central thread-safe logger for background network events.
 */
public class AppLogger {
    private static final int MAX_LOGS = 300;
    private static final AppLogger INSTANCE = new AppLogger();

    private final List<NetworkLog> logList = new ArrayList<>();
    private final List<LogListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface LogListener {
        void onNewLog(NetworkLog log);
        void onLogsCleared();
    }

    private AppLogger() {}

    public static AppLogger getInstance() {
        return INSTANCE;
    }

    public synchronized void addLog(NetworkLog.Level level, String tag, String message) {
        NetworkLog logEntry = new NetworkLog(level, tag, message);
        logList.add(0, logEntry); // Newest first
        if (logList.size() > MAX_LOGS) {
            logList.remove(logList.size() - 1);
        }

        // Print to Logcat as well
        switch (level) {
            case ERROR:
                Log.e("WiFiTcpMesh-" + tag, message);
                break;
            case WARN:
                Log.w("WiFiTcpMesh-" + tag, message);
                break;
            case DEBUG:
                Log.d("WiFiTcpMesh-" + tag, message);
                break;
            default:
                Log.i("WiFiTcpMesh-" + tag, message);
                break;
        }

        mainHandler.post(() -> {
            for (LogListener listener : listeners) {
                listener.onNewLog(logEntry);
            }
        });
    }

    public void i(String tag, String message) {
        addLog(NetworkLog.Level.INFO, tag, message);
    }

    public void s(String tag, String message) {
        addLog(NetworkLog.Level.SUCCESS, tag, message);
    }

    public void w(String tag, String message) {
        addLog(NetworkLog.Level.WARN, tag, message);
    }

    public void e(String tag, String message) {
        addLog(NetworkLog.Level.ERROR, tag, message);
    }

    public void d(String tag, String message) {
        addLog(NetworkLog.Level.DEBUG, tag, message);
    }

    public synchronized List<NetworkLog> getLogs() {
        return Collections.unmodifiableList(new ArrayList<>(logList));
    }

    public synchronized void clearLogs() {
        logList.clear();
        mainHandler.post(() -> {
            for (LogListener listener : listeners) {
                listener.onLogsCleared();
            }
        });
    }

    public void registerListener(LogListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unregisterListener(LogListener listener) {
        listeners.remove(listener);
    }
}
