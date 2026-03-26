package com.ariel.app

import android.Manifest
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.media.RingtoneManager
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.content.Context
import android.app.Activity
import android.content.Intent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import androidx.core.view.WindowCompat
import com.ariel.app.ui.theme.ArielTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            ArielTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ArielApp()
                }
            }
        }
    }
}

@Composable
fun ArielApp(viewModel: PanicViewModel = viewModel()) {
    // always start on the Panic tab; not saved across restarts or recompositions
    var selectedTab by remember { mutableStateOf(0) } // 0=Panic,1=Pairing,2=Settings
    val context = LocalContext.current
    
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.startPairing()
        } else {
            Toast.makeText(context, "Permissions required for pairing", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS
        ))

        // Check if Location is enabled
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        val gpsEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
        if (!gpsEnabled && !networkEnabled) {
            Toast.makeText(context, "PLEASE ENABLE LOCATION SERVICES (GPS) in system settings!", Toast.LENGTH_LONG).show()
        }

        // Request battery optimization ignore for "Never Sleep" functionality
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }

    // bottom navigation with three tabs
    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = context.getString(R.string.nav_panic)) },
                    label = { Text(context.getString(R.string.nav_panic)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Phone, contentDescription = context.getString(R.string.nav_pairing)) },
                    label = { Text(context.getString(R.string.nav_pairing)) }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = context.getString(R.string.nav_settings)) },
                    label = { Text(context.getString(R.string.nav_settings)) }
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> PanicScreen(viewModel)
                1 -> PairingScreen(viewModel)
                2 -> SettingsScreen(viewModel)
            }

            val ack by viewModel.lastAcknowledgment.collectAsState()
            LaunchedEffect(ack) {
                ack?.let { name ->
                    snackbarHostState.showSnackbar("$name has acknowledged your alert!")
                }
            }
        }
    }
}

