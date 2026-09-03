package com.example.service;

import android.os.Handler;
import android.os.Looper;
import com.example.model.ConnectedClient;
import com.example.model.HotspotInfo;
import com.example.model.TcpPacket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
        mainHandler.post(() -> {
            for (StateChangeListener l : listeners) {
                l.onMessageReceived(packet, source);
            }
        });
    }

    // Getters and Setters
    public boolean isServiceRunning() {
        return isServiceRunning;
    }

    public void setServiceRunning(boolean serviceRunning) {
        isServiceRunning = serviceRunning;
        if (serviceRunning && serviceStartedTimestamp == 0) {
            serviceStartedTimestamp = System.currentTimeMillis();
        } else if (!serviceRunning) {
            serviceStartedTimestamp = 0;
        }
        notifyStateChanged();
    }

    public long getServiceStartedTimestamp() {
        return serviceStartedTimestamp;
    }

    public boolean isWifiHardwareEnabled() {
        return isWifiHardwareEnabled;
    }

    public void setWifiHardwareEnabled(boolean wifiHardwareEnabled, String details) {
        isWifiHardwareEnabled = wifiHardwareEnabled;
        wifiStatusDetails = details;
        notifyStateChanged();
    }

    public String getWifiStatusDetails() {
        return wifiStatusDetails;
    }

    public HotspotInfo getHotspotInfo() {
        return hotspotInfo;
    }

    public void setHotspotInfo(HotspotInfo hotspotInfo) {
        this.hotspotInfo = hotspotInfo;
        notifyStateChanged();
    }

    public boolean isTcpServerRunning() {
        return isTcpServerRunning;
    }

    public void setTcpServerRunning(boolean tcpServerRunning, int port) {
        isTcpServerRunning = tcpServerRunning;
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
        isTcpClientConnected = connected;
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
