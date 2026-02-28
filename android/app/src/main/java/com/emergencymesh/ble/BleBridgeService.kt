package com.emergencymesh.ble

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Bluetooth LE Bridge Service for cross-platform communication
 * Currently a stub - to be implemented for iOS bridge support
 */
class BleBridgeService : Service() {
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BleBridgeService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "BleBridgeService started")
        // For now, just return - actual implementation will come in v1.2.0
        stopSelf()
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BleBridgeService destroyed")
    }
    
    companion object {
        private const val TAG = "BleBridgeService"
    }
}
