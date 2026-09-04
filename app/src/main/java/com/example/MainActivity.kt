package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import com.example.data.local.*
import com.example.service.MeshStateManager
import com.example.service.PersistentWifiTcpService
import com.example.service.ScheduleManager
import com.example.ui.components.LocalPersistenceBufferCard
import com.example.ui.components.AmqpCloudCard
import com.example.ui.theme.*
import com.example.utils.AppLogger
import com.example.utils.NetworkUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    // Navigation State: 0 = Setup / Configuración, 1 = Panel de Gestión Mesh TCP
    // Si ya está configurado (isConfigured == true), salta directo a la pantalla 1 (Dashboard)
    // Si es la primera vez o se limpiaron los datos, inicia en la pantalla 0 (Setup)
    var currentScreen by remember { mutableIntStateOf(if (scheduleManager.isConfigured) 1 else 0) }

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
    var telemetrySnapshot by remember { mutableStateOf(stateManager.latestTelemetrySnapshot) }
    var showGlobalResetDialog by remember { mutableStateOf(false) }

    // Permissions
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                              results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (locationGranted) {
            PersistentWifiTcpService.getInstance()?.onPermissionsGranted()
        }
        if (scheduleManager.isConfigured && !stateManager.isServiceRunning) {
            startPersistentService(context)
        }
    }

    LaunchedEffect(Unit) {
        try {
            val requiredPermissions = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requiredPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
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

            // Check and prompt Battery Optimization Exemption
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                    try {
                        val batteryIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(batteryIntent)
                    } catch (_: Exception) {}
                }
            }

            // Si ya estaba configurado de antes, arrancar el servicio de forma autónoma
            if (scheduleManager.isConfigured && !stateManager.isServiceRunning) {
                startPersistentService(context)
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
                telemetrySnapshot = state.latestTelemetrySnapshot
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
            delay(2000)
            logs = logger.logs
            isServiceRunning = stateManager.isServiceRunning
            hotspotInfo = stateManager.hotspotInfo
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (currentScreen == 0 && config.isConfigured) {
                        IconButton(
                            onClick = { currentScreen = 1 },
                            modifier = Modifier.testTag("action_back_to_dashboard")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Dashboard",
                                tint = CyberCyanPrimary
                            )
                        }
                    }
                },
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
                        Column {
                            Text(
                                text = if (currentScreen == 0) {
                                    if (config.isConfigured) "Schedule & Network Setup" else "Initial Configuration"
                                } else {
                                    "Mesh TCP: ${config.deviceId}"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            if (currentScreen == 1) {
                                Text(
                                    text = if (isServiceRunning) "Autonomous Mode 24/7 (Active)" else "Service Stopped",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isServiceRunning) TechTealSecondary else TextMuted
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showGlobalResetDialog = true },
                        modifier = Modifier.testTag("action_restore_app")
                    ) {
                        Icon(
                            Icons.Default.RestartAlt,
                            contentDescription = "Restaurar app a punto inicial",
                            tint = StatusWarning
                        )
                    }
                    if (currentScreen == 1) {
                        IconButton(
                            onClick = { currentScreen = 0 },
                            modifier = Modifier.testTag("action_edit_schedule")
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings & Schedules",
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
                        },
                        onNavigateBack = if (config.isConfigured) {
                            { currentScreen = 1 }
                        } else null,
                        onResetConfiguration = {
                            scheduleManager.resetConfiguration()
                            config = scheduleManager.config
                            currentScreen = 0
                            Toast.makeText(context, "Configuration reset to initial state", Toast.LENGTH_SHORT).show()
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
                        logs = logs,
                        telemetrySnapshot = telemetrySnapshot
                    )
                }
            }
        }

        if (showGlobalResetDialog) {
            AlertDialog(
                onDismissRequest = { showGlobalResetDialog = false },
                containerColor = DarkSurface,
                titleContentColor = StatusWarning,
                textContentColor = TextPrimary,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, tint = StatusWarning)
                        Text("Restaurar App al Punto Inicial")
                    }
                },
                text = {
                    Text(
                        "¿Deseas restaurar la aplicación al punto inicial de configuración? Se restablecerán todos los horarios, credenciales y parámetros a los valores de fábrica predeterminados y volverás a la pantalla de configuración inicial.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showGlobalResetDialog = false
                            scheduleManager.resetConfiguration()
                            config = scheduleManager.config
                            currentScreen = 0
                            Toast.makeText(context, "App restaurada al punto inicial", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = StatusWarning)
                    ) {
                        Text("Sí, Restaurar", fontWeight = FontWeight.Bold, color = DarkBackground)
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showGlobalResetDialog = false }) {
                        Text("Cancelar", color = TextPrimary)
                    }
                }
            )
        }
    }
}

