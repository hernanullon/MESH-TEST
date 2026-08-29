package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.model.*
import com.example.service.MeshStateManager
import com.example.service.PersistentWifiTcpService
import com.example.service.ScheduleManager
import com.example.ui.theme.*
import com.example.utils.AppLogger
import com.example.utils.NetworkUtils
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    MainAppContainer()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppContainer() {
    val context = LocalContext.current
    val stateManager = remember { MeshStateManager.getInstance() }
    val logger = remember { AppLogger.getInstance() }
    val scheduleManager = remember { ScheduleManager.getInstance().apply { init(context) } }

    // Navigation State: 0 = Página 1 (Horarios y Red Fija), 1 = Página 2 (Gestión TCP)
    var currentScreen by remember { mutableIntStateOf(0) }

    // Service & Mesh State
    var isServiceRunning by remember { mutableStateOf(stateManager.isServiceRunning) }
    var isTcpServerRunning by remember { mutableStateOf(stateManager.isTcpServerRunning) }
    var tcpServerPort by remember { mutableIntStateOf(stateManager.tcpServerPort) }
    var hotspotInfo by remember { mutableStateOf(stateManager.hotspotInfo) }
    var connectedClients by remember { mutableStateOf(stateManager.connectedClients) }
    var packetsSent by remember { mutableLongStateOf(stateManager.packetsSentCount) }
    var packetsReceived by remember { mutableLongStateOf(stateManager.packetsReceivedCount) }
    var logs by remember { mutableStateOf(logger.logs) }
    var config by remember { mutableStateOf(scheduleManager.config) }

    // Permissions
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    LaunchedEffect(Unit) {
        try {
            val requiredPermissions = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                    requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
                }
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (requiredPermissions.isNotEmpty()) {
                permissionsLauncher.launch(requiredPermissions.toTypedArray())
            }
        } catch (e: Exception) {
            logger.e("MainActivity", "Error requesting permissions: ${e.message}")
        }
    }

    DisposableEffect(Unit) {
        val meshListener = object : MeshStateManager.StateChangeListener {
            override fun onStateChanged(state: MeshStateManager) {
                isServiceRunning = state.isServiceRunning
                isTcpServerRunning = state.isTcpServerRunning
                tcpServerPort = state.tcpServerPort
                hotspotInfo = state.hotspotInfo
                connectedClients = state.connectedClients
                packetsSent = state.packetsSentCount
                packetsReceived = state.packetsReceivedCount
            }

            override fun onMessageReceived(packet: TcpPacket, from: String) {
                logs = logger.logs
            }
        }
        stateManager.registerListener(meshListener)

        val logListener = object : AppLogger.LogListener {
            override fun onNewLog(log: NetworkLog) { logs = logger.logs }
            override fun onLogsCleared() { logs = emptyList() }
        }
        logger.registerListener(logListener)

        val schedListener = object : ScheduleManager.ScheduleChangeListener {
            override fun onScheduleChanged(newConfig: ScheduleConfig) { config = newConfig }
            override fun onScheduleEvaluated(wifiShouldBeOn: Boolean, hotspotShouldBeOn: Boolean, summary: String) {}
        }
        scheduleManager.registerListener(schedListener)

        onDispose {
            stateManager.unregisterListener(meshListener)
            logger.unregisterListener(logListener)
            scheduleManager.unregisterListener(schedListener)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            logs = logger.logs
            isServiceRunning = stateManager.isServiceRunning
            hotspotInfo = stateManager.hotspotInfo
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isServiceRunning) StatusActive else TextMuted)
                        )
                        Text(
                            text = if (currentScreen == 0) "Configuración de Red y Horarios" else "Gestión de Red Local TCP",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                },
                actions = {
                    if (currentScreen == 1) {
                        IconButton(
                            onClick = { currentScreen = 0 },
                            modifier = Modifier.testTag("action_edit_schedule")
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = "Modificar Horarios y Red",
                                tint = CyberCyanPrimary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            AnimatedContent(
                targetState = currentScreen,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally { width -> width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> -width } + fadeOut()
                    } else {
                        slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                slideOutHorizontally { width -> width } + fadeOut()
                    }
                },
                label = "screen_switch"
            ) { screen ->
                when (screen) {
                    0 -> ScheduleSetupScreen(
                        context = context,
                        scheduleManager = scheduleManager,
                        initialConfig = config,
                        hotspotInfo = hotspotInfo,
                        tcpServerPort = tcpServerPort,
                        onScheduleSaved = {
                            startPersistentService(context)
                            currentScreen = 1
                        }
                    )
                    1 -> TcpManagementScreen(
                        context = context,
                        scheduleConfig = config,
                        isServerRunning = isTcpServerRunning,
                        serverPort = tcpServerPort,
                        hotspotInfo = hotspotInfo,
                        connectedClients = connectedClients,
                        packetsSent = packetsSent,
                        packetsReceived = packetsReceived,
                        logs = logs
                    )
                }
            }
        }
    }
}

