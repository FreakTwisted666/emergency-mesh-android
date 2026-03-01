package com.emergencymesh.security

import android.content.Context
import android.content.SharedPreferences
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
    private val prefs: SharedPreferences = context.getSharedPreferences(ENCRYPTION_PREFS, Context.MODE_PRIVATE)

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
            val keysetJson = prefs.getString(KEYSET_KEY, null)

            if (keysetJson != null) {
                val keysetBytes = Base64.decode(keysetJson, Base64.DEFAULT)
                keysetHandle = KeysetHandle.read(com.google.crypto.tink.JsonKeysetReader.withBytes(keysetBytes))
            } else {
                keysetHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
                val keysetBytes = keysetHandle?.write(com.google.crypto.tink.JsonKeysetWriter.withoutSecrets())
                val keysetJson = Base64.encodeToString(keysetBytes, Base64.DEFAULT)
                prefs.edit().putString(KEYSET_KEY, keysetJson).apply()
            }

            aead = keysetHandle?.getPrimitive(Aead::class.java)
            Log.d(TAG, "Encryption initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize keyset", e)
            try {
                keysetHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
                aead = keysetHandle?.getPrimitive(Aead::class.java)
                Log.w(TAG, "Encryption initialized with new keyset")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to create fallback keyset", e2)
            }
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
        private const val ENCRYPTION_PREFS = "emergency_encryption_keys"
        private const val KEYSET_KEY = "master_keyset"
    }
}
