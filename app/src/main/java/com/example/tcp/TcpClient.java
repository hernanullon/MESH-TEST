package com.example.tcp;

import android.os.Handler;
import android.os.Looper;
import com.example.model.TcpPacket;
import com.example.utils.AppLogger;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Autonomous Background TCP Client for connecting to peer nodes in the local Wi-Fi mesh.
 */
public class TcpClient {
    private static final String TAG = "TcpClient";
    private final String targetHost;
    private final int targetPort;
    private final AppLogger logger = AppLogger.getInstance();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private Thread workerThread;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final BlockingQueue<TcpPacket> sendQueue = new LinkedBlockingQueue<>();

    private TcpClientListener listener;
    private long totalBytesSent = 0;
    private long totalBytesReceived = 0;
    private long lastPingTime = 0;
    private long currentLatencyMs = 0;

    public interface TcpClientListener {
        void onConnected(String host, int port);
        void onDisconnected(String reason);
        void onPacketReceived(TcpPacket packet);
        void onPacketSent(TcpPacket packet);
        void onLatencyUpdated(long latencyMs);
    }

    public TcpClient(String targetHost, int targetPort) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    public void setListener(TcpClientListener listener) {
        this.listener = listener;
    }

    public boolean isConnected() {
        return isConnected.get();
    }

    public String getTargetEndpoint() {
        return targetHost + ":" + targetPort;
    }

    public long getCurrentLatencyMs() {
        return currentLatencyMs;
    }

    public synchronized void start() {
        if (isRunning.get()) return;
        isRunning.set(true);

        logger.i(TAG, "Starting autonomous TCP Client -> " + targetHost + ":" + targetPort);
        workerThread = new Thread(this::runConnectionLifecycle, "TcpClient-Worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void runConnectionLifecycle() {
        while (isRunning.get()) {
            try {
                logger.i(TAG, "Attempting TCP connection to " + targetHost + ":" + targetPort + "...");
                socket = new Socket();
                socket.connect(new InetSocketAddress(targetHost, targetPort), 5000);
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);

                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

                isConnected.set(true);
                logger.s(TAG, "TCP Client CONNECTED to " + targetHost + ":" + targetPort);

                if (listener != null) {
                    mainHandler.post(() -> listener.onConnected(targetHost, targetPort));
                }

                // Send discovery handshake
                sendPacket(new TcpPacket(TcpPacket.Type.DISCOVER, "LocalClient", "Server@" + targetPort, "DEVICE_HANDSHAKE"));

                // Reader and sender loop
                Thread senderThread = new Thread(this::runSenderLoop, "TcpClient-Sender");
                senderThread.setDaemon(true);
                senderThread.start();

                long nextHeartbeat = System.currentTimeMillis() + 10000;

                String line;
                while (isRunning.get() && isConnected.get() && (line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    int bytes = line.getBytes(StandardCharsets.UTF_8).length;
                    totalBytesReceived += bytes;

                    TcpPacket packet = TcpPacket.fromJson(line);
                    if (packet != null) {
                        if (packet.getType() == TcpPacket.Type.PONG && lastPingTime > 0) {
                            currentLatencyMs = System.currentTimeMillis() - lastPingTime;
                            if (listener != null) {
                                mainHandler.post(() -> listener.onLatencyUpdated(currentLatencyMs));
                            }
                        } else {
                            logger.i(TAG, "Client received: [" + packet.getType() + "] " + packet.getPayload());
                        }

                        if (listener != null) {
                            mainHandler.post(() -> listener.onPacketReceived(packet));
                        }
                    }

                    // Send periodic heartbeat ping
                    if (System.currentTimeMillis() >= nextHeartbeat) {
                        lastPingTime = System.currentTimeMillis();
                        sendPacket(new TcpPacket(TcpPacket.Type.PING, "LocalClient", "Server@" + targetPort, "PING"));
                        nextHeartbeat = System.currentTimeMillis() + 10000;
                    }
                }
            } catch (Exception e) {
                if (isRunning.get()) {
                    logger.w(TAG, "TCP Client connection error: " + e.getMessage());
                }
            } finally {
                cleanSocket();
                if (listener != null && isRunning.get()) {
                    mainHandler.post(() -> listener.onDisconnected("Connection lost / Reconnecting..."));
                }
            }

            // Auto-reconnection delay
            if (isRunning.get()) {
                try {
                    Thread.sleep(4000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    private void runSenderLoop() {
        while (isRunning.get() && isConnected.get()) {
            try {
                TcpPacket packet = sendQueue.take();
                String data = packet.toJson() + "\n";
                byte[] raw = data.getBytes(StandardCharsets.UTF_8);

                synchronized (this) {
                    if (writer != null) {
                        writer.write(data);
                        writer.flush();
                        totalBytesSent += raw.length;
                        if (listener != null) {
                            mainHandler.post(() -> listener.onPacketSent(packet));
                        }
                    }
                }
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                logger.w(TAG, "Failed sending packet via TCP client: " + e.getMessage());
                cleanSocket();
                break;
            }
        }
    }

    public void sendPacket(TcpPacket packet) {
        sendQueue.offer(packet);
    }

    private synchronized void cleanSocket() {
        isConnected.set(false);
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
        socket = null;
        reader = null;
        writer = null;
    }

    public synchronized void stop() {
        isRunning.set(false);
        cleanSocket();
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        sendQueue.clear();
        logger.i(TAG, "TCP Client stopped.");
        if (listener != null) {
            mainHandler.post(() -> listener.onDisconnected("Stopped"));
        }
    }

    public long getTotalBytesSent() {
        return totalBytesSent;
    }

    public long getTotalBytesReceived() {
        return totalBytesReceived;
    }
}