/**
 * Dialogo Nativo de Selección de Horario basado en Material 3 TimePicker
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialTimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val timePickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(timePickerState.hour, timePickerState.minute)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = CyberCyanPrimary,
                    contentColor = CyberCyanOnPrimary
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("OK", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.AccessTime, contentDescription = null, tint = CyberCyanPrimary)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
        },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TimePicker(
                    state = timePickerState,
                    colors = TimePickerDefaults.colors(
                        clockDialColor = DarkSurfaceVariant,
                        clockDialSelectedContentColor = DarkBackground,
                        clockDialUnselectedContentColor = TextPrimary,
                        selectorColor = CyberCyanPrimary,
                        containerColor = DarkSurface,
                        periodSelectorBorderColor = CyberCyanPrimary,
                        periodSelectorSelectedContainerColor = CyberCyanPrimary.copy(alpha = 0.25f),
                        periodSelectorUnselectedContainerColor = DarkSurfaceVariant,
                        periodSelectorSelectedContentColor = CyberCyanPrimary,
                        periodSelectorUnselectedContentColor = TextMuted,
                        timeSelectorSelectedContainerColor = CyberCyanPrimary.copy(alpha = 0.25f),
                        timeSelectorUnselectedContainerColor = DarkSurfaceVariant,
                        timeSelectorSelectedContentColor = CyberCyanPrimary,
                        timeSelectorUnselectedContentColor = TextPrimary
                    )
                )
            }
        },
        containerColor = DarkSurface,
        shape = RoundedCornerShape(20.dp)
    )
}

/**
 * Componente interactivo para mostrar y seleccionar horas con el TimePicker
 */
@Composable
fun TimeSelectionCard(
    label: String,
    subLabel: String,
    hour: Int,
    minute: Int,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formattedTime = String.format(Locale.US, "%02d:%02d", hour, minute)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = DarkSurfaceVariant,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = accentColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "Set",
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Text(
                text = formattedTime,
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold,
                color = accentColor
            )

            Text(
                text = subLabel,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
    }
}

/**
 * Componente interactivo para seleccionar días de la semana de operación
 */
private data class DayInfo(val calendarDay: Int, val shortName: String, val fullName: String)

