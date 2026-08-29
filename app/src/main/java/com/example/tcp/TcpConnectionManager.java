package com.example.tcp;

import com.example.model.ConnectedClient;
import com.example.model.TcpPacket;
import com.example.utils.AppLogger;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * High-level coordinator for TCP networking, routing messages, managing local server and peer client sockets.
 */
public class TcpConnectionManager {
    private static final String TAG = "TcpConnectionManager";
    private final AppLogger logger = AppLogger.getInstance();

    private TcpServer server;
    private TcpClient client;
    private int serverPort = 8888;

    private long totalPacketsSent = 0;
    private long totalPacketsReceived = 0;

    private final List<TcpNetworkEventListener> listeners = new CopyOnWriteArrayList<>();

    public interface TcpNetworkEventListener {
        void onServerStatusChanged(boolean running, int port);
        void onClientStatusChanged(boolean connected, String endpoint, long latencyMs);
        void onClientsListUpdated(List<ConnectedClient> clients);
        void onPacketReceived(TcpPacket packet, String source);
        void onPacketSent(TcpPacket packet, String destination);
    }

    public TcpConnectionManager() {}

    public void registerListener(TcpNetworkEventListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unregisterListener(TcpNetworkEventListener listener) {
        listeners.remove(listener);
    }

    public synchronized void startServer(int port) {
        this.serverPort = port;
        if (server != null && server.isRunning()) {
            if (server.getPort() == port) return;
            server.stop();
        }

        server = new TcpServer(port);
        server.setListener(new TcpServer.TcpServerListener() {
            @Override
            public void onServerStarted(int port) {
                notifyServerStatus(true, port);
            }

            @Override
            public void onServerStopped() {
                notifyServerStatus(false, port);
                notifyClientsList(java.util.Collections.emptyList());
            }

            @Override
            public void onClientConnected(ConnectedClient client) {
                logger.s(TAG, "New peer registered: " + client.getEndpoint());
                TcpServer currentServer = server;
                notifyClientsList(currentServer != null ? currentServer.getConnectedClients() : java.util.Collections.emptyList());
            }

            @Override
            public void onClientDisconnected(ConnectedClient client) {
                logger.w(TAG, "Peer removed: " + client.getEndpoint());
                TcpServer currentServer = server;
                notifyClientsList(currentServer != null ? currentServer.getConnectedClients() : java.util.Collections.emptyList());
            }

            @Override
            public void onPacketReceived(ConnectedClient client, TcpPacket packet) {
                totalPacketsReceived++;
                for (TcpNetworkEventListener l : listeners) {
                    l.onPacketReceived(packet, client.getEndpoint());
                }
            }

            @Override
            public void onPacketSent(String clientId, TcpPacket packet) {
                totalPacketsSent++;
                for (TcpNetworkEventListener l : listeners) {
                    l.onPacketSent(packet, clientId);
                }
            }
        });

        server.start();
    }

    public synchronized void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        notifyServerStatus(false, serverPort);
    }

    public boolean isServerRunning() {
        return server != null && server.isRunning();
    }

    public int getServerPort() {
        return serverPort;
    }

    public List<ConnectedClient> getConnectedClients() {
        if (server != null) {
            return server.getConnectedClients();
        }
        return java.util.Collections.emptyList();
    }

    public synchronized void connectToPeer(String host, int port) {
        if (client != null) {
            client.stop();
        }

        client = new TcpClient(host, port);
        client.setListener(new TcpClient.TcpClientListener() {
            @Override
            public void onConnected(String host, int port) {
                notifyClientStatus(true, host + ":" + port, 0);
            }

            @Override
            public void onDisconnected(String reason) {
                notifyClientStatus(false, host + ":" + port, 0);
            }

            @Override
            public void onPacketReceived(TcpPacket packet) {
                totalPacketsReceived++;
                for (TcpNetworkEventListener l : listeners) {
                    l.onPacketReceived(packet, host + ":" + port);
                }
            }

            @Override
            public void onPacketSent(TcpPacket packet) {
                totalPacketsSent++;
                for (TcpNetworkEventListener l : listeners) {
                    l.onPacketSent(packet, host + ":" + port);
                }
            }

            @Override
            public void onLatencyUpdated(long latencyMs) {
                notifyClientStatus(true, host + ":" + port, latencyMs);
            }
        });

        client.start();
    }

    public synchronized void disconnectFromPeer() {
        if (client != null) {
            client.stop();
            client = null;
        }
        notifyClientStatus(false, "", 0);
    }

    public boolean isClientConnected() {
        return client != null && client.isConnected();
    }

    public String getClientTargetEndpoint() {
        return client != null ? client.getTargetEndpoint() : "";
    }

    public boolean broadcast(String payload) {
        TcpPacket packet = new TcpPacket(TcpPacket.Type.DATA, "HostServer", "ALL", payload);
        boolean sent = false;
        if (server != null && server.isRunning()) {
            sent = server.broadcastPacket(packet);
        }
        if (client != null && client.isConnected()) {
            client.sendPacket(packet);
            sent = true;
        }
        return sent;
    }

    public boolean sendDirect(String recipientId, String payload) {
        TcpPacket packet = new TcpPacket(TcpPacket.Type.DATA, "Node", recipientId, payload);
        if (server != null && server.isRunning()) {
            return server.sendToClient(recipientId, packet);
        }
        if (client != null && client.isConnected()) {
            client.sendPacket(packet);
            return true;
        }
        return false;
    }

    public long getTotalPacketsSent() {
        return totalPacketsSent;
    }

    public long getTotalPacketsReceived() {
        return totalPacketsReceived;
    }

    public long getTotalBytes() {
        long bytes = 0;
        if (server != null) {
            bytes += server.getTotalBytesSent() + server.getTotalBytesReceived();
        }
        if (client != null) {
            bytes += client.getTotalBytesSent() + client.getTotalBytesReceived();
        }
        return bytes;
    }

    private void notifyServerStatus(boolean running, int port) {
        for (TcpNetworkEventListener l : listeners) {
            l.onServerStatusChanged(running, port);
        }
    }

    private void notifyClientStatus(boolean connected, String endpoint, long latency) {
        for (TcpNetworkEventListener l : listeners) {
            l.onClientStatusChanged(connected, endpoint, latency);
        }
    }

    private void notifyClientsList(List<ConnectedClient> clients) {
        for (TcpNetworkEventListener l : listeners) {
            l.onClientsListUpdated(clients);
        }
    }
}
