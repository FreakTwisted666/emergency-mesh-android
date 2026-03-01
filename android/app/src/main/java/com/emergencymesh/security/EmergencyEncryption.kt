package com.emergencymesh.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.KeyStore

/**
 * Production encryption for emergency messages
 * Uses Google Tink with AES-256-GCM
 * Keys are persisted in Android EncryptedSharedPreferences
 */
class EmergencyEncryption(private val context: Context) {

    private var aead: Aead? = null
    private var keysetHandle: KeysetHandle? = null
    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "encryption_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        try {
            AeadConfig.register()
            loadOrCreateKeyset()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize encryption", e)
        }
    }

    private fun loadOrCreateKeyset() {
        try {
            val keysetJson = encryptedPrefs.getString(KEYSET_KEY, null)
            
            if (keysetJson != null) {
                // Load existing keyset
                val keysetBytes = Base64.decode(keysetJson, Base64.DEFAULT)
                keysetHandle = KeysetHandle.read(
                    com.google.crypto.tink.JsonKeysetReader.withBytes(keysetBytes)
                )
                Log.d(TAG, "Loaded existing encryption keyset")
            } else {
                // Generate new keyset and persist it
                keysetHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
                val writer = com.google.crypto.tink.JsonKeysetWriter.withByteArrayOutputStream()
                keysetHandle?.write(writer)
                val keysetBytes = writer.toByteArray()
                val keysetJsonToSave = Base64.encodeToString(keysetBytes, Base64.DEFAULT)
                encryptedPrefs.edit().putString(KEYSET_KEY, keysetJsonToSave).apply()
                Log.d(TAG, "Generated and saved new encryption keyset")
            }
            
            aead = keysetHandle?.getPrimitive(Aead::class.java)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load/create keyset", e)
            // Fallback: generate new keyset (won't be able to decrypt old messages)
            try {
                keysetHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
                aead = keysetHandle?.getPrimitive(Aead::class.java)
                Log.w(TAG, "Using fallback keyset - old messages cannot be decrypted")
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

    /**
     * Clear and regenerate keys (use with caution - old messages become unreadable)
     */
    fun rotateKeys() {
        try {
            keysetHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            val writer = com.google.crypto.tink.JsonKeysetWriter.withByteArrayOutputStream()
            keysetHandle?.write(writer)
            val keysetBytes = writer.toByteArray()
            val keysetJson = Base64.encodeToString(keysetBytes, Base64.DEFAULT)
            encryptedPrefs.edit().putString(KEYSET_KEY, keysetJson).apply()
            aead = keysetHandle?.getPrimitive(Aead::class.java)
            Log.d(TAG, "Keys rotated successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Key rotation failed", e)
        }
    }

    companion object {
        private const val TAG = "EmergencyEncryption"
        private const val KEYSET_KEY = "master_keyset"
    }
}