/**
 * PÁGINA 1: Formulario de configuración de horario y Credenciales Fijas de Red (SSID / Clave / Puerto)
 */
@Composable
fun ScheduleSetupScreen(
    context: Context,
    scheduleManager: ScheduleManager,
    initialConfig: ScheduleConfig,
    hotspotInfo: HotspotInfo,
    tcpServerPort: Int,
    onScheduleSaved: () -> Unit
) {
    var startHourText by remember { mutableStateOf(String.format(Locale.US, "%02d", initialConfig.offStartHour)) }
    var startMinText by remember { mutableStateOf(String.format(Locale.US, "%02d", initialConfig.offStartMinute)) }
    var endHourText by remember { mutableStateOf(String.format(Locale.US, "%02d", initialConfig.offEndHour)) }
    var endMinText by remember { mutableStateOf(String.format(Locale.US, "%02d", initialConfig.offEndMinute)) }

    // Campos de Red Fija para que todos los nodos conozcan siempre las mismas credenciales
    var customSsidText by remember { mutableStateOf(initialConfig.customSsid) }
    var customPassText by remember { mutableStateOf(initialConfig.customPassphrase) }
    var customPortText by remember { mutableStateOf(initialConfig.tcpPort.toString()) }

    var currentTimeStr by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        while (true) {
            val cal = Calendar.getInstance()
            currentTimeStr = sdf.format(cal.time)
            delay(1000)
        }
    }

    val sH = (startHourText.toIntOrNull() ?: 4).coerceIn(0, 23)
    val sM = (startMinText.toIntOrNull() ?: 0).coerceIn(0, 59)
    val eH = (endHourText.toIntOrNull() ?: 5).coerceIn(0, 23)
    val eM = (endMinText.toIntOrNull() ?: 30).coerceIn(0, 59)
    val portNumber = (customPortText.toIntOrNull() ?: 8888).coerceIn(1024, 65535)

    val previewOffStr = String.format(Locale.US, "%02d:%02d a %02d:%02d", sH, sM, eH, eM)
    val previewTcpOnStr = remember(sH, sM, eH, eM) {
        val tempConfig = ScheduleConfig()
        tempConfig.applyInvertedSchedule(sH, sM, eH, eM)
        tempConfig.hotspotScheduleFormatted
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("schedule_setup_page"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Encabezado
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, CyberCyanPrimary.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Configuración de Horario y Red Fija",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Configura el horario de apagado de la Red TCP (prioridad Wi-Fi) y define el Nombre de Red (SSID) y Contraseña fijos para que todos los nodos puedan asociarse automáticamente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Hora Actual del Dispositivo:", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkSurfaceVariant,
                            border = BorderStroke(1.dp, CyberCyanPrimary.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = currentTimeStr.ifEmpty { "00:00:00" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = CyberCyanPrimary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Credenciales Fijas de la Red Local para Nodos
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, TechTealSecondary.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = TechTealSecondary)
                        Text(
                            text = "Credenciales Fijas de Red (SSID & Clave)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TechTealSecondary
                        )
                    }

                    Text(
                        text = "Estas credenciales serán las que tus nodos secundarios (ESP32, móviles, sensores) usarán para conectarse siempre de forma fija.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )

                    OutlinedTextField(
                        value = customSsidText,
                        onValueChange = { customSsidText = it },
                        label = { Text("Nombre de Red Fijo (SSID)") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.WifiTethering, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().testTag("input_custom_ssid")
                    )

                    OutlinedTextField(
                        value = customPassText,
                        onValueChange = { customPassText = it },
                        label = { Text("Contraseña Fija (Mínimo 8 caracteres)") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().testTag("input_custom_pass")
                    )

                    OutlinedTextField(
                        value = customPortText,
                        onValueChange = { if (it.length <= 5) customPortText = it },
                        label = { Text("Puerto Socket TCP (ej. 8888)") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Sensors, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().testTag("input_custom_port")
                    )
                }
            }
        }

        // Formulario de Horario (Red TCP Apagada / Wi-Fi ON)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = CyberCyanPrimary)
                        Text(
                            text = "Horario: Red TCP Apagada / Wi-Fi Encendido",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = CyberCyanPrimary
                        )
                    }

                    // Hora Inicio
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Hora Inicio:", modifier = Modifier.width(90.dp), color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = startHourText,
                            onValueChange = { if (it.length <= 2) startHourText = it },
                            label = { Text("HH (0-23)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("input_start_hh")
                        )
                        Text(":", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        OutlinedTextField(
                            value = startMinText,
                            onValueChange = { if (it.length <= 2) startMinText = it },
                            label = { Text("MM (0-59)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("input_start_mm")
                        )
                    }

                    // Hora Fin
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Hora Fin:", modifier = Modifier.width(90.dp), color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = endHourText,
                            onValueChange = { if (it.length <= 2) endHourText = it },
                            label = { Text("HH (0-23)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("input_end_hh")
                        )
                        Text(":", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        OutlinedTextField(
                            value = endMinText,
                            onValueChange = { if (it.length <= 2) endMinText = it },
                            label = { Text("MM (0-59)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("input_end_mm")
                        )
                    }
                }
            }
        }

        // Resumen Visual Automático del Complemento
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                border = BorderStroke(1.dp, TechTealSecondary.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Esquema de Conmutación Resultante:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    // Card 1: Red TCP Apagada / Wi-Fi ON
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = CyberCyanPrimary.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, CyberCyanPrimary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Wifi, contentDescription = null, tint = CyberCyanPrimary)
                            Column {
                                Text("1. Red TCP APAGADA / Wi-Fi ACTIVO (Configurado)", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text(
                                    text = previewOffStr,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberCyanPrimary
                                )
                            }
                        }
                    }

                    // Card 2: Red TCP Encendida (Complemento Automático con Red, Clave y Puerto)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = TechTealSecondary.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, TechTealSecondary.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.CellWifi, contentDescription = null, tint = TechTealSecondary)
                                Column {
                                    Text("2. Red TCP ENCENDIDA / Wi-Fi APAGADO (Complemento)", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    Text(
                                        text = previewTcpOnStr,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = TechTealSecondary
                                    )
                                }
                            }

                            // Datos de Acceso a la Red y Puerto
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = DarkSurface.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "SSID: ${customSsidText.ifEmpty { "Direct-Mesh-Master" }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = CyberCyanPrimary
                                    )
                                    Text(
                                        text = "Clave: ${customPassText.ifEmpty { "MeshPassword123" }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = "Puerto: $portNumber",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = StatusActive
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Botón Guardar y Avanzar
        item {
            Button(
                onClick = {
                    val finalSsid = if (customSsidText.trim().isNotEmpty()) customSsidText.trim() else "Direct-Mesh-Master"
                    val finalPass = if (customPassText.trim().isNotEmpty()) customPassText.trim() else "MeshPassword123"

                    scheduleManager.setInvertedSchedule(sH, sM, eH, eM)
                    scheduleManager.updateNetworkCredentials(finalSsid, finalPass, portNumber)

                    Toast.makeText(context, "Configuración y Red Fija guardadas", Toast.LENGTH_SHORT).show()
                    onScheduleSaved()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberCyanPrimary,
                    contentColor = CyberCyanOnPrimary
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("save_schedule_button")
            ) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Guardar y Pasar a Gestión TCP",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

/**
 * PÁGINA 2: Gestión de la Red TCP con acceso a modificar el horario
 */
@Composable
fun TcpManagementScreen(
    context: Context,
    scheduleConfig: ScheduleConfig,
    isServerRunning: Boolean,
    serverPort: Int,
    hotspotInfo: HotspotInfo,
    connectedClients: List<ConnectedClient>,
    packetsSent: Long,
    packetsReceived: Long,
    logs: List<NetworkLog>
) {
    var messageText by remember { mutableStateOf("") }
    val localIp = remember { NetworkUtils.getLocalIpAddress() }

    val networkSsid = if (hotspotInfo.ssid.isNotEmpty()) hotspotInfo.ssid else scheduleConfig.customSsid
    val networkPass = if (hotspotInfo.passphrase.isNotEmpty()) hotspotInfo.passphrase else scheduleConfig.customPassphrase
    val displayIp = if (hotspotInfo.ipAddress.isNotEmpty() && hotspotInfo.ipAddress != "0.0.0.0") {
        hotspotInfo.ipAddress
    } else if (!localIp.isNullOrEmpty()) {
        localIp
    } else {
        "192.168.43.1"
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("tcp_management_page"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Banner de Horario Activo
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, TechTealSecondary.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.AccessTime,
                            contentDescription = null,
                            tint = TechTealSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            "Horario de Conmutación Activo",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }
                    Text(
                        text = "Wi-Fi ON (TCP Apagada): ${scheduleConfig.wifiScheduleFormatted}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = CyberCyanPrimary
                    )
                    Text(
                        text = "Red TCP ON (Complemento): ${scheduleConfig.hotspotScheduleFormatted}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TechTealSecondary
                    )
                }
            }
        }

        // Estado del Servidor TCP y Credenciales de Red Local
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Estado Servidor
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Servidor Socket TCP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Escuchando peticiones de nodos en red local", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isServerRunning) StatusActive.copy(alpha = 0.15f) else TextMuted.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, if (isServerRunning) StatusActive.copy(alpha = 0.5f) else TextMuted.copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = if (isServerRunning) "ACTIVO" else "DETENIDO",
                                color = if (isServerRunning) StatusActive else TextMuted,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }

                    // Panel de Datos de Conexión de la Red Local y Socket TCP
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = DarkSurfaceVariant,
                        border = BorderStroke(1.dp, CyberCyanPrimary.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.WifiTethering, contentDescription = null, tint = CyberCyanPrimary, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "Datos de Conexión para Nodos / Clientes",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberCyanPrimary
                                )
                            }

                            HorizontalDivider(color = DarkBorder, thickness = 0.8.dp)

                            // Nombre de Red (SSID)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Wifi, contentDescription = null, tint = CyberCyanPrimary, modifier = Modifier.size(16.dp))
                                    Text("Nombre Red (SSID):", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                                Text(
                                    text = networkSsid,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberCyanPrimary,
                                    modifier = Modifier.testTag("tcp_network_ssid_value")
                                )
                            }

                            // Contraseña de Red
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = TechTealSecondary, modifier = Modifier.size(16.dp))
                                    Text("Contraseña Red:", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                                Text(
                                    text = networkPass,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hotspotInfo.passphrase.isNotEmpty() || scheduleConfig.customPassphrase.isNotEmpty()) TextPrimary else TextSecondary,
                                    modifier = Modifier.testTag("tcp_network_pass_value")
                                )
                            }

                            // Puerto de Escucha TCP
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Sensors, contentDescription = null, tint = StatusActive, modifier = Modifier.size(16.dp))
                                    Text("Puerto Socket TCP:", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                                Text(
                                    text = "$serverPort",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = StatusActive,
                                    modifier = Modifier.testTag("tcp_network_port_value")
                                )
                            }

                            // IP del Nodo Servidor
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Router, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                    Text("IP Nodo Servidor:", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                                Text(
                                    text = displayIp,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    modifier = Modifier.testTag("tcp_network_ip_value")
                                )
                            }

                            // Botón de Copiar Credenciales y Puerto
                            OutlinedButton(
                                onClick = {
                                    val clipData = "Red Wi-Fi: $networkSsid\nClave: $networkPass\nIP: $displayIp\nPuerto TCP: $serverPort"
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("Credenciales Red TCP", clipData))
                                    Toast.makeText(context, "Credenciales de Red y Puerto copiados al portapapeles", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("button_copy_network_credentials"),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCyanPrimary),
                                border = BorderStroke(1.dp, CyberCyanPrimary.copy(alpha = 0.5f)),
                                contentPadding = PaddingValues(vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copiar Nombre, Clave y Puerto", fontSize = 13.sp)
                            }
                        }
                    }

                    // Métricas de Tráfico
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Enviados", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text("$packetsSent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = CyberCyanPrimary)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Recibidos", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text("$packetsReceived", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TechTealSecondary)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Nodos", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text("${connectedClients.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = StatusActive)
                            }
                        }
                    }
                }
            }
        }

        // Envío de Mensajes TCP
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Transmitir Mensaje TCP",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        placeholder = { Text("Escribe un mensaje para difundir en la red TCP...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("tcp_message_input")
                    )

                    Button(
                        onClick = {
                            if (messageText.isNotBlank()) {
                                try {
                                    val intent = Intent(context, PersistentWifiTcpService::class.java).apply {
                                        action = PersistentWifiTcpService.ACTION_SEND_BROADCAST
                                        putExtra(PersistentWifiTcpService.EXTRA_MESSAGE, messageText.trim())
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(intent)
                                    } else {
                                        context.startService(intent)
                                    }
                                    Toast.makeText(context, "Mensaje enviado a la red", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Servicio en segundo plano no disponible: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                                messageText = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyberCyanPrimary,
                            contentColor = CyberCyanOnPrimary
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("tcp_send_button")
                    ) {
                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Difundir en la Red TCP")
                    }
                }
            }
        }

        // Nodos Conectados
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Nodos TCP Conectados (${connectedClients.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (connectedClients.isEmpty()) {
                        Text(
                            text = "Esperando que otros dispositivos se conecten a la red...",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    } else {
                        connectedClients.forEach { client ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = DarkSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(StatusActive))
                                        Text(client.ipAddress, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    }
                                    Text("Puerto ${client.port}", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Registro de Mensajes y Tráfico
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Registro de Mensajes Recibidos",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val tcpLogs = logs.filter { it.tag.contains("TCP", ignoreCase = true) || it.tag.contains("Mesh", ignoreCase = true) }.takeLast(15)
                    if (tcpLogs.isEmpty()) {
                        Text(
                            text = "No se han recibido paquetes TCP todavía.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )
                    } else {
                        tcpLogs.reversed().forEach { log ->
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = DarkSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(log.formattedTime, fontSize = 10.sp, color = TextMuted)
                                        Text(log.tag, fontSize = 10.sp, color = CyberCyanPrimary)
                                    }
                                    Text(
                                        text = log.message,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun startPersistentService(context: Context) {
    try {
        val intent = Intent(context, PersistentWifiTcpService::class.java).apply {
            action = PersistentWifiTcpService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    } catch (e: Exception) {
        AppLogger.getInstance().e("MainActivity", "Error iniciando servicio en segundo plano: " + e.message)
    }
}
