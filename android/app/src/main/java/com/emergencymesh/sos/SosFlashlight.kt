package com.emergencymesh.sos

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

/**
 * Manages visual SOS signals using camera flash
 */
class SosFlashlight(private val context: Context) {

    private val cameraManager get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var cameraId: String? = null
    private var isFlashing = false
    private var flashJob: kotlinx.coroutines.Job? = null

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

    fun startFlashing(scope: kotlinx.coroutines.CoroutineScope) {
        if (isFlashing || cameraId == null) return

        isFlashing = true
        flashJob = scope.launch {
            while (isFlashing) {
                try {
                    // SOS in Morse code: ... --- ...
                    // S: 3 short flashes
                    repeat(3) { flash() }
                    kotlinx.coroutines.delay(500)
                    // O: 3 long flashes
                    repeat(3) { flash(long = true) }
                    kotlinx.coroutines.delay(500)
                    // S: 3 short flashes
                    repeat(3) { flash() }
                    kotlinx.coroutines.delay(1000)
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
        kotlinx.coroutines.delay(if (long) 600 else 200)
        turnOffFlash()
        kotlinx.coroutines.delay(200)
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
