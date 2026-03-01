package com.emergencymesh.security

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException

/**
 * Production encryption for emergency messages
 * Uses Google Tink with AES-256-GCM
 */
class EmergencyEncryption(private val context: Context) {

    private var aead: Aead? = null
    private var keysetHandle: KeysetHandle? = null

    init {
        try {
            AeadConfig.register()
            initializeKeyset()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encryption", e)
        }
    }

    private fun initializeKeyset() {
        try {
            // Generate new keyset each time (simplified - production should persist)
            keysetHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            aead = keysetHandle?.getPrimitive(Aead::class.java)
            Log.d(TAG, "Encryption initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize keyset", e)
        }
    }

    fun encrypt(plaintext: String, associatedData: String? = null): String? {
        try {
            val aead = this.aead ?: return null
            val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
            val adBytes = associatedData?.toByteArray(StandardCharsets.UTF_8) ?: byteArrayOf()
            val ciphertextBytes = aead.encrypt(plaintextBytes, adBytes)
            return Base64.encodeToString(ciphertextBytes, Base64.DEFAULT)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Encryption failed", e)
            return null
        }
    }

    fun decrypt(ciphertext: String, associatedData: String? = null): String? {
        try {
            val aead = this.aead ?: return null
            val ciphertextBytes = Base64.decode(ciphertext, Base64.DEFAULT)
            val adBytes = associatedData?.toByteArray(StandardCharsets.UTF_8) ?: byteArrayOf()
            val plaintextBytes = aead.decrypt(ciphertextBytes, adBytes)
            return String(plaintextBytes, StandardCharsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Decryption failed", e)
            return null
        }
    }

    companion object {
        private const val TAG = "EmergencyEncryption"
    }
}