@Composable
fun DaysOfWeekSelector(
    selectedDays: Set<Int>,
    onToggleDay: (Int) -> Unit,
    onSelectPreset: (Set<Int>) -> Unit,
    modifier: Modifier = Modifier
) {
    val days = listOf(
        DayInfo(Calendar.MONDAY, "M", "Monday"),
        DayInfo(Calendar.TUESDAY, "T", "Tuesday"),
        DayInfo(Calendar.WEDNESDAY, "W", "Wednesday"),
        DayInfo(Calendar.THURSDAY, "T", "Thursday"),
        DayInfo(Calendar.FRIDAY, "F", "Friday"),
        DayInfo(Calendar.SATURDAY, "S", "Saturday"),
        DayInfo(Calendar.SUNDAY, "S", "Sunday")
    )

    val currentCalendarDay = remember { Calendar.getInstance().get(Calendar.DAY_OF_WEEK) }
    val isTodayActive = selectedDays.contains(currentCalendarDay)
    val todayName = ScheduleConfig.getDayFullName(currentCalendarDay)

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, CyberCyanPrimary.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.DateRange, contentDescription = null, tint = CyberCyanPrimary)
                    Text(
                        text = "Operating Days",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isTodayActive) StatusActive.copy(alpha = 0.15f) else StatusError.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, if (isTodayActive) StatusActive.copy(alpha = 0.4f) else StatusError.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = if (isTodayActive) "Today ($todayName): Active" else "Today ($todayName): Inactive",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isTodayActive) StatusActive else StatusError,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = "Select scheduled operating days. On non-operating days, all modules remain OFF.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            // Circular chips row for each day
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                days.forEach { day ->
                    val isSelected = selectedDays.contains(day.calendarDay)
                    val isToday = day.calendarDay == currentCalendarDay

                    Surface(
                        onClick = { onToggleDay(day.calendarDay) },
                        shape = CircleShape,
                        color = when {
                            isSelected -> CyberCyanPrimary
                            else -> DarkSurfaceVariant
                        },
                        border = BorderStroke(
                            width = if (isToday) 2.dp else 1.dp,
                            color = if (isToday) TechTealSecondary else if (isSelected) CyberCyanPrimary else DarkBorder
                        ),
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = day.shortName,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) CyberCyanOnPrimary else TextPrimary
                                )
                                if (isToday) {
                                    Box(
                                        modifier = Modifier
                                            .size(4.dp)
                                            .background(if (isSelected) CyberCyanOnPrimary else TechTealSecondary, CircleShape)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Quick preset buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onSelectPreset(setOf(
                            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
                        ))
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCyanPrimary),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text("All (7d)", style = MaterialTheme.typography.labelSmall)
                }

                OutlinedButton(
                    onClick = {
                        onSelectPreset(setOf(
                            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                            Calendar.THURSDAY, Calendar.FRIDAY
                        ))
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TechTealSecondary),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text("Mon - Fri", style = MaterialTheme.typography.labelSmall)
                }

                OutlinedButton(
                    onClick = {
                        onSelectPreset(setOf(Calendar.SATURDAY, Calendar.SUNDAY))
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text("Weekends", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * Page 1: Initial setup / Operating schedule, node device ID, mesh credentials, hardware thresholds & AMQP server
 */
@Composable
fun ScheduleSetupScreen(
    context: Context,
    scheduleManager: ScheduleManager,
    initialConfig: ScheduleConfig,
    hotspotInfo: HotspotInfo,
    tcpServerPort: Int,
    onScheduleSaved: () -> Unit,
    onNavigateBack: (() -> Unit)? = null,
    onResetConfiguration: (() -> Unit)? = null
) {
    var deviceIdText by remember { mutableStateOf(initialConfig.deviceId) }
    var startHour by remember { mutableIntStateOf(initialConfig.offStartHour) }
    var startMinute by remember { mutableIntStateOf(initialConfig.offStartMinute) }
    var endHour by remember { mutableIntStateOf(initialConfig.offEndHour) }
    var endMinute by remember { mutableIntStateOf(initialConfig.offEndMinute) }

    var selectedDays by remember {
        mutableStateOf(initialConfig.activeDays.map { it.toInt() }.toSet())
    }

    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showAdvancedHardware by remember { mutableStateOf(false) }
    var showAmqpSection by remember { mutableStateOf(true) }

    // Fixed Mesh Network Credentials
    var customSsidText by remember { mutableStateOf(initialConfig.customSsid) }
    var customPassText by remember { mutableStateOf(initialConfig.customPassphrase) }
    var customPortText by remember { mutableStateOf(initialConfig.tcpPort.toString()) }

    // Hardware Relays & Protection Thresholds
    var ipDriverText by remember { mutableStateOf(initialConfig.ipDriver) }
    var batteryMinText by remember { mutableStateOf(initialConfig.batteryMin.toString()) }
    var batteryMaxText by remember { mutableStateOf(initialConfig.batteryMax.toString()) }
    var tempMinText by remember { mutableStateOf(initialConfig.tempMin.toString()) }
    var tempMaxText by remember { mutableStateOf(initialConfig.tempMax.toString()) }

    // Internal Sensor Sampling Rates
    var locationIntervalText by remember { mutableStateOf(initialConfig.locationIntervalSeconds.toString()) }
    var inertialIntervalText by remember { mutableStateOf(initialConfig.inertialIntervalMs.toString()) }

    // AMQP / RabbitMQ Cloud Sync Server Credentials
    var amqpHostText by remember { mutableStateOf(initialConfig.amqpHost) }
    var amqpPortText by remember { mutableStateOf(initialConfig.amqpPort.toString()) }
    var amqpVHostText by remember { mutableStateOf(initialConfig.amqpVirtualHost) }
    var amqpUserText by remember { mutableStateOf(initialConfig.amqpUsername) }
    var amqpPassText by remember { mutableStateOf(initialConfig.amqpPassword) }
    var amqpExchangeText by remember { mutableStateOf(initialConfig.amqpExchange) }
    var amqpRoutingKeyText by remember { mutableStateOf(initialConfig.amqpRoutingKey) }
    var amqpQueueText by remember { mutableStateOf(initialConfig.amqpQueue) }
    var amqpSslEnabled by remember { mutableStateOf(initialConfig.isAmqpSslEnabled) }

    var currentTimeStr by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        while (true) {
            val cal = Calendar.getInstance()
            currentTimeStr = sdf.format(cal.time)
            delay(1000)
        }
    }

    val portNumber = (customPortText.toIntOrNull() ?: 8888).coerceIn(1024, 65535)
    val amqpPortNumber = (amqpPortText.toIntOrNull() ?: 5672).coerceIn(1, 65535)

    val previewOffStr = String.format(Locale.US, "%02d:%02d - %02d:%02d", startHour, startMinute, endHour, endMinute)
    val previewTcpOnStr = remember(startHour, startMinute, endHour, endMinute) {
        val tempConfig = ScheduleConfig()
        tempConfig.applyInvertedSchedule(startHour, startMinute, endHour, endMinute)
        tempConfig.hotspotScheduleFormatted
    }

    val daysFormatted = remember(selectedDays) {
        val tempConfig = ScheduleConfig()
        tempConfig.setActiveDays(selectedDays.map { java.lang.Integer.valueOf(it) })
        tempConfig.activeDaysFormatted
    }

    // Native Time Selection Dialogs
    if (showStartPicker) {
        MaterialTimePickerDialog(
            title = "Start Time (Wi-Fi ON)",
            initialHour = startHour,
            initialMinute = startMinute,
            onConfirm = { h, m ->
                startHour = h
                startMinute = m
            },
            onDismiss = { showStartPicker = false }
        )
    }

    if (showEndPicker) {
        MaterialTimePickerDialog(
            title = "End Time (Wi-Fi ON)",
            initialHour = endHour,
            initialMinute = endMinute,
            onConfirm = { h, m ->
                endHour = h
                endMinute = m
            },
            onDismiss = { showEndPicker = false }
        )
    }

    // Reset Confirmation Dialog
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = DarkSurface,
            titleContentColor = StatusError,
            textContentColor = TextPrimary,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = StatusError)
                    Text("Reset Configuration")
                }
            },
            text = {
                Text(
                    "This action will clear all saved preferences in SharedPreferences. " +
                    "The application will return to First Run setup mode.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetDialog = false
                        onResetConfiguration?.invoke()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = StatusError)
                ) {
                    Text("Yes, Reset", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag("schedule_setup_page"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (!initialConfig.isConfigured) CyberCyanPrimary.copy(alpha = 0.08f) else DarkSurface
                ),
                border = BorderStroke(1.dp, if (!initialConfig.isConfigured) CyberCyanPrimary else DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (!initialConfig.isConfigured) "Initial Setup (First Run)" else "Operating & Network Settings",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (!initialConfig.isConfigured) CyberCyanPrimary else TextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (!initialConfig.isConfigured)
                                    "Complete initial parameters to initialize node telemetry, schedule, and cloud sync."
                                else
                                    "Modify operating days, Wi-Fi window, mesh credentials, and broker settings.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }

                        if (!initialConfig.isConfigured) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = CyberCyanPrimary.copy(alpha = 0.2f),
                                border = BorderStroke(1.dp, CyberCyanPrimary)
                            ) {
                                Text(
                                    text = "NEW",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberCyanPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Device Time:", style = MaterialTheme.typography.bodySmall, color = TextMuted)
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

        // Node / Device Identifier
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, TechTealSecondary.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Badge, contentDescription = null, tint = TechTealSecondary)
                        Text(
                            text = "Node Identifier (Device ID)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TechTealSecondary
                        )
                    }
                    Text(
                        text = "Unique identifier attached to telemetry payloads and mesh packets.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )

                    OutlinedTextField(
                        value = deviceIdText,
                        onValueChange = { deviceIdText = it },
                        label = { Text("Device ID (e.g. NODE-01, BUS-102)") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Fingerprint, contentDescription = null, tint = TechTealSecondary) },
                        modifier = Modifier.fillMaxWidth().testTag("input_device_id")
                    )
                }
            }
        }

        // Operating Days Selector
        item {
            DaysOfWeekSelector(
                selectedDays = selectedDays,
                onToggleDay = { day ->
                    selectedDays = if (selectedDays.contains(day)) {
                        selectedDays - day
                    } else {
                        selectedDays + day
                    }
                },
                onSelectPreset = { preset ->
                    selectedDays = preset
                }
            )
        }

        // Schedule Form (Wi-Fi ON Window / Automatic TCP ON Complement)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.AccessTime, contentDescription = null, tint = CyberCyanPrimary)
                        Text(
                            text = "Wi-Fi Client Connection Window",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = CyberCyanPrimary
                        )
                    }

                    Text(
                        text = "Tap cards to set the Wi-Fi active window. All remaining time outside this window will run TCP Mesh & Hotspot automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TimeSelectionCard(
                            label = "Wi-Fi Start",
                            subLabel = "Wi-Fi ON / TCP OFF",
                            hour = startHour,
                            minute = startMinute,
                            icon = Icons.Default.PlayArrow,
                            accentColor = CyberCyanPrimary,
                            onClick = { showStartPicker = true },
                            modifier = Modifier.weight(1f)
                        )

                        TimeSelectionCard(
                            label = "Wi-Fi End",
                            subLabel = "Wi-Fi OFF / TCP ON",
                            hour = endHour,
                            minute = endMinute,
                            icon = Icons.Default.Stop,
                            accentColor = TechTealSecondary,
                            onClick = { showEndPicker = true },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Fixed Mesh Network Credentials
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
                            text = "Fixed Mesh Network Credentials",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TechTealSecondary
                        )
                    }

                    Text(
                        text = "Static Hotspot credentials that secondary nodes (ESP32 sensors, other nodes) connect to.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )

                    OutlinedTextField(
                        value = customSsidText,
                        onValueChange = { customSsidText = it },
                        label = { Text("Hotspot Network Name (SSID)") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.WifiTethering, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().testTag("input_custom_ssid")
                    )

                    OutlinedTextField(
                        value = customPassText,
                        onValueChange = { customPassText = it },
                        label = { Text("Passphrase (min 8 characters)") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().testTag("input_custom_pass")
                    )

                    OutlinedTextField(
                        value = customPortText,
                        onValueChange = { if (it.length <= 5) customPortText = it },
                        label = { Text("TCP Socket Port (e.g. 8888)") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Sensors, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().testTag("input_custom_port")
                    )
                }
            }
        }

        // AMQP / RabbitMQ Cloud Sync Server Credentials
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, CyberCyanPrimary.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, tint = CyberCyanPrimary)
                            Column {
                                Text(
                                    text = "AMQP / RabbitMQ Server Credentials",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberCyanPrimary
                                )
                                Text(
                                    text = "Telemetry ingestion & cloud sync broker",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }
                        }

                        IconButton(onClick = { showAmqpSection = !showAmqpSection }) {
                            Icon(
                                if (showAmqpSection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle AMQP settings",
                                tint = CyberCyanPrimary
                            )
                        }
                    }

                    if (showAmqpSection) {
                        Text(
                            text = "Broker connection parameters used during the Wi-Fi ON window to publish gathered telemetry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = amqpHostText,
                                onValueChange = { amqpHostText = it },
                                label = { Text("Broker Host / IP") },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Dns, contentDescription = null) },
                                modifier = Modifier.weight(2f).testTag("input_amqp_host")
                            )

                            OutlinedTextField(
                                value = amqpPortText,
                                onValueChange = { if (it.length <= 5) amqpPortText = it },
                                label = { Text("Port") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("input_amqp_port")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = amqpUserText,
                                onValueChange = { amqpUserText = it },
                                label = { Text("Username") },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.AccountCircle, contentDescription = null) },
                                modifier = Modifier.weight(1f).testTag("input_amqp_user")
                            )

                            OutlinedTextField(
                                value = amqpPassText,
                                onValueChange = { amqpPassText = it },
                                label = { Text("Password") },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Password, contentDescription = null) },
                                modifier = Modifier.weight(1f).testTag("input_amqp_pass")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = amqpExchangeText,
                                onValueChange = { amqpExchangeText = it },
                                label = { Text("Exchange Name") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("input_amqp_exchange")
                            )

                            OutlinedTextField(
                                value = amqpRoutingKeyText,
                                onValueChange = { amqpRoutingKeyText = it },
                                label = { Text("Routing Key") },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("input_amqp_routing_key")
                            )
                        }
                    }
                }
            }
        }

        // Hardware Relays & Protection Thresholds (ESP-01)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.ElectricBolt, contentDescription = null, tint = StatusWarning)
                            Column {
                                Text(
                                    text = "Hardware Relay & Protection Thresholds",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "ESP-01 Driver & cutoff limits",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted
                                )
                            }
                        }

                        IconButton(onClick = { showAdvancedHardware = !showAdvancedHardware }) {
                            Icon(
                                if (showAdvancedHardware) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle hardware settings",
                                tint = TechTealSecondary
                            )
                        }
                    }

                    if (showAdvancedHardware) {
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = ipDriverText,
                            onValueChange = { ipDriverText = it },
                            label = { Text("Relay Driver IP (ESP-01)") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Router, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = batteryMinText,
                                onValueChange = { if (it.length <= 3) batteryMinText = it },
                                label = { Text("Min Battery %") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = batteryMaxText,
                                onValueChange = { if (it.length <= 3) batteryMaxText = it },
                                label = { Text("Max Battery %") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedTextField(
                                value = tempMinText,
                                onValueChange = { if (it.length <= 3) tempMinText = it },
                                label = { Text("Min Temp °C") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = tempMaxText,
                                onValueChange = { if (it.length <= 3) tempMaxText = it },
                                label = { Text("Max Temp °C") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // Sensor Sampling Rates Configuration (GPS & IMU)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, TechTealSecondary.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Sensors, contentDescription = null, tint = TechTealSecondary)
                        Column {
                            Text(
                                text = "Sensor Sampling Rates",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TechTealSecondary
                            )
                            Text(
                                text = "Configure reading intervals for onboard sensors",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }

                    Text(
                        text = "• GPS / Location: Interval in seconds (minimum & default: 1s).\n" +
                               "• Inertial / IMU: Interval in milliseconds (default: 200ms, min: 20ms).",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = locationIntervalText,
                            onValueChange = { if (it.length <= 4) locationIntervalText = it },
                            label = { Text("GPS Rate (Seconds)") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null, tint = TechTealSecondary) },
                            modifier = Modifier.weight(1f).testTag("input_location_interval")
                        )

                        OutlinedTextField(
                            value = inertialIntervalText,
                            onValueChange = { if (it.length <= 5) inertialIntervalText = it },
                            label = { Text("IMU Rate (Milliseconds)") },
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Explore, contentDescription = null, tint = TechTealSecondary) },
                            modifier = Modifier.weight(1f).testTag("input_inertial_interval")
                        )
                    }
                }
            }
        }

        // Switching Summary
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                border = BorderStroke(1.dp, TechTealSecondary.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Calculated Switching Summary:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    // Operating Days
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = DarkSurface.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, DarkBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = TechTealSecondary, modifier = Modifier.size(18.dp))
                            Column {
                                Text("Operating Days:", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text(
                                    text = daysFormatted,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (selectedDays.isEmpty()) StatusError else TextPrimary
                                )
                            }
                        }
                    }

                    // Card 1: Wi-Fi ON / TCP OFF
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
                                Text("Wi-Fi ON (Sync & Cloud Upload)", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text(
                                    text = previewOffStr,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberCyanPrimary
                                )
                            }
                        }
                    }

                    // Card 2: TCP ON / Wi-Fi OFF
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
                                    Text("TCP Mesh ON (Hotspot & Telemetry)", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                    Text(
                                        text = previewTcpOnStr,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = TechTealSecondary
                                    )
                                }
                            }

                            // Network credentials badge
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
                                        text = "Pass: ${customPassText.ifEmpty { "MeshPassword123" }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = "Port: $portNumber",
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

        // Save & Continue Button
        item {
            Button(
                onClick = {
                    val finalDeviceId = if (deviceIdText.trim().isNotEmpty()) deviceIdText.trim() else "NODE-01"
                    val finalSsid = if (customSsidText.trim().isNotEmpty()) customSsidText.trim() else "Direct-Mesh-Master"
                    val finalPass = if (customPassText.trim().isNotEmpty()) customPassText.trim() else "MeshPassword123"
                    val finalIpDriver = if (ipDriverText.trim().isNotEmpty()) ipDriverText.trim() else "192.168.43.100"
                    val bMin = batteryMinText.toIntOrNull() ?: 20
                    val bMax = batteryMaxText.toIntOrNull() ?: 80
                    val tMin = tempMinText.toIntOrNull() ?: 15
                    val tMax = tempMaxText.toIntOrNull() ?: 45
                    val locInterval = (locationIntervalText.toIntOrNull() ?: 1).coerceAtLeast(1)
                    val imuInterval = (inertialIntervalText.toIntOrNull() ?: 200).coerceAtLeast(20)

                    val daysList = selectedDays.map { java.lang.Integer.valueOf(it) }

                    scheduleManager.updateFullConfiguration(
                        finalDeviceId,
                        daysList,
                        startHour,
                        startMinute,
                        endHour,
                        endMinute,
                        finalSsid,
                        finalPass,
                        portNumber,
                        finalIpDriver,
                        bMin,
                        bMax,
                        tMin,
                        tMax,
                        amqpHostText.trim(),
                        amqpPortNumber,
                        amqpVHostText.trim(),
                        amqpUserText.trim(),
                        amqpPassText,
                        amqpExchangeText.trim(),
                        amqpRoutingKeyText.trim(),
                        amqpQueueText.trim(),
                        amqpSslEnabled,
                        locInterval,
                        imuInterval
                    )

                    Toast.makeText(context, "Configuration saved successfully", Toast.LENGTH_SHORT).show()
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
                    text = if (!initialConfig.isConfigured) "Save & Start Operation" else "Save Changes",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }

        // Return to Dashboard (If already configured)
        if (initialConfig.isConfigured && onNavigateBack != null) {
            item {
                OutlinedButton(
                    onClick = onNavigateBack,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Back to Management Dashboard")
                }
            }
        }

        // Reset Initial Configuration (Danger / Testing Zone)
        if (initialConfig.isConfigured && onResetConfiguration != null) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { showResetDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, tint = StatusError, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Reset to Initial Setup (First Run)",
                        color = StatusError,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

/**
 * PÁGINA 2: Gestión de la Red TCP con acceso a modificar el horario y Telemetría en Vivo
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
    logs: List<NetworkLog>,
    telemetrySnapshot: com.example.model.telemetry.UnifiedTelemetrySnapshot
) {
    var messageText by remember { mutableStateOf("") }
    val localIp = remember { NetworkUtils.getLocalIpAddress() }

    var capturedSnapshot by remember { mutableStateOf<com.example.model.telemetry.UnifiedTelemetrySnapshot?>(null) }
    var snapshotTimestamp by remember { mutableStateOf<String?>(null) }

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
        // Active Schedule Banner
        item {
            val currentCalendarDay = remember { Calendar.getInstance().get(Calendar.DAY_OF_WEEK) }
            val isTodayActive = scheduleConfig.isDayActive(currentCalendarDay)
            val todayName = ScheduleConfig.getDayFullName(currentCalendarDay)

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, if (isTodayActive) TechTealSecondary.copy(alpha = 0.4f) else StatusError.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
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
                                "Schedule Status",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isTodayActive) StatusActive.copy(alpha = 0.15f) else StatusError.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, if (isTodayActive) StatusActive.copy(alpha = 0.4f) else StatusError.copy(alpha = 0.4f))
                        ) {
                            Text(
                                text = if (isTodayActive) "Today ($todayName): Active" else "Today ($todayName): Inactive",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isTodayActive) StatusActive else StatusError,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = TechTealSecondary, modifier = Modifier.size(14.dp))
                        Text(
                            text = "Days: ${scheduleConfig.activeDaysFormatted}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    Text(
                        text = "WiFi ON: ${scheduleConfig.wifiScheduleFormatted}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = CyberCyanPrimary
                    )
                    Text(
                        text = "TCP ON: ${scheduleConfig.hotspotScheduleFormatted}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TechTealSecondary
                    )
                }
            }
        }

        // On-Demand Base Telemetry Snapshot Card (GPS + IMU + Device)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, CyberCyanPrimary.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header with Title and Snapshot Button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Sensors,
                                contentDescription = null,
                                tint = CyberCyanPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    text = "Base Telemetry (On-Demand)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = if (snapshotTimestamp != null) "Snapshot captured at $snapshotTimestamp" else "On-demand sampling (0% idle drain)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (snapshotTimestamp != null) CyberCyanPrimary else TextMuted
                                )
                            }
                        }

                        Button(
                            onClick = {
                                val service = PersistentWifiTcpService.getInstance()
                                val snap = if (service != null && service.telemetryEngine != null) {
                                    service.telemetryEngine.sampleSnapshotNow()
                                } else {
                                    MeshStateManager.getInstance().latestTelemetrySnapshot
                                        ?: com.example.model.telemetry.UnifiedTelemetrySnapshot.empty(scheduleConfig.deviceId)
                                }
                                capturedSnapshot = snap
                                snapshotTimestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CyberCyanPrimary.copy(alpha = 0.2f),
                                contentColor = CyberCyanPrimary
                            ),
                            border = BorderStroke(1.dp, CyberCyanPrimary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("btn_capture_telemetry_snapshot")
                        ) {
                            Icon(
                                Icons.Default.CameraAlt,
                                contentDescription = "Capture Snapshot",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Snapshot", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }

                    val currentSnap = capturedSnapshot
                    if (currentSnap == null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkSurfaceVariant,
                            border = BorderStroke(1.dp, DarkBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Speed, contentDescription = null, tint = TextMuted, modifier = Modifier.size(28.dp))
                                Text(
                                    text = "Real-time updates disabled",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "To minimize CPU and battery usage, tap 'Snapshot' to inspect an instant sample of GPS, Inertial, and Hardware status.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    } else {
                        val loc = currentSnap.location
                        val imu = currentSnap.inertial
                        val dev = currentSnap.deviceStatus

                        // 1. GPS & Location Metrics Grid
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkSurfaceVariant,
                            border = BorderStroke(1.dp, DarkBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.LocationOn,
                                            contentDescription = null,
                                            tint = if (loc.hasFix()) StatusActive else StatusWarning,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            "GPS & Navigation",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (loc.hasFix()) StatusActive.copy(alpha = 0.15f) else StatusWarning.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            text = loc.fixStatusDescription,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (loc.hasFix()) StatusActive else StatusWarning,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Speed KPI
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Speed", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                        Text(
                                            text = String.format(Locale.US, "%.1f km/h", loc.speedKmh),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = CyberCyanPrimary
                                        )
                                    }

                                    // Coordinates
                                    Column(modifier = Modifier.weight(2f)) {
                                        Text("Coordinates", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                        Text(
                                            text = loc.coordinatesFormatted,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = TextPrimary
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = String.format(Locale.US, "Alt: %.1fm | Bearing: %.1f° | Acc: ±%.1fm", loc.altitude, loc.bearing, loc.accuracy),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                    Text(
                                        text = "Sats: ${loc.satellites}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TechTealSecondary
                                    )
                                }
                            }
                        }

                        // 2. Inertial & IMU Metrics Grid
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkSurfaceVariant,
                            border = BorderStroke(1.dp, DarkBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.Explore, contentDescription = null, tint = TechTealSecondary, modifier = Modifier.size(16.dp))
                                    Text("Inertial IMU (Filtered)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = DarkBackground,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(modifier = Modifier.padding(6.dp)) {
                                            Text("Accel (|G|)", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontSize = 10.sp)
                                            Text(
                                                text = String.format(Locale.US, "%.1f m/s²", imu.accelMagnitude),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = CyberCyanPrimary
                                            )
                                            Text(
                                                text = String.format(Locale.US, "X:%.1f Y:%.1f Z:%.1f", imu.accelX, imu.accelY, imu.accelZ),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                color = TextMuted
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = DarkBackground,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(modifier = Modifier.padding(6.dp)) {
                                            Text("Gyroscope", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontSize = 10.sp)
                                            Text(
                                                text = String.format(Locale.US, "%.2f rad/s", (Math.abs(imu.gyroX) + Math.abs(imu.gyroY) + Math.abs(imu.gyroZ))),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = TechTealSecondary
                                            )
                                            Text(
                                                text = String.format(Locale.US, "X:%.1f Y:%.1f Z:%.1f", imu.gyroX, imu.gyroY, imu.gyroZ),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                color = TextMuted
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = DarkBackground,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Column(modifier = Modifier.padding(6.dp)) {
                                            Text("Orientation", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontSize = 10.sp)
                                            Text(
                                                text = String.format(Locale.US, "P:%.0f° R:%.0f°", imu.pitch, imu.roll),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary
                                            )
                                            Text(
                                                text = String.format(Locale.US, "Yaw: %.0f°", imu.yaw),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                color = TextMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Device & Hardware Health Grid
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = DarkSurfaceVariant,
                            border = BorderStroke(1.dp, DarkBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Battery
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        if (dev.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                                        contentDescription = null,
                                        tint = if (dev.batteryLevelPercent > 20) StatusActive else StatusError,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "${dev.batteryLevelPercent}% (${dev.batteryTemperatureC}°C)",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary
                                        )
                                        Text(
                                            text = if (dev.isCharging) "Charging (${dev.chargeSource})" else "Discharging",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextMuted
                                        )
                                    }
                                }

                                // RAM & Storage
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = "RAM: ${dev.ramUsagePercent}% (${dev.freeRamMb}MB free)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Disk: ${String.format(Locale.US, "%.1f", dev.freeStorageGb)}GB free",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Wi-Fi Hotspot Radio Status Card
        item {
            val isHotspotRunning = hotspotInfo.isRunning
            val hotspotState = hotspotInfo.state
            val statusColor = when (hotspotState) {
                HotspotInfo.State.RUNNING -> StatusActive
                HotspotInfo.State.STARTING -> StatusWarning
                else -> StatusError
            }
            val statusLabel = when (hotspotState) {
                HotspotInfo.State.RUNNING -> "Broadcasting (Active)"
                HotspotInfo.State.STARTING -> "Starting SoftAP..."
                HotspotInfo.State.FAILED -> "Failed: ${hotspotInfo.errorMessage.ifEmpty { "Error" }}"
                HotspotInfo.State.DISABLED -> "Stopped / Timed Out"
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, if (isHotspotRunning) StatusActive.copy(alpha = 0.5f) else StatusError.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.WifiTethering, contentDescription = null, tint = statusColor, modifier = Modifier.size(20.dp))
                            Column {
                                Text("Wi-Fi Hotspot Radio (SoftAP)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextPrimary)
                                Text("Physical Wi-Fi Access Point", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = statusColor.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.5f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Text(
                                    text = statusLabel,
                                    color = statusColor,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    if (!isHotspotRunning) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = StatusError.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, StatusError.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = StatusError, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "Wi-Fi radio is not broadcasting. Other smartphones/clients will NOT see this network until the Hotspot is started.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusError
                                )
                            }
                        }
                    }

                    // Button to restart Hotspot
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(context, PersistentWifiTcpService::class.java).apply {
                                    action = PersistentWifiTcpService.ACTION_CREATE_HOTSPOT
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(intent)
                                } else {
                                    context.startService(intent)
                                }
                                Toast.makeText(context, "Restarting Wi-Fi Hotspot...", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isHotspotRunning) DarkSurfaceVariant else CyberCyanPrimary,
                            contentColor = if (isHotspotRunning) TextPrimary else CyberCyanOnPrimary
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("restart_hotspot_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isHotspotRunning) "Reboot Hotspot Radio" else "Start Wi-Fi Hotspot Now", fontSize = 13.sp)
                    }
                }
            }
        }

        // TCP Server Status & Fixed Local Network Credentials
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // TCP Server Status with LED indicator
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("TCP Socket Server", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text("Socket listening on port $serverPort", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isServerRunning) StatusActive.copy(alpha = 0.15f) else StatusError.copy(alpha = 0.15f),
                            border = BorderStroke(1.dp, if (isServerRunning) StatusActive.copy(alpha = 0.5f) else StatusError.copy(alpha = 0.5f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isServerRunning) StatusActive else StatusError)
                                )
                                Text(
                                    text = if (isServerRunning) "Active" else "Inactive",
                                    color = if (isServerRunning) StatusActive else StatusError,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    // Connection Details Panel
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
                                Icon(Icons.Default.Sensors, contentDescription = null, tint = CyberCyanPrimary, modifier = Modifier.size(18.dp))
                                Text(
                                    text = "Client Connection Details",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = CyberCyanPrimary
                                )
                            }

                            HorizontalDivider(color = DarkBorder, thickness = 0.8.dp)

                            // Network SSID
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Wifi, contentDescription = null, tint = CyberCyanPrimary, modifier = Modifier.size(16.dp))
                                    Text("SSID:", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                                Text(
                                    text = if (hotspotInfo.isRunning) networkSsid else "$networkSsid (Radio Off)",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hotspotInfo.isRunning) CyberCyanPrimary else StatusError,
                                    modifier = Modifier.testTag("tcp_network_ssid_value")
                                )
                            }

                            // Passphrase
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = TechTealSecondary, modifier = Modifier.size(16.dp))
                                    Text("Password:", style = MaterialTheme.typography.bodySmall, color = TextMuted)
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

                            // TCP Port
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Sensors, contentDescription = null, tint = StatusActive, modifier = Modifier.size(16.dp))
                                    Text("TCP Port:", style = MaterialTheme.typography.bodySmall, color = TextMuted)
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

                            // Server IP
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Default.Router, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                                    Text("Server IP:", style = MaterialTheme.typography.bodySmall, color = TextMuted)
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

                            // Copy Credentials Button
                            OutlinedButton(
                                onClick = {
                                    val clipData = "SSID: $networkSsid\nPassword: $networkPass\nIP: $displayIp\nTCP Port: $serverPort"
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    clipboard?.setPrimaryClip(ClipData.newPlainText("TCP Mesh Credentials", clipData))
                                    Toast.makeText(context, "Credentials copied to clipboard", Toast.LENGTH_SHORT).show()
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
                                Text("Copy Credentials", fontSize = 13.sp)
                            }
                        }
                    }

                    // Traffic Metrics
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
                                Text("Sent", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text("$packetsSent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = CyberCyanPrimary)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Received", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text("$packetsReceived", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TechTealSecondary)
                            }
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = DarkSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Nodes", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                                Text("${connectedClients.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = StatusActive)
                            }
                        }
                    }
                }
            }
        }

        // Send TCP Broadcast Message
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Broadcast TCP Message",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )

                    OutlinedTextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        placeholder = { Text("Enter message to broadcast...") },
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
                                    Toast.makeText(context, "Message sent to mesh", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Service error: ${e.message}", Toast.LENGTH_SHORT).show()
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
                        Text("Broadcast Message")
                    }
                }
            }
        }

        // Connected Nodes
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Connected Nodes (${connectedClients.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (connectedClients.isEmpty()) {
                        Text(
                            text = "Waiting for client nodes to connect...",
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
                                    Text("Port ${client.port}", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Local Persistence Buffer (Room SQLite) Card
        item {
            LocalPersistenceBufferCard(context = context)
        }

        // Cloud & Messaging Layer (RabbitMQ AMQP) Card
        item {
            AmqpCloudCard(context = context)
        }

        // System OS & Keep-Alive Settings Card
        item {
            KeepAliveSettingsCard(context = context)
        }

        // Traffic and Message Logs
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = BorderStroke(1.dp, DarkBorder),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Received Messages Log",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val tcpLogs = logs.filter { it.tag.contains("TCP", ignoreCase = true) || it.tag.contains("Mesh", ignoreCase = true) }.takeLast(15)
                    if (tcpLogs.isEmpty()) {
                        Text(
                            text = "No TCP packets received yet.",
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
        AppLogger.getInstance().e("MainActivity", "Error starting background service: " + e.message)
    }
}

@Composable
fun KeepAliveSettingsCard(context: Context) {
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    val isIgnoringBattery = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        } else true
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, DarkBorder),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = StatusActive)
                Text(
                    text = "OS Battery Exemption & Keep-Alive",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            Text(
                text = "Android OS actively restricts apps flagged for high background battery usage or idle hotspot timeouts. Setting the app to 'Unrestricted' prevents Android from killing the mesh service or turning off the Wi-Fi radio.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )

            // Status Badges Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isIgnoringBattery) StatusActive.copy(alpha = 0.12f) else StatusWarning.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, if (isIgnoringBattery) StatusActive.copy(alpha = 0.4f) else StatusWarning.copy(alpha = 0.4f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Battery Policy", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Text(
                            text = if (isIgnoringBattery) "Unrestricted (Exempt)" else "Optimized / Restricted",
                            fontWeight = FontWeight.Bold,
                            color = if (isIgnoringBattery) StatusActive else StatusWarning,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = StatusActive.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, StatusActive.copy(alpha = 0.4f)),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Watchdog Protection", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                        Text(
                            text = "Auto-Revive & Lock ON",
                            fontWeight = FontWeight.Bold,
                            color = StatusActive,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // 1. Button to set App Battery Usage to "Unrestricted" (Android 12/13/14+)
            OutlinedButton(
                onClick = {
                    var opened = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                            opened = true
                        } catch (_: Exception) {}
                    }
                    if (!opened) {
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open app settings: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("exempt_battery_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCyanPrimary)
            ) {
                Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Exempt from Battery Saver / High Usage Kill", fontSize = 13.sp)
            }

            // 2. Button to open App Info / Battery Details directly
            OutlinedButton(
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not open App Info", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("open_app_info_battery"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) {
                Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("App Info -> Battery -> Select 'Unrestricted'", fontSize = 13.sp)
            }

            // 3. Button to open Hotspot Tethering settings
            OutlinedButton(
                onClick = {
                    try {
                        val tetherIntent = Intent().apply {
                            setClassName("com.android.settings", "com.android.settings.TetherSettings")
                        }
                        context.startActivity(tetherIntent)
                    } catch (e1: Exception) {
                        try {
                            val wirelessIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                            context.startActivity(wirelessIntent)
                        } catch (e2: Exception) {
                            val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS)
                            context.startActivity(wifiIntent)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("open_hotspot_settings_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TechTealSecondary)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open Hotspot Timeout Settings", fontSize = 13.sp)
            }
        }
    }
}


