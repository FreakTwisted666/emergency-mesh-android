package com.emergencymesh.sos

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*

/**
 * Manages visual SOS signals using camera flash
 */
class SosFlashlight(private val context: Context) {

    private val cameraManager get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraId: String? = null
    private var isFlashing = false
    private var flashJob: Job? = null
    private val flashScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        cameraId = cameraManager.cameraIdList.firstOrNull { id ->
            try {
                cameraManager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) ==
                        android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
            } catch (e: Exception) {
                false
            }
        }
    }

    fun startFlashing() {
        if (isFlashing || cameraId == null) return

        isFlashing = true
        flashJob = flashScope.launch {
            while (isFlashing) {
                try {
                    repeat(3) { flash() }
                    delay(500)
                    repeat(3) { flash(long = true) }
                    delay(500)
                    repeat(3) { flash() }
                    delay(1000)
                } catch (e: Exception) {
                    Log.e(TAG, "Flash error", e)
                }
            }
        }
    }

    fun stopFlashing() {
        isFlashing = false
        flashJob?.cancel()
        flashJob = null
        turnOffFlash()
    }

    private suspend fun flash(long: Boolean = false) {
        turnOnFlash()
        delay(if (long) 600 else 200)
        turnOffFlash()
        delay(200)
    }

    private fun turnOnFlash() {
        try {
            cameraId?.let { id ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cameraManager.setTorchMode(id, true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn on flash", e)
        }
    }

    private fun turnOffFlash() {
        try {
            cameraId?.let { id ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    cameraManager.setTorchMode(id, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to turn off flash", e)
        }
    }

    companion object {
        private const val TAG = "SosFlashlight"
    }
}
