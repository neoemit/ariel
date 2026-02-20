package com.ariel.app

import android.Manifest
import android.graphics.Bitmap
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import android.content.Context
import android.content.Intent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ArielApp()
                }
            }
        }
    }
}

@Composable
fun ArielApp(viewModel: PanicViewModel = viewModel()) {
    var showPairing by remember { mutableStateOf(false) }
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

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = !showPairing,
                    onClick = { showPairing = false },
                    icon = { Icon(Icons.Default.Notifications, contentDescription = "Panic") },
                    label = { Text("Panic") }
                )
                NavigationBarItem(
                    selected = showPairing,
                    onClick = { showPairing = true },
                    icon = { Icon(Icons.Default.Share, contentDescription = "Pairing") },
                    label = { Text("Pairing") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (showPairing) {
                PairingScreen(viewModel)
            } else {
                PanicScreen(viewModel)
            }
            
            val ack by viewModel.lastAcknowledgment.collectAsState()
            ack?.let { name ->
                Snackbar(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    Text("$name has acknowledged your alert!")
                }
            }
        }
    }
}

@Composable
fun PanicScreen(viewModel: PanicViewModel) {
    val progress by viewModel.panicTriggerProgress.collectAsState()
    val isTriggered by viewModel.isPanicTriggered.collectAsState()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current

    LaunchedEffect(isPressed) {
        viewModel.setPressed(isPressed)
        if (isPressed) {
            val startTime = System.currentTimeMillis()
            var lastVibrationProgress = 0f
            while (isPressed && !isTriggered) {
                val elapsed = System.currentTimeMillis() - startTime
                val currentProgress = elapsed / 3000f
                viewModel.setTriggerProgress(currentProgress)
                
                // Haptic feedback every 10%
                if (currentProgress - lastVibrationProgress >= 0.1f) {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    lastVibrationProgress = currentProgress
                }
                
                delay(16)
                if (elapsed >= 3000) break
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
                Text(
                    text = if (peerCount > 0) "$peerCount Buddies Online" else "No Buddies Online",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
            }
        }

        if (isTriggered) {
            // Pulsing Animation or Static Alert
            Text("ALERT ACTIVE", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.Red)
            Text("Your buddies have been notified", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
            
            Spacer(modifier = Modifier.height(64.dp))
            
            Button(
                onClick = { viewModel.resetPanic() },
                modifier = Modifier.height(56.dp).fillMaxWidth(0.6f),
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Text("RESET PANIC", fontWeight = FontWeight.Bold)
            }
        } else {
            Box(contentAlignment = Alignment.Center) {
                // Outer Progress Ring
                Canvas(modifier = Modifier.size(300.dp)) {
                    drawCircle(color = Color(0xFF1A1A1A), style = Stroke(width = 16.dp.toPx()))
                    drawArc(
                        color = Color.Red,
                        startAngle = -90f,
                        sweepAngle = progress * 360f,
                        useCenter = false,
                        style = Stroke(width = 16.dp.toPx())
                    )
                }

                // The Big Red Button
                Surface(
                    onClick = {},
                    interactionSource = interactionSource,
                    modifier = Modifier.size(240.dp),
                    shape = CircleShape,
                    color = Color.Red,
                    tonalElevation = 8.dp,
                    shadowElevation = 12.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "PANIC",
                            fontSize = 44.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(48.dp))
            Text(
                "HOLD BUTTON TO TRIGGER",
                style = MaterialTheme.typography.labelLarge,
                color = if (isPressed) Color.White else Color.Gray,
                letterSpacing = 2.sp
            )
        }
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
                Text("CANCEL")
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Your ID: $myName", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(16.dp))

        qrBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "My QR Code",
                modifier = Modifier.size(200.dp).background(Color.White)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        // --- Status Indicator ---
        val status by viewModel.status.collectAsState()
        status?.let { msg ->
            Surface(
                color = Color(0xFF333333),
                shape = CircleShape,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Text(
                    text = msg,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF64B5F6) // Light blue for status
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("Scan a buddy's QR code or ask them to scan yours", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = { isScanning = true }, modifier = Modifier.fillMaxWidth()) {
            Text("SCAN BUDDY'S QR CODE")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { viewModel.clearPool() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("RESET ALL CONNECTIONS")
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (friends.isNotEmpty()) {
            Text("YOUR PANIC POOL", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            friends.forEach { friend ->
                val nickname = nicknames[friend]
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
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
                                    color = Color.Gray
                                )
                            }
                        }
                        IconButton(onClick = {
                            editingFriend = friend
                            nicknameText = nickname ?: ""
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit Nickname", tint = Color.Gray)
                        }
                        IconButton(onClick = { viewModel.removeFriend(friend) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Friend", tint = Color.Gray)
                        }
                    }
                }
            }
        } else {
            Text("No friends paired yet.", color = Color.Gray)
        }
    }

    if (editingFriend != null) {
        AlertDialog(
            onDismissRequest = { editingFriend = null },
            title = { Text("Set Nickname") },
            text = {
                Column {
                    Text("ID: ${editingFriend}")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nicknameText,
                        onValueChange = { nicknameText = it },
                        label = { Text("Nickname") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    editingFriend?.let { viewModel.setNickname(it, nicknameText) }
                    editingFriend = null
                }) {
                    Text("SAVE")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingFriend = null }) {
                    Text("CANCEL")
                }
            }
        )
    }
}
