package com.emergencymesh.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.emergencymesh.EmergencyMeshApp
import com.emergencymesh.mesh.MeshManager
import com.emergencymesh.mesh.MeshService
import com.emergencymesh.mesh.ConnectionState
import com.emergencymesh.sos.SosBeaconService
import com.emergencymesh.ui.theme.EmergencyMeshTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var meshManager: MeshManager
    private var isSosActive by mutableStateOf(false)
    private var sosActiveBeforeRotation = false

    // Network state receiver
    private val networkReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Network state changed")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            Log.d(TAG, "All permissions granted")
            startMeshServices()
        } else {
            Log.w(TAG, "Some permissions denied: ${permissions.filter { !it.value }.keys}")
            showPermissionDeniedMessage()
        }
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        Log.d(TAG, "Battery optimization request completed")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Restore SOS state if activity was recreated
        sosActiveBeforeRotation = savedInstanceState?.getBoolean(KEY_SOS_ACTIVE) ?: false

        meshManager = (application as EmergencyMeshApp).meshManager

        setContent {
            EmergencyMeshTheme {
                val connectionState by meshManager.connectionState.collectAsState()
                val nearbyDevices by meshManager.nearbyDevices.collectAsState()

                EmergencyMeshApp(
                    connectionState = connectionState,
                    nearbyDeviceCount = nearbyDevices.size,
                    sosActive = isSosActive,
                    onSosPressed = { toggleSOS() },
                    onScanQrPressed = { scanQrCode() },
                    onShowQrPressed = { showQrCode() }
                )
            }
        }

        // Register network receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerReceiver(
                networkReceiver,
                IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        // Restore SOS if it was active before rotation
        if (sosActiveBeforeRotation && !isSosActive) {
            toggleSOS()
        }

        checkPermissions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_SOS_ACTIVE, isSosActive)
    }

    private fun checkPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }

        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            Log.d(TAG, "All permissions already granted")
            startMeshServices()
        } else {
            Log.d(TAG, "Requesting permissions: ${permissionsToRequest.joinToString()}")
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun showPermissionDeniedMessage() {
        // Show UI explaining why permissions are needed
        lifecycleScope.launch {
            // Could show a dialog or snackbar
            android.widget.Toast.makeText(
                this@MainActivity,
                "Permissions required for mesh networking. Please grant in Settings.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startMeshServices() {
        lifecycleScope.launch {
            try {
                // Start mesh network service
                val meshServiceIntent = Intent(this@MainActivity, MeshService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(meshServiceIntent)
                } else {
                    startService(meshServiceIntent)
                }

                // Enable hotspot
                meshManager.enableHotspot(true)
                meshManager.startDeviceDiscovery()

                // Request battery optimization exemption
                requestBatteryOptimizationExemption()

                Log.d(TAG, "Mesh services started successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mesh services", e)
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Failed to start mesh network. Check permissions.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val packageName = packageName
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = android.content.Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = android.net.Uri.parse("package:$packageName")
                }

                // Check if activity can handle the intent
                if (intent.resolveActivity(packageManager) != null) {
                    batteryOptimizationLauncher.launch(intent)
                    Log.d(TAG, "Battery optimization exemption requested")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request battery optimization exemption", e)
        }
    }

    private fun toggleSOS() {
        try {
            isSosActive = !isSosActive

            if (isSosActive) {
                SosBeaconService.startService(this)
                android.widget.Toast.makeText(
                    this,
                    getString(com.emergencymesh.R.string.sos_sent),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                Log.d(TAG, "SOS activated")
            } else {
                SosBeaconService.stopService(this)
                android.widget.Toast.makeText(
                    this,
                    getString(com.emergencymesh.R.string.sos_cancelled),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                Log.d(TAG, "SOS deactivated")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling SOS", e)
            isSosActive = false // Reset state on error
        }
    }

    private fun scanQrCode() {
        // TODO: Implement QR code scanning using ZXing
        android.widget.Toast.makeText(
            this,
            "QR scanner - point at another device's QR code",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun showQrCode() {
        // Show connect link as QR code
        val connectLink = meshManager.connectLink
        if (connectLink != null) {
            android.widget.Toast.makeText(
                this,
                "Show QR code: $connectLink",
                android.widget.Toast.LENGTH_LONG
            ).show()
        } else {
            android.widget.Toast.makeText(
                this,
                "Generating QR code...",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if services are still running
        if (!isServiceRunning(MeshService::class.java)) {
            Log.w(TAG, "MeshService not running - restarting")
            startMeshServices()
        }
    }

    override fun onPause() {
        super.onPause()
        // Keep services running in background
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(networkReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        // Don't cleanup meshManager - services should keep running
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == serviceClass.name }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val KEY_SOS_ACTIVE = "sos_active_state"
    }
}

@Composable
fun EmergencyMeshApp(
    connectionState: ConnectionState,
    nearbyDeviceCount: Int,
    sosActive: Boolean,
    onSosPressed: () -> Unit,
    onScanQrPressed: () -> Unit,
    onShowQrPressed: () -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            BottomNavigationBar(
                onScanQrPressed = onScanQrPressed,
                onShowQrPressed = onShowQrPressed
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status bar
            StatusIndicator(
                connectionState = connectionState,
                nearbyDeviceCount = nearbyDeviceCount
            )

            Spacer(modifier = Modifier.weight(1f))

            // SOS Button
            SOSButton(
                isActive = sosActive,
                onClick = onSosPressed
            )

            Spacer(modifier = Modifier.weight(1f))

            // Quick actions
            QuickActionsRow()
        }
    }
}

@Composable
fun StatusIndicator(
    connectionState: ConnectionState,
    nearbyDeviceCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .padding(end = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.size(12.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = when (connectionState) {
                                ConnectionState.CONNECTED -> Color(0xFF388E3C)
                                ConnectionState.CONNECTING -> Color(0xFFF57C00)
                                else -> Color(0xFFD32F2F)
                            }
                        ) {}
                    }

                    Text(
                        text = when (connectionState) {
                            ConnectionState.CONNECTED -> "Mesh Active"
                            ConnectionState.CONNECTING -> "Connecting..."
                            else -> "Disconnected"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Text(
                    text = "$nearbyDeviceCount devices nearby",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Show error message if disconnected
            if (connectionState == ConnectionState.ERROR) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFFD32F2F).copy(alpha = 0.2f)
                    )
                ) {
                    Text(
                        text = "⚠️ Mesh network error. Check WiFi and permissions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD32F2F),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun SOSButton(
    isActive: Boolean,
    onClick: () -> Unit
) {
    var isPulsing by remember { mutableStateOf(false) }

    LaunchedEffect(isActive) {
        if (isActive) {
            while (true) {
                isPulsing = true
                kotlinx.coroutines.delay(500)
                isPulsing = false
                kotlinx.coroutines.delay(500)
            }
        } else {
            isPulsing = false
        }
    }

    Button(
        onClick = onClick,
        modifier = Modifier
            .size(200.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive) Color(0xFFD32F2F) else Color(0xFFB71C1C)
        ),
        shape = RoundedCornerShape(100.dp),
        enabled = true // Always enabled
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "SOS",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White
            )
            Text(
                text = if (isActive) "TAP TO CANCEL" else "TAP FOR HELP",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f)
            )
        }
    }
}

@Composable
fun BottomNavigationBar(
    onScanQrPressed: () -> Unit,
    onShowQrPressed: () -> Unit
) {
    NavigationBar {
        NavigationBarItem(
            icon = { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR") },
            label = { Text("Scan") },
            selected = false,
            onClick = onScanQrPressed
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.QrCode, contentDescription = "Show QR") },
            label = { Text("My QR") },
            selected = false,
            onClick = onShowQrPressed
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.People, contentDescription = "Devices") },
            label = { Text("Devices") },
            selected = false,
            onClick = { }
        )
        NavigationBarItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") },
            selected = false,
            onClick = { }
        )
    }
}

@Composable
fun QuickActionsRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        QuickActionButton(
            icon = Icons.Default.LocationOn,
            label = "Share Location"
        ) { }

        QuickActionButton(
            icon = Icons.Default.Info,
            label = "Medical ID"
        ) { }

        QuickActionButton(
            icon = Icons.Default.BatteryFull,
            label = "Battery Saver"
        ) { }
    }
}

@Composable
fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.size(64.dp)
        ) {
            Icon(icon, contentDescription = label)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall
        )
    }
}
