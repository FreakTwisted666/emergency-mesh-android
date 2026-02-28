package com.emergencymesh.security

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.util.UUID

/**
 * Production encryption for emergency messages
 * Uses Google Tink with AES-256-GCM for authenticated encryption
 * 
 * CRITICAL: This ensures SOS messages and location data are protected
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

    /**
     * Initialize or load encryption keyset
     */
    private fun initializeKeyset() {
        try {
            // Try to load existing keyset from secure storage
            val prefs = context.getSharedPreferences(ENCRYPTION_PREFS, Context.MODE_PRIVATE)
            val keysetJson = prefs.getString(KEYSET_KEY, null)

            if (keysetJson != null) {
                // Load existing keyset
                val keysetBytes = Base64.decode(keysetJson, Base64.DEFAULT)
                keysetHandle = KeysetHandle.read(JsonKeysetReader(keysetBytes))
            } else {
                // Generate new keyset
                keysetHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
                
                // Save keyset
                val keysetBytes = keysetHandle?.write(JsonKeysetWriter())
                val keysetJson = Base64.encodeToString(keysetBytes, Base64.DEFAULT)
                
                prefs.edit()
                    .putString(KEYSET_KEY, keysetJson)
                    .apply()
            }

            aead = keysetHandle?.getPrimitive(Aead::class.java)
            Log.d(TAG, "Encryption initialized successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize keyset", e)
            // Fallback: create new keyset
            try {
                keysetHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
                aead = keysetHandle?.getPrimitive(Aead::class.java)
                Log.w(TAG, "Encryption initialized with new keyset (previous lost)")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to create fallback keyset", e2)
            }
        }
    }

    /**
     * Encrypt a message
     * @param plaintext The message to encrypt
     * @param associatedData Optional additional data to authenticate (e.g., sender ID)
     * @return Base64-encoded ciphertext, or null if encryption fails
     */
    fun encrypt(plaintext: String, associatedData: String? = null): String? {
        try {
            val aead = this.aead ?: run {
                Log.e(TAG, "Encryption not initialized")
                return null
            }

            val plaintextBytes = plaintext.toByteArray(StandardCharsets.UTF_8)
            val adBytes = associatedData?.toByteArray(StandardCharsets.UTF_8) ?: byteArrayOf()

            val ciphertextBytes = aead.encrypt(plaintextBytes, adBytes)
            return Base64.encodeToString(ciphertextBytes, Base64.DEFAULT)

        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Encryption failed", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected encryption error", e)
            return null
        }
    }

    /**
     * Decrypt a message
     * @param ciphertext Base64-encoded ciphertext
     * @param associatedData Same associated data used during encryption
     * @return Decrypted plaintext, or null if decryption fails
     */
    fun decrypt(ciphertext: String, associatedData: String? = null): String? {
        try {
            val aead = this.aead ?: run {
                Log.e(TAG, "Encryption not initialized")
                return null
            }

            val ciphertextBytes = Base64.decode(ciphertext, Base64.DEFAULT)
            val adBytes = associatedData?.toByteArray(StandardCharsets.UTF_8) ?: byteArrayOf()

            val plaintextBytes = aead.decrypt(ciphertextBytes, adBytes)
            return String(plaintextBytes, StandardCharsets.UTF_8)

        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Decryption failed - possibly tampered message", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected decryption error", e)
            return null
        }
    }

    /**
     * Encrypt an emergency message object
     */
    fun encryptEmergencyMessage(
        messageId: String,
        content: String,
        senderId: String,
        timestamp: Long,
        latitude: Double?,
        longitude: Double?
    ): String? {
        val messageJson = JSONObject().apply {
            put("id", messageId)
            put("c", content) // Shorter keys to save space
            put("s", senderId)
            put("t", timestamp)
            put("lat", latitude)
            put("lon", longitude)
            put("type", "SOS")
        }

        // Encrypt with sender ID as associated data for authentication
        return encrypt(messageJson.toString(), senderId)
    }

    /**
     * Decrypt an emergency message object
     */
    fun decryptEmergencyMessage(ciphertext: String, senderId: String): EmergencyMessage? {
        val plaintext = decrypt(ciphertext, senderId) ?: return null

        return try {
            val json = JSONObject(plaintext)
            EmergencyMessage(
                id = json.getString("id"),
                content = json.getString("c"),
                senderId = json.getString("s"),
                timestamp = json.getLong("t"),
                latitude = json.optDouble("lat", Double.NaN),
                longitude = json.optDouble("lon", Double.NaN)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse decrypted message", e)
            null
        }
    }

    /**
     * Generate a new keyset (for key rotation)
     */
    fun rotateKeys() {
        try {
            // Generate new keyset
            keysetHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            aead = keysetHandle?.getPrimitive(Aead::class.java)

            // Save new keyset
            val prefs = context.getSharedPreferences(ENCRYPTION_PREFS, Context.MODE_PRIVATE)
            val keysetBytes = keysetHandle?.write(JsonKeysetWriter())
            val keysetJson = Base64.encodeToString(keysetBytes, Base64.DEFAULT)

            prefs.edit()
                .putString(KEYSET_KEY, keysetJson)
                .apply()

            Log.d(TAG, "Keys rotated successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Key rotation failed", e)
        }
    }

    companion object {
        private const val TAG = "EmergencyEncryption"
        private const val ENCRYPTION_PREFS = "emergency_encryption_keys"
        private const val KEYSET_KEY = "master_keyset"
    }
}

/**
 * Decrypted emergency message data class
 */
data class EmergencyMessage(
    val id: String,
    val content: String,
    val senderId: String,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double
) {
    fun hasLocation(): Boolean = !latitude.isNaN() && !longitude.isNaN()
}

/**
 * Simple JSON keyset reader/writer for Tink
 * Production should use Android Keystore for better security
 */
class JsonKeysetReader(private val jsonData: ByteArray) : com.google.crypto.tink.KeysetReader() {
    override fun read(): com.google.crypto.tink.proto.Keyset {
        return com.google.crypto.tink.proto.Keyset.parseFrom(jsonData)
    }

    override fun readEncryptedKeyset(): com.google.crypto.tink.proto.EncryptedKeyset {
        return com.google.crypto.tink.proto.EncryptedKeyset.parseFrom(jsonData)
    }
}

class JsonKeysetWriter : com.google.crypto.tink.KeysetWriter() {
    override fun write(keyset: com.google.crypto.tink.proto.Keyset): ByteArray {
        return keyset.toByteArray()
    }

    override fun write(encryptedKeyset: com.google.crypto.tink.proto.EncryptedKeyset): ByteArray {
        return encryptedKeyset.toByteArray()
    }
}
