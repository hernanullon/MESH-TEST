package com.example.service.hardware;

import android.content.Context;
import com.example.model.ScheduleConfig;
import com.example.model.telemetry.DeviceStatusTelemetry;
import com.example.service.ScheduleManager;
import com.example.service.telemetry.DeviceStatusCollector;
import com.example.utils.AppLogger;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controller responsible for managing battery charging and thermal cooling (fans)
 * using two parallel, decoupled asynchronous processes:
 *
 * PROCESO 1 (Control de Banderas a cada 30 segundos):
 * - Evalúa los valores recolectados del smartphone (SoC de batería y Temperatura).
 * - status_recharge:
 *     if soc_actual <= socMin -> status_recharge = 1
 *     if soc_actual >= socMax -> status_recharge = 0
 *     (en el intervalo intermedio conserva su valor anterior)
 * - status_fans:
 *     if tempActual <= tempMin -> status_fans = 0
 *     if tempActual >= tempMax -> status_fans = 1
 *     (en el intervalo intermedio conserva su valor anterior)
 *
 * PROCESO 2 (Despacho periódico de comandos a cada 1 minuto):
 * - Envía al ESP32 driver (puerto 8888) el valor actual de status_recharge y status_fans:
 *     {"task": "charge", "action": status_recharge}
 *     {"task": "fans", "action": status_fans}
 */
public class HardwareRelayController {
    private static final String TAG = "HardwareRelayController";
    private static final int DRIVER_PORT = 8888;
    private static final int SOCKET_TIMEOUT_MS = 3500;

    private static final long FLAG_EVALUATION_INTERVAL_SEC = 30; // Proceso 1: cada 30s
    private static final long DISPATCH_INTERVAL_SEC = 60;        // Proceso 2: cada 1min

    private static volatile HardwareRelayController instance;

    private final Context context;
    private final AppLogger logger = AppLogger.getInstance();
    private final DeviceStatusCollector deviceStatusCollector;

    // Banderas de estado lógico (0 = apagado, 1 = encendido)
    private final AtomicInteger statusRecharge = new AtomicInteger(0);
    private final AtomicInteger statusFans = new AtomicInteger(0);

    // Ejecutor programado para los dos procesos en paralelo
    private ScheduledExecutorService schedulerExecutor;
    private volatile boolean isRunning = false;

    // Métricas y estado para UI y diagnóstico
    private volatile String lastCommandStatus = "Ready";
    private volatile long lastCommandTimestamp = 0;
    private volatile int lastEvaluatedBattery = -1;
    private volatile float lastEvaluatedTemp = -1.0f;

    private HardwareRelayController(Context context) {
        this.context = context.getApplicationContext();
        this.deviceStatusCollector = new DeviceStatusCollector(this.context);
    }

    public static HardwareRelayController getInstance(Context context) {
        if (instance == null) {
            synchronized (HardwareRelayController.class) {
                if (instance == null) {
                    instance = new HardwareRelayController(context);
                }
            }
        }
        return instance;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;

        logger.s(TAG, "Iniciando HardwareRelayController (Proceso 1: banderas c/30s, Proceso 2: envío JSON c/1min)...");

        if (schedulerExecutor == null || schedulerExecutor.isShutdown()) {
            schedulerExecutor = Executors.newScheduledThreadPool(2);

            // Proceso 1: Chequeo y actualización de banderas a cada 30 segundos
            schedulerExecutor.scheduleWithFixedDelay(
                    this::runProcess1FlagEvaluation,
                    2,
                    FLAG_EVALUATION_INTERVAL_SEC,
                    TimeUnit.SECONDS
            );

            // Proceso 2: Envío periódico de comandos JSON al ESP32 a cada 1 minuto (60s)
            schedulerExecutor.scheduleWithFixedDelay(
                    this::runProcess2PeriodicDispatcher,
                    5,
                    DISPATCH_INTERVAL_SEC,
                    TimeUnit.SECONDS
            );
        }
    }

    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;
        logger.w(TAG, "Deteniendo HardwareRelayController...");

