package com.example.service;

import android.os.Handler;
import android.os.Looper;
import com.example.model.ConnectedClient;
import com.example.model.HotspotInfo;
import com.example.model.TcpPacket;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton state manager for mesh network, hotspot, TCP sockets, and background telemetry.
 */
public class MeshStateManager {
    private static final MeshStateManager INSTANCE = new MeshStateManager();

    private boolean isServiceRunning = false;
    private boolean isWifiHardwareEnabled = false;
    private String wifiStatusDetails = "Standby";

    private HotspotInfo hotspotInfo = HotspotInfo.disabled();
    private boolean isTcpServerRunning = false;
    private int tcpServerPort = 8888;

    private boolean isTcpClientConnected = false;
    private String tcpClientTarget = "";
    private long tcpClientLatency = 0;

    private List<ConnectedClient> connectedClients = new ArrayList<>();
    private long packetsSentCount = 0;
    private long packetsReceivedCount = 0;
    private long totalBytesTransferred = 0;
    private long serviceStartedTimestamp = 0;
    private com.example.model.telemetry.UnifiedTelemetrySnapshot latestTelemetrySnapshot = com.example.model.telemetry.UnifiedTelemetrySnapshot.empty("NODE-01");

    // Holds the latest readings received from external sensors (e.g., climatic, can, obd, etc.)
    // Key: type (e.g. "climatic"), Value: { json: JSONObject, receivedTimestamp: Long }
    private final ConcurrentHashMap<String, ExternalSensorEntry> latestExternalSensors = new ConcurrentHashMap<>();

    private static class ExternalSensorEntry {
        final JSONObject json;
        final long receivedTimestamp;

        ExternalSensorEntry(JSONObject json, long receivedTimestamp) {
            this.json = json;
            this.receivedTimestamp = receivedTimestamp;
        }
    }

    private final List<StateChangeListener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface StateChangeListener {
        void onStateChanged(MeshStateManager state);
        void onMessageReceived(TcpPacket packet, String from);
    }

    private MeshStateManager() {}

    public static MeshStateManager getInstance() {
        return INSTANCE;
    }

