package com.example.tcp;

import android.os.Handler;
import android.os.Looper;
import com.example.model.ConnectedClient;
import com.example.model.TcpPacket;
import com.example.utils.AppLogger;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Robust multi-threaded background TCP Server for local Wi-Fi mesh communication without internet.
 */
public class TcpServer {
    private static final String TAG = "TcpServer";
    private final int port;
    private final AppLogger logger = AppLogger.getInstance();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ServerSocket serverSocket;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private ExecutorService threadPool;
    private Thread acceptThread;

    private final Map<String, ClientHandler> activeClients = new ConcurrentHashMap<>();
    private TcpServerListener listener;

    private long totalBytesSent = 0;
    private long totalBytesReceived = 0;

    public interface TcpServerListener {
        void onServerStarted(int port);
        void onServerStopped();
        void onClientConnected(ConnectedClient client);
        void onClientDisconnected(ConnectedClient client);
        void onPacketReceived(ConnectedClient client, TcpPacket packet);
        void onPacketSent(String clientId, TcpPacket packet);
    }

    public TcpServer(int port) {
        this.port = port;
    }

    public void setListener(TcpServerListener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return isRunning.get();
    }

    public int getPort() {
        return port;
    }

    public synchronized void start() {
        if (isRunning.get()) {
            logger.w(TAG, "TCP Server is already running on port " + port);
            return;
        }

        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
            isRunning.set(true);
            threadPool = Executors.newCachedThreadPool();

            logger.s(TAG, "TCP Server STARTED on port " + port + " (listening for local Wi-Fi peers)");

            acceptThread = new Thread(this::runAcceptLoop, "TcpServer-AcceptThread");
            acceptThread.setDaemon(true);
            acceptThread.start();

            if (listener != null) {
                mainHandler.post(() -> listener.onServerStarted(port));
            }
        } catch (IOException e) {
            logger.e(TAG, "Failed to start TCP Server on port " + port + ": " + e.getMessage());
            isRunning.set(false);
        }
    }

    private void runAcceptLoop() {
        while (isRunning.get() && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setKeepAlive(true);
                clientSocket.setTcpNoDelay(true);

                String clientIp = clientSocket.getInetAddress().getHostAddress();
                int clientPort = clientSocket.getPort();
                String clientId = clientIp + ":" + clientPort;

                logger.s(TAG, "Incoming TCP Connection from: " + clientId);

                ClientHandler handler = new ClientHandler(clientSocket, clientId, clientIp, clientPort);
                activeClients.put(clientId, handler);
                threadPool.execute(handler);

                if (listener != null) {
                    mainHandler.post(() -> listener.onClientConnected(handler.getClientModel()));
                }
            } catch (IOException e) {
                if (isRunning.get()) {
                    logger.w(TAG, "Socket accept error: " + e.getMessage());
                }
            }
        }
    }

    public synchronized void stop() {
        if (!isRunning.get()) return;

        logger.i(TAG, "Stopping TCP Server...");
        isRunning.set(false);

        // Close all client connections
        for (ClientHandler handler : activeClients.values()) {
            handler.close();
        }
        activeClients.clear();

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
            serverSocket = null;
        }

        if (threadPool != null) {
            threadPool.shutdownNow();
            threadPool = null;
        }

        logger.i(TAG, "TCP Server stopped.");
        if (listener != null) {
            mainHandler.post(listener::onServerStopped);
        }
    }

    /**
     * Broadcasts a TCP packet to all active connected peer clients.
     */
    public boolean broadcastPacket(TcpPacket packet) {
        if (!isRunning.get() || activeClients.isEmpty()) {
            logger.w(TAG, "Cannot broadcast: No active client connections.");
            return false;
        }

        String jsonPayload = packet.toJson() + "\n";
        byte[] rawBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);

        boolean anySuccess = false;
        for (ClientHandler handler : activeClients.values()) {
            boolean sent = handler.sendRaw(jsonPayload, rawBytes.length);
            if (sent) {
                anySuccess = true;
                totalBytesSent += rawBytes.length;
            }
        }

        if (anySuccess) {
            logger.i(TAG, "Broadcasted packet [" + packet.getType() + "] to " + activeClients.size() + " peer(s)");
            if (listener != null) {
                mainHandler.post(() -> listener.onPacketSent("ALL", packet));
            }
        }
        return anySuccess;
    }

    /**
     * Sends a packet to a specific connected client.
     */
    public boolean sendToClient(String clientId, TcpPacket packet) {
        ClientHandler handler = activeClients.get(clientId);
        if (handler == null) {
            logger.w(TAG, "Client not found: " + clientId);
            return false;
        }

        String jsonPayload = packet.toJson() + "\n";
        byte[] rawBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
        boolean sent = handler.sendRaw(jsonPayload, rawBytes.length);
        if (sent) {
            totalBytesSent += rawBytes.length;
            logger.i(TAG, "Sent packet [" + packet.getType() + "] to " + clientId);
            if (listener != null) {
                mainHandler.post(() -> listener.onPacketSent(clientId, packet));
            }
        }
        return sent;
    }

    public List<ConnectedClient> getConnectedClients() {
        List<ConnectedClient> list = new ArrayList<>();
        for (ClientHandler handler : activeClients.values()) {
            list.add(handler.getClientModel());
        }
        return Collections.unmodifiableList(list);
    }

    public long getTotalBytesSent() {
        return totalBytesSent;
    }

    public long getTotalBytesReceived() {
        return totalBytesReceived;
    }

    /**
     * Inner handler thread for each connected socket.
     */
    private class ClientHandler implements Runnable {
        private final Socket socket;
        private final ConnectedClient clientModel;
        private BufferedReader reader;
        private BufferedWriter writer;
        private final AtomicBoolean isConnected = new AtomicBoolean(true);

        ClientHandler(Socket socket, String clientId, String ip, int port) {
            this.socket = socket;
            this.clientModel = new ConnectedClient(clientId, ip, port);
        }

        ConnectedClient getClientModel() {
            return clientModel;
        }

        @Override
        public void run() {
            try {
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

                // Send immediate ACK / DISCOVER response to peer
                TcpPacket welcomePacket = new TcpPacket(
                        TcpPacket.Type.ACK,
                        "Server@" + port,
                        clientModel.getClientId(),
                        "CONNECTED_TO_LOCAL_MESH_SERVER"
                );
                sendRaw(welcomePacket.toJson() + "\n", welcomePacket.toJson().length());

                String line;
                while (isConnected.get() && (line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
                    clientModel.addBytesReceived(lineBytes);
                    totalBytesReceived += lineBytes;
                    clientModel.updateHeartbeat();

                    TcpPacket packet = TcpPacket.fromJson(line);
                    if (packet != null) {
                        handleIncomingPacket(packet);
                    }
                }
            } catch (IOException e) {
                if (isConnected.get()) {
                    logger.d(TAG, "Client read loop terminated: " + clientModel.getClientId() + " (" + e.getMessage() + ")");
                }
            } finally {
                close();
            }
        }

        private void handleIncomingPacket(TcpPacket packet) {
            if (packet.getType() == TcpPacket.Type.PING || packet.getType() == TcpPacket.Type.HEARTBEAT) {
                // Auto-reply with PONG / ACK without user interaction
                TcpPacket pong = new TcpPacket(TcpPacket.Type.PONG, "Server@" + port, clientModel.getClientId(), "PONG:" + packet.getId());
                sendRaw(pong.toJson() + "\n", pong.toJson().length());
                logger.d(TAG, "Heartbeat PING answered with PONG for " + clientModel.getClientId());
            } else {
                logger.i(TAG, "Packet received from [" + clientModel.getClientId() + "] Type=" + packet.getType() + " Payload=" + packet.getPayload());
            }

            if (listener != null) {
                mainHandler.post(() -> listener.onPacketReceived(clientModel, packet));
            }
        }

        synchronized boolean sendRaw(String data, int byteCount) {
            if (!isConnected.get() || writer == null) return false;
            try {
                writer.write(data);
                writer.flush();
                clientModel.addBytesSent(byteCount);
                return true;
            } catch (IOException e) {
                logger.w(TAG, "Failed sending to " + clientModel.getClientId() + ": " + e.getMessage());
                close();
                return false;
            }
        }

        void close() {
            if (!isConnected.getAndSet(false)) return;
            try {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException ignored) {}

            activeClients.remove(clientModel.getClientId());
            clientModel.setAlive(false);
            logger.w(TAG, "Client disconnected: " + clientModel.getClientId());

            if (listener != null) {
                mainHandler.post(() -> listener.onClientDisconnected(clientModel));
            }
        }
    }
}
