package com.emergencymesh.mesh

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.emergencymesh.EmergencyMeshApp
import com.emergencymesh.R
import kotlinx.coroutines.*

/**
 * Foreground service to keep mesh network running in background
 * Essential for emergency scenarios where app must work even when not in foreground
 */
class MeshService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MeshService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "MeshService started")

        // Create foreground notification
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Start mesh network
        startMeshNetwork()

        // If service is killed, restart it
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "MeshService destroyed")
        stopMeshNetwork()
    }

    private fun createNotification(): Notification {
        val channelId = EmergencyMeshApp.MESH_SERVICE_CHANNEL_ID

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                channelId,
                "Mesh Network Service",
                NotificationManager.IMPORTANCE_LOW
            ).also { channel ->
                getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.emergencymesh.ui.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.mesh_active))
            .setContentText("Monitoring for nearby devices")
            .setSmallIcon(R.drawable.ic_mesh_network)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startMeshNetwork() {
        val app = application as EmergencyMeshApp
        // Launch in serviceScope
        serviceScope.launch {
            try {
                app.meshManager.enableHotspot(true)
                app.meshManager.startDeviceDiscovery()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start mesh network", e)
            }
        }
    }

    private fun stopMeshNetwork() {
        val app = application as EmergencyMeshApp
        // Run cleanup in scope
        serviceScope.launch {
            app.meshManager.let { manager ->
                manager.enableHotspot(false)
                manager.stopDeviceDiscovery()
                manager.cleanup()
            }
        }
    }

    companion object {
        private const val TAG = "MeshService"
        private const val NOTIFICATION_ID = 1001
    }
}