@Composable
fun PanicScreen(viewModel: PanicViewModel) {
    val ctx = LocalContext.current
    val progress by viewModel.panicTriggerProgress.collectAsState()
    val isTriggered by viewModel.isPanicTriggered.collectAsState()
    var isPressed by remember { mutableStateOf(false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val selectedEscalation by viewModel.selectedEscalation.collectAsState()

    LaunchedEffect(isPressed) {
        viewModel.setPressed(isPressed)
        if (isPressed) {
            val startTime = System.currentTimeMillis()
            var lastVibrationProgress = 0f
            while (isPressed && !isTriggered) {
                val elapsed = System.currentTimeMillis() - startTime
                val currentProgress = elapsed.toFloat() / PanicViewModel.PANIC_HOLD_DURATION_MS.toFloat()
                viewModel.setTriggerProgress(currentProgress)
                
                // Haptic feedback every 10%
                if (currentProgress - lastVibrationProgress >= 0.1f) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    lastVibrationProgress = currentProgress
                }
                
                delay(16)
                if (elapsed >= PanicViewModel.PANIC_HOLD_DURATION_MS) break
            }
        }
    }

    val peerCount by viewModel.peerCount.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Status Bar at the top
        Surface(
            shape = CircleShape,
            color = if (peerCount > 0) Color(0xFF1B5E20) else Color(0xFF333333),
            modifier = Modifier.padding(bottom = 48.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(8.dp).background(if (peerCount > 0) Color.Green else Color.Gray, CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                // singular/plural handling
                val onlineText = when (peerCount) {
                    0 -> ctx.getString(R.string.status_none_online)
                    1 -> ctx.getString(R.string.status_one_online)
                    else -> ctx.getString(R.string.status_many_online, peerCount)
                }
                Text(
                    text = onlineText,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
            }
        }

        if (isTriggered) {
            // Pulsing Animation or Static Alert
            Text(LocalContext.current.getString(R.string.alert_active), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            Text(LocalContext.current.getString(R.string.alert_notified), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(12.dp))

            val statusRes = when (selectedEscalation) {
                PanicViewModel.ESCALATION_MEDICAL -> R.string.escalation_selected_medical
                PanicViewModel.ESCALATION_ARMED -> R.string.escalation_selected_armed
                else -> R.string.escalation_selected_generic
            }
            Text(
                text = LocalContext.current.getString(statusRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(36.dp))
            
            Button(
                onClick = { viewModel.resetPanic() },
                modifier = Modifier.height(56.dp).fillMaxWidth(0.6f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(LocalContext.current.getString(R.string.reset_panic), fontWeight = FontWeight.Bold)
            }
        } else {
            val ringBg = MaterialTheme.colorScheme.surfaceVariant
            val ringColor = MaterialTheme.colorScheme.error
            Box(contentAlignment = Alignment.Center) {
                // Outer Progress Ring
                Canvas(modifier = Modifier.size(300.dp)) {
                    drawCircle(color = ringBg, style = Stroke(width = 16.dp.toPx()))
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = progress * 360f,
                        useCenter = false,
                        style = Stroke(width = 16.dp.toPx())
                    )
                }

                SegmentedPanicButton(
                    selectedEscalation = selectedEscalation,
                    modifier = Modifier
                        .size(240.dp)
                        .semantics { contentDescription = ctx.getString(R.string.a11y_hold_panic) },
                    onEscalationSelected = { escalationType ->
                        viewModel.setEscalationMode(escalationType)
                    },
                    onPressedChange = { pressed ->
                        isPressed = pressed
                    }
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun SegmentedPanicButton(
    selectedEscalation: String,
    modifier: Modifier = Modifier,
    onEscalationSelected: (String) -> Unit,
    onPressedChange: (Boolean) -> Unit
) {
    var buttonSize by remember { mutableStateOf(IntSize.Zero) }
    var trackingTouch by remember { mutableStateOf(false) }

    val selectedColor = MaterialTheme.colorScheme.error
    val unselectedColor = MaterialTheme.colorScheme.error.copy(alpha = 0.72f)
    val dividerColor = MaterialTheme.colorScheme.onError.copy(alpha = 0.55f)
    val iconColor = MaterialTheme.colorScheme.onError

    Box(
        modifier = modifier
            .onSizeChanged { buttonSize = it }
            .pointerInteropFilter { event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val selected = resolveEscalationForTouch(event.x, event.y, buttonSize)
                        if (selected != null) {
                            onEscalationSelected(selected)
                            trackingTouch = true
                            onPressedChange(true)
                            true
                        } else {
                            false
                        }
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (!trackingTouch) return@pointerInteropFilter false
                        val selected = resolveEscalationForTouch(event.x, event.y, buttonSize)
                        if (selected != null) {
                            onEscalationSelected(selected)
                            onPressedChange(true)
                        } else {
                            onPressedChange(false)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!trackingTouch) return@pointerInteropFilter false
                        trackingTouch = false
                        onPressedChange(false)
                        true
                    }

                    else -> trackingTouch
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val radius = size.minDimension / 2f
            val center = center
            val stroke = 2.dp.toPx()

            drawArc(
                color = if (selectedEscalation == PanicViewModel.ESCALATION_GENERIC) selectedColor else unselectedColor,
                startAngle = 30f,
                sweepAngle = 120f,
                useCenter = true,
                style = Fill
            )
            drawArc(
                color = if (selectedEscalation == PanicViewModel.ESCALATION_MEDICAL) selectedColor else unselectedColor,
                startAngle = 150f,
                sweepAngle = 120f,
                useCenter = true,
                style = Fill
            )
            drawArc(
                color = if (selectedEscalation == PanicViewModel.ESCALATION_ARMED) selectedColor else unselectedColor,
                startAngle = 270f,
                sweepAngle = 120f,
                useCenter = true,
                style = Fill
            )

            drawCircle(color = dividerColor, radius = radius, style = Stroke(width = stroke))

            listOf(30f, 150f, 270f).forEach { angle ->
                val radians = Math.toRadians(angle.toDouble())
                val endX = center.x + cos(radians).toFloat() * radius
                val endY = center.y + sin(radians).toFloat() * radius
                drawLine(
                    color = dividerColor,
                    start = center,
                    end = androidx.compose.ui.geometry.Offset(endX, endY),
                    strokeWidth = stroke
                )
            }
        }

        SegmentIcon(angleDegrees = 210f, distance = 72.dp) {
            Icon(
                imageVector = Icons.Default.LocalHospital,
                contentDescription = LocalContext.current.getString(R.string.escalation_medical_desc),
                tint = iconColor
            )
        }
        SegmentIcon(angleDegrees = 90f, distance = 72.dp) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = LocalContext.current.getString(R.string.escalation_generic_desc),
                tint = iconColor
            )
        }
        SegmentIcon(angleDegrees = 330f, distance = 72.dp) {
            Icon(
                painter = painterResource(R.drawable.ic_gun),
                contentDescription = LocalContext.current.getString(R.string.escalation_armed_desc),
                tint = iconColor
            )
        }
    }
}

@Composable
private fun BoxScope.SegmentIcon(
    angleDegrees: Float,
    distance: Dp,
    icon: @Composable () -> Unit
) {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val xOffset = (cos(radians) * distance.value).toFloat().dp
    val yOffset = (sin(radians) * distance.value).toFloat().dp
    Box(
        modifier = Modifier
            .align(Alignment.Center)
            .offset(x = xOffset, y = yOffset)
    ) {
        icon()
    }
}

private fun resolveEscalationForTouch(x: Float, y: Float, size: IntSize): String? {
    if (size.width <= 0 || size.height <= 0) return null

    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val dx = x - centerX
    val dy = y - centerY
    val radius = minOf(size.width, size.height) / 2f

    if (dx * dx + dy * dy > radius * radius) return null

    val angle = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0).toFloat()
    return when {
        angle >= 30f && angle < 150f -> PanicViewModel.ESCALATION_GENERIC
        angle >= 150f && angle < 270f -> PanicViewModel.ESCALATION_MEDICAL
        else -> PanicViewModel.ESCALATION_ARMED
    }
}

@Composable
fun PairingScreen(viewModel: PanicViewModel) {
    val friends by viewModel.friends.collectAsState()
    val nicknames by viewModel.nicknames.collectAsState()
    
    var isScanning by remember { mutableStateOf(false) }
    var editingFriend by remember { mutableStateOf<String?>(null) }
    var nicknameText by remember { mutableStateOf("") }

    val myName = viewModel.myName
    val qrBitmap = remember(myName) { QRUtils.generateQRCode(myName) }

    if (isScanning) {
        Box(modifier = Modifier.fillMaxSize()) {
            QRScannerView(onCodeScanned = { name ->
                viewModel.addFriend(name)
                isScanning = false
            })
            Button(
                onClick = { isScanning = false },
                modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp)
            ) {
                Text(LocalContext.current.getString(R.string.cancel))
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(LocalContext.current.getString(R.string.your_id, myName), style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        qrBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = LocalContext.current.getString(R.string.a11y_my_qr),
                modifier = Modifier.size(260.dp).background(Color.White)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = { isScanning = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text(LocalContext.current.getString(R.string.scan_qr_button), color = MaterialTheme.colorScheme.onPrimary)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(LocalContext.current.getString(R.string.your_panic_pool), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        if (friends.isNotEmpty()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(friends) { friend ->
                    val nickname = nicknames[friend]
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = nickname ?: friend,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (nickname != null) {
                                    Text(
                                        text = friend,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(onClick = {
                                editingFriend = friend
                                nicknameText = nickname ?: ""
                            }) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = LocalContext.current.getString(R.string.edit_nickname),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { viewModel.removeFriend(friend) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = LocalContext.current.getString(R.string.delete_friend),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        } else {
            Text(
                LocalContext.current.getString(R.string.no_friends),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (editingFriend != null) {
        AlertDialog(
            onDismissRequest = { editingFriend = null },
            title = { Text(LocalContext.current.getString(R.string.set_nickname)) },
            text = {
                Column {
                    Text(LocalContext.current.getString(R.string.id_label, editingFriend))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nicknameText,
                        onValueChange = { nicknameText = it },
                        label = { Text(LocalContext.current.getString(R.string.nickname)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingFriend?.let {
                        viewModel.setNickname(it, nicknameText)
                    }
                    editingFriend = null
                }) {
                    Text(LocalContext.current.getString(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingFriend = null }) {
                    Text(LocalContext.current.getString(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun SettingsScreen(viewModel: PanicViewModel) {
    val context = LocalContext.current
    val ringtoneUri by viewModel.panicRingtoneUri.collectAsState()
    val relayBackendUrl by viewModel.relayBackendUrl.collectAsState()
    var ringtoneName by remember { mutableStateOf("Default") }
    var relayBackendUrlInput by remember(relayBackendUrl) { mutableStateOf(relayBackendUrl) }
    val versionDisplay = remember {
        runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} ($code)"
        }.getOrDefault("Unknown")
    }

    LaunchedEffect(ringtoneUri) {
        ringtoneName = if (ringtoneUri == null) {
            "Default"
        } else {
            RingtoneManager.getRingtone(context, Uri.parse(ringtoneUri))
                ?.getTitle(context)
                ?: "Unknown"
        }
    }

    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            viewModel.setPanicRingtone(uri?.toString())
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = LocalContext.current.getString(R.string.about_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = LocalContext.current.getString(R.string.relay_url_title),
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = relayBackendUrlInput,
                onValueChange = { relayBackendUrlInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(LocalContext.current.getString(R.string.relay_url_label)) },
                placeholder = { Text(LocalContext.current.getString(R.string.relay_url_placeholder)) },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = LocalContext.current.getString(R.string.relay_url_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { viewModel.setRelayBackendUrl(relayBackendUrlInput) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(LocalContext.current.getString(R.string.save_relay_url))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { viewModel.clearPool() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(LocalContext.current.getString(R.string.reset_all))
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select panic alert sound")
                        val existing = ringtoneUri?.let { Uri.parse(it) }
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing)
                    }
                    ringtoneLauncher.launch(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(LocalContext.current.getString(R.string.alert_sound, ringtoneName))
            }
        }

        Text(
            text = LocalContext.current.getString(R.string.version_display, versionDisplay),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
