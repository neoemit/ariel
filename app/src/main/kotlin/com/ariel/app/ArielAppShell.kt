package com.thomaslamendola.ariel

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel

data class PermissionGroup(
    val labelRes: Int,
    val permissions: List<String>,
)

private fun requiredPermissionGroups(): List<PermissionGroup> {
    val groups = mutableListOf<PermissionGroup>()

    groups += PermissionGroup(
        labelRes = R.string.permission_group_nearby_bluetooth,
        permissions = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        groups += PermissionGroup(
            labelRes = R.string.permission_group_nearby_wifi,
            permissions = listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        )
        groups += PermissionGroup(
            labelRes = R.string.permission_group_notifications,
            permissions = listOf(Manifest.permission.POST_NOTIFICATIONS)
        )
    }

    groups += PermissionGroup(
        labelRes = R.string.permission_group_camera,
        permissions = listOf(Manifest.permission.CAMERA)
    )

    return groups
}

private fun findMissingPermissions(context: Context, permissions: List<String>): List<String> {
    return permissions.filter { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun ArielApp(viewModel: PanicViewModel = viewModel()) {
    var selectedTab by remember { mutableStateOf(0) } // 0=Panic,1=Pairing,2=Settings
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    val permissionGroups = remember { requiredPermissionGroups() }
    val requiredPermissions = remember(permissionGroups) { permissionGroups.flatMap { it.permissions } }
    var missingPermissions by remember {
        mutableStateOf(findMissingPermissions(context, requiredPermissions))
    }

    fun refreshPermissions() {
        missingPermissions = findMissingPermissions(context, requiredPermissions)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        refreshPermissions()
    }

    val requestPermissions: () -> Unit = {
        if (requiredPermissions.isNotEmpty()) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    val openAppSettings: () -> Unit = {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        viewModel.setUiActive(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        )

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    viewModel.setUiActive(true)
                    refreshPermissions()
                }
                Lifecycle.Event.ON_STOP -> viewModel.setUiActive(false)
                else -> Unit
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.setUiActive(false)
        }
    }

    LaunchedEffect(Unit) {
        refreshPermissions()
        if (missingPermissions.isNotEmpty()) {
            requestPermissions()
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }

    LaunchedEffect(missingPermissions) {
        if (missingPermissions.isEmpty()) {
            viewModel.startPairing()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    Scaffold(
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
                    icon = { Icon(Icons.Default.Wifi, contentDescription = context.getString(R.string.nav_pairing)) },
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
                0 -> PanicScreen(
                    viewModel = viewModel,
                    missingPermissions = missingPermissions,
                    onRequestPermissions = requestPermissions,
                    onOpenAppSettings = openAppSettings
                )
                1 -> PairingScreen(
                    viewModel = viewModel,
                    missingPermissions = missingPermissions,
                    onRequestPermissions = requestPermissions,
                    onOpenAppSettings = openAppSettings
                )
                2 -> SettingsScreen(
                    viewModel = viewModel,
                    permissionGroups = permissionGroups,
                    missingPermissions = missingPermissions,
                    onRequestPermissions = requestPermissions,
                    onOpenAppSettings = openAppSettings
                )
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

private fun permissionLabel(context: Context, permission: String): String {
    return when (permission) {
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT -> context.getString(R.string.permission_group_nearby_bluetooth)
        Manifest.permission.NEARBY_WIFI_DEVICES -> context.getString(R.string.permission_group_nearby_wifi)
        Manifest.permission.POST_NOTIFICATIONS -> context.getString(R.string.permission_group_notifications)
        Manifest.permission.CAMERA -> context.getString(R.string.permission_group_camera)
        else -> permission
    }
}

@Composable
fun PermissionBlockedScreen(
    title: String,
    description: String,
    missingPermissions: List<String>,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val missingLabels = remember(missingPermissions) {
        missingPermissions.map { permissionLabel(context, it) }.distinct()
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        ElevatedCard(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = context.getString(R.string.permissions_missing_count, missingLabels.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                )
                missingLabels.forEach { label ->
                    Text(
                        text = "• $label",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(context.getString(R.string.permissions_action_grant))
                }
                OutlinedButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(context.getString(R.string.permissions_action_open_settings))
                }
            }
        }
    }
}

@Composable
fun PermissionStatusCard(
    permissionGroups: List<PermissionGroup>,
    missingPermissions: List<String>,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val missingSet = remember(missingPermissions) { missingPermissions.toSet() }
    val allGranted = missingPermissions.isEmpty()

    ElevatedCard(shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = context.getString(R.string.permissions_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Text(
                text = context.getString(R.string.permissions_section_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            permissionGroups.forEach { group ->
                val granted = group.permissions.all { permission -> permission !in missingSet }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = context.getString(group.labelRes),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (granted) {
                            context.getString(R.string.permissions_status_granted)
                        } else {
                            context.getString(R.string.permissions_status_not_granted)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (granted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val canUseFullScreenIntent = notificationManager.canUseFullScreenIntent()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = context.getString(R.string.permission_group_full_screen_intent),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = if (canUseFullScreenIntent) {
                            context.getString(R.string.permissions_status_granted)
                        } else {
                            context.getString(R.string.permissions_status_not_granted)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (canUseFullScreenIntent) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                if (!canUseFullScreenIntent) {
                    Text(
                        text = context.getString(R.string.permission_full_screen_intent_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(context.getString(R.string.permission_full_screen_intent_action))
                    }
                }
            }

            if (!allGranted) {
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(context.getString(R.string.permissions_action_grant))
                }
                OutlinedButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(context.getString(R.string.permissions_action_open_settings))
                }
            }
        }
    }
}
