package com.example.model;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Represents a connected TCP peer device.
 */
public class ConnectedClient {
    private final String clientId;
    private final String ipAddress;
    private final int port;
    private final long connectedTimestamp;
    private long lastHeartbeatTimestamp;
    private long bytesSent;
    private long bytesReceived;
    private boolean isAlive;
    private long latencyMs;

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss", Locale.US);

    public ConnectedClient(String clientId, String ipAddress, int port) {
        this.clientId = clientId;
        this.ipAddress = ipAddress;
        this.port = port;
        this.connectedTimestamp = System.currentTimeMillis();
        this.lastHeartbeatTimestamp = System.currentTimeMillis();
        this.bytesSent = 0;
        this.bytesReceived = 0;
        this.isAlive = true;
        this.latencyMs = 0;
    }

    public String getClientId() {
        return clientId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public int getPort() {
        return port;
    }

    public String getEndpoint() {
        return ipAddress + ":" + port;
    }

    public long getConnectedTimestamp() {
        return connectedTimestamp;
    }

    public String getFormattedConnectTime() {
        return TIME_FORMAT.format(new Date(connectedTimestamp));
    }

    public long getLastHeartbeatTimestamp() {
        return lastHeartbeatTimestamp;
    }

    public void updateHeartbeat() {
        this.lastHeartbeatTimestamp = System.currentTimeMillis();
        this.isAlive = true;
    }

    public long getBytesSent() {
        return bytesSent;
    }

    public void addBytesSent(long bytes) {
        this.bytesSent += bytes;
    }

    public long getBytesReceived() {
        return bytesReceived;
    }

    public void addBytesReceived(long bytes) {
        this.bytesReceived += bytes;
    }

    public boolean isAlive() {
        return isAlive;
    }

    public void setAlive(boolean alive) {
        isAlive = alive;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }
}