        if (schedulerExecutor != null && !schedulerExecutor.isShutdown()) {
            schedulerExecutor.shutdownNow();
            schedulerExecutor = null;
        }
    }

    public int getStatusRecharge() {
        return statusRecharge.get();
    }

    public int getStatusFans() {
        return statusFans.get();
    }

    public boolean isChargeRelayOn() {
        return statusRecharge.get() == 1;
    }

    public boolean isFansRelayOn() {
        return statusFans.get() == 1;
    }

    public String getLastCommandStatus() {
        return lastCommandStatus;
    }

    public long getLastCommandTimestamp() {
        return lastCommandTimestamp;
    }

    public int getLastEvaluatedBattery() {
        return lastEvaluatedBattery;
    }

    public float getLastEvaluatedTemp() {
        return lastEvaluatedTemp;
    }

    // =========================================================================
    // PROCESO 1: Control de activación/desactivación de banderas (cada 30 seg)
    // =========================================================================
    /**
     * Proceso 1 que corre autónomamente cada 30 segundos:
     * Toma muestra del smartphone y actualiza las banderas status_recharge y status_fans:
     *
     * status_recharge:
     *   if (soc_actual <= socMin) status_recharge = 1;
     *   if (soc_actual >= socMax) status_recharge = 0;
     *
     * status_fans:
     *   if (tempActual <= tempMin) status_fans = 0;
     *   if (tempActual >= tempMax) status_fans = 1;
     */
    public void runProcess1FlagEvaluation() {
        if (!isRunning) return;
        try {
            DeviceStatusTelemetry telemetry = deviceStatusCollector.sample();
            if (telemetry != null) {
                evaluateTelemetry(telemetry);
            }
        } catch (Throwable t) {
            logger.w(TAG, "Error en Proceso 1 (evaluación de banderas): " + t.getMessage());
        }
    }

    /**
     * Aplica las reglas lógicas exactas solicitadas sobre las banderas
     */
    public void evaluateTelemetry(DeviceStatusTelemetry telemetry) {
        if (telemetry == null) return;

        ScheduleConfig config = ScheduleManager.getInstance().getConfig();
        int socMin = (config != null) ? config.getBatteryMin() : 20; // e.g. 20%
        int socMax = (config != null) ? config.getBatteryMax() : 80; // e.g. 80%
        int tempMin = (config != null) ? config.getTempMin() : 25;    // e.g. 25°C
        int tempMax = (config != null) ? config.getTempMax() : 42;    // e.g. 42°C

        // Validación de cordura de umbrales
        if (socMin >= socMax) socMin = Math.max(5, socMax - 10);
        if (tempMin >= tempMax) tempMin = Math.max(10, tempMax - 5);

        int socActual = telemetry.getBatteryLevelPercent();
        float tempActual = telemetry.getBatteryTemperatureC();

        lastEvaluatedBattery = socActual;
        lastEvaluatedTemp = tempActual;

        // ---------------------------------------------------------------------
        // 1. Lógica para status_recharge:
        // status_recharge = 0
        // if soc_actual <= socMin:  status_recharge = 1
        // if soc_actual >= socMax: status_recharge = 0
        // ---------------------------------------------------------------------
        int currentRecharge = statusRecharge.get();
        if (socActual <= socMin) {
            if (currentRecharge != 1) {
                statusRecharge.set(1);
                logger.i(TAG, String.format(java.util.Locale.US,
                        "[Proceso 1] Batería baja: %d%% <= %d%% -> status_recharge = 1 (ENCENDER)", socActual, socMin));
            }
        } else if (socActual >= socMax) {
            if (currentRecharge != 0) {
                statusRecharge.set(0);
                logger.i(TAG, String.format(java.util.Locale.US,
                        "[Proceso 1] Batería máxima alcanzada: %d%% >= %d%% -> status_recharge = 0 (APAGAR)", socActual, socMax));
            }
        }

        // ---------------------------------------------------------------------
        // 2. Lógica para status_fans:
        // status_fans = 0
        // if tempActual <= tempMin:  status_fans = 0
        // if tempActual >= tempMax: status_fans = 1
        // ---------------------------------------------------------------------
        int currentFans = statusFans.get();
        if (tempActual <= tempMin) {
            if (currentFans != 0) {
                statusFans.set(0);
                logger.i(TAG, String.format(java.util.Locale.US,
                        "[Proceso 1] Temperatura normalizada: %.1f°C <= %d°C -> status_fans = 0 (APAGAR)", tempActual, tempMin));
            }
        } else if (tempActual >= tempMax) {
            if (currentFans != 1) {
                statusFans.set(1);
                logger.w(TAG, String.format(java.util.Locale.US,
                        "[Proceso 1] Temperatura alta detectada: %.1f°C >= %d°C -> status_fans = 1 (ENCENDER)", tempActual, tempMax));
            }
        }
    }

    // =========================================================================
    // PROCESO 2: Envío periódico del valor de las banderas en JSON (cada 1 min)
    // =========================================================================
    /**
     * Proceso 2 que corre autónomamente cada 1 minuto:
     * Toma el valor de status_recharge y status_fans y envía los JSON al driver ESP32:
     *   {"task": "charge", "action": status_recharge}
     *   {"task": "fans", "action": status_fans}
     */
    public void runProcess2PeriodicDispatcher() {
        if (!isRunning) return;
        try {
            int currentRecharge = statusRecharge.get();
            int currentFans = statusFans.get();

            logger.i(TAG, String.format(java.util.Locale.US,
                    "[Proceso 2] Envío periódico (1min) -> charge: action=%d | fans: action=%d",
                    currentRecharge, currentFans));

            // Envía comando de recarga con la bandera actual
            sendSingleCommandSync("charge", currentRecharge, "Periodic 1min dispatch (status_recharge=" + currentRecharge + ")");

            // Pequeño intervalo de 200ms entre sockets para no colisionar en el ESP32
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {}

            // Envía comando de ventiladores con la bandera actual
            sendSingleCommandSync("fans", currentFans, "Periodic 1min dispatch (status_fans=" + currentFans + ")");
        } catch (Throwable t) {
            logger.w(TAG, "Error en Proceso 2 (despacho periódico de comandos): " + t.getMessage());
        }
    }

    /**
     * Métodos manuales / inmediatos para pruebas desde la UI
     */
    public void setChargeState(int action, String reason) {
        statusRecharge.set(action);
        Executors.newSingleThreadExecutor().execute(() -> {
            sendSingleCommandSync("charge", action, reason);
        });
    }

    public void setFansState(int action, String reason) {
        statusFans.set(action);
        Executors.newSingleThreadExecutor().execute(() -> {
            sendSingleCommandSync("fans", action, reason);
        });
    }

    /**
     * Envía un paquete JSON por TCP Socket al puerto 8888 del ESP32:
     * {"task": "<task>", "action": <action>}
     */
    private void sendSingleCommandSync(String task, int action, String reason) {
        ScheduleConfig config = ScheduleManager.getInstance().getConfig();
        String host = (config != null) ? config.getIpDriver() : "192.168.43.100";
        if (host == null || host.trim().isEmpty()) {
            host = "192.168.43.100";
        }
        host = host.trim();

        String jsonPayload;
        try {
            JSONObject obj = new JSONObject();
            obj.put("task", task);
            obj.put("action", action);
            jsonPayload = obj.toString() + "\n";
        } catch (Exception e) {
            jsonPayload = "{\"task\":\"" + task + "\",\"action\":" + action + "}\n";
        }

        Socket socket = null;
        try {
            logger.i(TAG, String.format("TCP -> ESP32 (%s:%d): %s [Motivo: %s]", host, DRIVER_PORT, jsonPayload.trim(), reason));
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, DRIVER_PORT), SOCKET_TIMEOUT_MS);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            writer.write(jsonPayload);
            writer.flush();

            lastCommandStatus = String.format("OK: %s=%d enviado a %s", task, action, host);
            lastCommandTimestamp = System.currentTimeMillis();
            logger.s(TAG, "Comando entregado con éxito al ESP32: " + jsonPayload.trim());
        } catch (Exception e) {
            lastCommandStatus = String.format("Err: %s (%s:%d)", e.getMessage() != null ? e.getMessage() : "Timeout", host, DRIVER_PORT);
            lastCommandTimestamp = System.currentTimeMillis();
            logger.w(TAG, String.format("Fallo al entregar comando a ESP32 (%s:%d): %s", host, DRIVER_PORT, e.getMessage()));
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {}
            }
        }
    }
}
