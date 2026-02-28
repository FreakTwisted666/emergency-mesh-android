package com.emergencymesh

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.emergencymesh.data.AppDatabase
import com.emergencymesh.mesh.MeshManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class EmergencyMeshApp : Application() {

    val applicationScope = CoroutineScope(SupervisorJob())

    lateinit var database: AppDatabase
        private set

    lateinit var meshManager: MeshManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize database
        database = AppDatabase.getDatabase(this)

        // Initialize mesh manager
        meshManager = MeshManager(this)

        // Create notification channels
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // SOS Channel - Highest priority
            val sosChannel = NotificationChannel(
                SOS_CHANNEL_ID,
                "SOS Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical SOS alerts from nearby devices"
                enableVibration(true)
                enableLights(true)
                lightColor = android.graphics.Color.RED
            }

            // Mesh Service Channel
            val meshChannel = NotificationChannel(
                MESH_SERVICE_CHANNEL_ID,
                "Mesh Network Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background mesh network operation"
                setShowBadge(false)
            }

            // Messages Channel
            val messagesChannel = NotificationChannel(
                MESSAGES_CHANNEL_ID,
                "Messages",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "New message notifications"
            }

            notificationManager.createNotificationChannels(
                listOf(sosChannel, meshChannel, messagesChannel)
            )
        }
    }

    companion object {
        const val SOS_CHANNEL_ID = "sos_alerts"
        const val MESH_SERVICE_CHANNEL_ID = "mesh_service"
        const val MESSAGES_CHANNEL_ID = "messages"

        lateinit var instance: EmergencyMeshApp
            private set
    }
}