    public void registerListener(StateChangeListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
            listener.onStateChanged(this);
        }
    }

    public void unregisterListener(StateChangeListener listener) {
        listeners.remove(listener);
    }

    public void notifyStateChanged() {
        mainHandler.post(() -> {
            for (StateChangeListener l : listeners) {
                l.onStateChanged(this);
            }
        });
    }

    public void notifyPacketReceived(TcpPacket packet, String source) {
        if (packet != null) {
            recordExternalSensorPacket(packet);
        }
        mainHandler.post(() -> {
            for (StateChangeListener l : listeners) {
                l.onMessageReceived(packet, source);
            }
        });
    }

    /**
     * Stores incoming external telemetry packets in memory so they can be merged into
     * the unified real-time stream.
     */
    private void recordExternalSensorPacket(TcpPacket packet) {
        try {
            String effectiveType = packet.getEffectiveType();
            if (effectiveType == null || effectiveType.trim().isEmpty()) return;
            effectiveType = effectiveType.trim().toLowerCase();

            // Ignore internal transport control packets
            if ("ping".equals(effectiveType) || "pong".equals(effectiveType)
                    || "ack".equals(effectiveType) || "discover".equals(effectiveType)
                    || "realtime".equals(effectiveType) || "batch".equals(effectiveType)) {
                return;
            }

            // Extract the data fields as JSONObject
            JSONObject dataObj = null;
            String rawPayload = packet.getPayload();
            if (rawPayload != null && !rawPayload.trim().isEmpty()) {
                try {
                    dataObj = new JSONObject(rawPayload.trim());
                } catch (Exception ignored) {}
            }

            if (dataObj == null) {
                try {
                    dataObj = new JSONObject(packet.toJson());
                } catch (Exception ignored) {}
            }

            if (dataObj != null) {
                latestExternalSensors.put(effectiveType, new ExternalSensorEntry(dataObj, System.currentTimeMillis()));
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Returns the currently active external sensors (received within maxAgeMs, default 30s).
     */
    public Map<String, JSONObject> getActiveExternalSensors(long maxAgeMs) {
        long now = System.currentTimeMillis();
        ConcurrentHashMap<String, JSONObject> active = new ConcurrentHashMap<>();
        for (Map.Entry<String, ExternalSensorEntry> entry : latestExternalSensors.entrySet()) {
            if (now - entry.getValue().receivedTimestamp <= maxAgeMs) {
                active.put(entry.getKey(), entry.getValue().json);
            }
        }
        return active;
    }

    // Getters and Setters
    public boolean isServiceRunning() {
        return isServiceRunning;
    }

    public void setServiceRunning(boolean running) {
        this.isServiceRunning = running;
        if (running && serviceStartedTimestamp == 0) {
            serviceStartedTimestamp = System.currentTimeMillis();
        } else if (!running) {
            serviceStartedTimestamp = 0;
        }
        notifyStateChanged();
    }

    public boolean isWifiHardwareEnabled() {
        return isWifiHardwareEnabled;
    }

    public void setWifiHardwareEnabled(boolean enabled) {
        this.isWifiHardwareEnabled = enabled;
        notifyStateChanged();
    }

    public void setWifiHardwareEnabled(boolean enabled, String details) {
        this.isWifiHardwareEnabled = enabled;
        if (details != null) {
            this.wifiStatusDetails = details;
        }
        notifyStateChanged();
    }

    public String getWifiStatusDetails() {
        return wifiStatusDetails;
    }

    public void setWifiStatusDetails(String details) {
        this.wifiStatusDetails = details != null ? details : "Standby";
        notifyStateChanged();
    }

    public HotspotInfo getHotspotInfo() {
        return hotspotInfo;
    }

    public void setHotspotInfo(HotspotInfo info) {
        this.hotspotInfo = info != null ? info : HotspotInfo.disabled();
        notifyStateChanged();
    }

    public boolean isHotspotActive() {
        return hotspotInfo != null && hotspotInfo.isEnabled();
    }

    public boolean isTcpServerRunning() {
        return isTcpServerRunning;
    }

    public void setTcpServerRunning(boolean tcpServerRunning, int port) {
        this.isTcpServerRunning = tcpServerRunning;
        tcpServerPort = port;
        notifyStateChanged();
    }

    public int getTcpServerPort() {
        return tcpServerPort;
    }

    public boolean isTcpClientConnected() {
        return isTcpClientConnected;
    }

    public void setTcpClientConnected(boolean connected, String target, long latency) {
        this.isTcpClientConnected = connected;
        tcpClientTarget = target;
        tcpClientLatency = latency;
        notifyStateChanged();
    }

    public String getTcpClientTarget() {
        return tcpClientTarget;
    }

    public long getTcpClientLatency() {
        return tcpClientLatency;
    }

    public List<ConnectedClient> getConnectedClients() {
        return Collections.unmodifiableList(connectedClients);
    }

    public void setConnectedClients(List<ConnectedClient> clients) {
        this.connectedClients = clients != null ? new ArrayList<>(clients) : new ArrayList<>();
        notifyStateChanged();
    }

    public long getPacketsSentCount() {
        return packetsSentCount;
    }

    public void setPacketsSentCount(long count) {
        this.packetsSentCount = count;
        notifyStateChanged();
    }

    public long getPacketsReceivedCount() {
        return packetsReceivedCount;
    }

    public void setPacketsReceivedCount(long count) {
        this.packetsReceivedCount = count;
        notifyStateChanged();
    }

    public long getTotalBytesTransferred() {
        return totalBytesTransferred;
    }

    public void setTotalBytesTransferred(long bytes) {
        this.totalBytesTransferred = bytes;
        notifyStateChanged();
    }

    public com.example.model.telemetry.UnifiedTelemetrySnapshot getLatestTelemetrySnapshot() {
        return latestTelemetrySnapshot;
    }

    public void setLatestTelemetrySnapshot(com.example.model.telemetry.UnifiedTelemetrySnapshot snapshot) {
        if (snapshot != null) {
            this.latestTelemetrySnapshot = snapshot;
            // Do NOT call notifyStateChanged() here to avoid continuous UI recompositions & high memory/CPU usage
        }
    }
}
