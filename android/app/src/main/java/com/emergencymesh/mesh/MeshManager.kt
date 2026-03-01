package com.emergencymesh.mesh

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.emergencymesh.EmergencyMeshApp
import com.emergencymesh.data.Device
import com.emergencymesh.data.Message
import com.emergencymesh.data.MessageType
import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode
import com.ustadmobile.meshrabiya.vnet.MeshrabiyaConnectLink
import com.ustadmobile.meshrabiya.vnet.NodeConfig
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * Production-ready mesh networking manager using Meshrabiya
 */
class MeshManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val app get() = EmergencyMeshApp.instance
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val dataStore: DataStore<Preferences>

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var receiveJob: Job? = null
    private var discoveryJob: Job? = null
    private var retryJob: Job? = null

    private var virtualNode: AndroidVirtualNode? = null
    private lateinit var chatSocket: DatagramSocket

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _nearbyDevices = MutableStateFlow<List<Device>>(emptyList())
    val nearbyDevices: StateFlow<List<Device>> = _nearbyDevices.asStateFlow()

    val deviceId: String
        get() = virtualNode?.address?.let { addr -> addr.addressToDotNotation() } ?: "0.0.0.0"

    var connectLink: String? = null
        private set

    var onMessageReceived: ((Message) -> Unit)? = null
    var onDeviceDiscovered: ((Device) -> Unit)? = null

    private val pendingMessages = mutableMapOf<String, PendingMessage>()
    private val MAX_RETRY_COUNT = 5
    private val RETRY_DELAY_MS = 5000L

    private val json = Json { encodeDefaults = true }

    init {
        // Initialize DataStore using preferencesDataStore delegate
        dataStore = context.applicationContext.getDataStore(name = "meshr_settings")
        
        ioScope.launch {
            initializeVirtualNode()
        }
        startMessageRetryProcessor()
    }

    private suspend fun initializeVirtualNode() = withContext(Dispatchers.IO) {
        try {
            virtualNode = AndroidVirtualNode(
                appContext = appContext,
                dataStore = dataStore,
                json = json,
                config = NodeConfig(maxHops = 5)
            )

            _connectionState.value = ConnectionState.CONNECTING
            
            // Create bound socket for chat
            chatSocket = virtualNode!!.createBoundDatagramSocket(MESSAGE_PORT)
            
            Log.d(TAG, "Virtual node initialized: ${virtualNode!!.address.addressToDotNotation()}")
            
            startMessageListener()
            startDeviceDiscovery()

            _connectionState.value = ConnectionState.CONNECTED

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize virtual node", e)
            _connectionState.value = ConnectionState.ERROR
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        ioScope.launch {
            delay(5000L)
            initializeVirtualNode()
        }
    }

    private fun startMessageListener() {
        receiveJob?.cancel()
        receiveJob = ioScope.launch {
            try {
                while (isActive) {
                    try {
                        val buffer: ByteArray = ByteArray(MAX_MESSAGE_SIZE)
                        val packet: DatagramPacket = DatagramPacket(buffer, buffer.size)
                        
                        chatSocket.soTimeout = 1000
                        chatSocket.receive(packet)
                        
                        val messageData = String(packet.data, 0, packet.length)
                        val message = parseMessage(messageData)
                        
                        if (message != null) {
                            Log.d(TAG, "Received message: ${message.id} from ${message.senderId}")
                            
                            app.database.messageDao().insert(message)
                            
                            withContext(Dispatchers.Main) {
                                onMessageReceived?.invoke(message)
                            }
                        }
                    } catch (e: SocketTimeoutException) {
                        // Expected - continue listening
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Error receiving message", e)
                            delay(1000)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Message listener failed", e)
                if (isActive) {
                    delay(2000)
                    startMessageListener()
                }
            }
        }
    }

    fun startDeviceDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = ioScope.launch {
            while (isActive && _connectionState.value == ConnectionState.CONNECTED) {
                try {
                    val state = virtualNode?.state?.first()
                    val devices = mutableListOf<Device>()
                    
                    state?.originatorMessages?.forEach { (addrInt, lastMsg) ->
                        try {
                            val device = Device(
                                id = addrInt.addressToDotNotation(),
                                name = null,
                                virtualAddress = addrInt.addressToDotNotation(),
                                signalStrength = -100,
                                hopCount = lastMsg.hopCount.toInt(),
                                lastSeen = lastMsg.timeReceived,
                                batteryLevel = null,
                                isSosActive = false,
                                latitude = null,
                                longitude = null
                            )
                            devices.add(device)
                            Log.d(TAG, "Discovered device: ${device.virtualAddress} (${device.hopCount} hops)")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing device", e)
                        }
                    }
                    
                    // Clean up stale devices (not seen in 60 seconds)
                    val staleCutoff = System.currentTimeMillis() - 60000
                    val activeDevices = devices.filter { it.lastSeen > staleCutoff }
                    
                    if (activeDevices.isNotEmpty()) {
                        app.database.deviceDao().insertAll(activeDevices)
                    }
                    
                    _nearbyDevices.value = activeDevices
                    
                    activeDevices.forEach { device ->
                        withContext(Dispatchers.Main) {
                            onDeviceDiscovered?.invoke(device)
                        }
                    }
                    
                    Log.d(TAG, "Device discovery: ${activeDevices.size} devices found")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Device discovery error", e)
                }
                
                delay(DISCOVERY_INTERVAL_MS)
            }
        }
    }

    fun stopDeviceDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        Log.d(TAG, "Device discovery stopped")
    }

    suspend fun sendMessage(message: Message): Boolean = withContext(Dispatchers.IO) {
        if (virtualNode == null) {
            Log.e(TAG, "Cannot send message - virtualNode not initialized")
            addToRetryQueue(message)
            return@withContext false
        }
        
        try {
            val data = messageToJson(message).encodeToByteArray()
            val packet = DatagramPacket(data, data.size)

            // Parse destination address
            val destAddr: ByteArray = when {
                message.messageType == MessageType.SOS -> {
                    // Broadcast - 255.255.255.255
                    byteArrayOf(
                        0xFF.toByte(),
                        0xFF.toByte(),
                        0xFF.toByte(),
                        0xFF.toByte()
                    )
                }
                else -> {
                    try {
                        InetAddress.getByName(message.senderId).address
                    } catch (e: Exception) {
                        Log.e(TAG, "Cannot resolve address: ${message.senderId}", e)
                        return@withContext false
                    }
                }
            }

            packet.address = InetAddress.getByAddress(destAddr)
            packet.port = MESSAGE_PORT
            
            chatSocket.send(packet)
            Log.d(TAG, "Message sent: ${message.id}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
            addToRetryQueue(message)
            false
        }
    }

    suspend fun broadcastSOS(latitude: Double?, longitude: Double?): Boolean {
        val sosMessage = Message(
            id = UUID.randomUUID().toString(),
            senderId = deviceId,
            senderName = null,
            content = "🚨 SOS - EMERGENCY - NEED HELP",
            timestamp = System.currentTimeMillis(),
            messageType = MessageType.SOS,
            latitude = latitude,
            longitude = longitude,
            isRead = false,
            hopCount = 0,
            expiresAt = null
        )
        
        app.database.messageDao().insert(sosMessage)
        val sent = sendMessage(sosMessage)
        
        if (sent) {
            Log.d(TAG, "SOS broadcast sent successfully: ${sosMessage.id}")
        } else {
            Log.e(TAG, "SOS broadcast FAILED: ${sosMessage.id}")
        }
        
        withContext(Dispatchers.Main) {
            onMessageReceived?.invoke(sosMessage)
        }
        
        return sent
    }

    private fun addToRetryQueue(message: Message) {
        val pending = pendingMessages[message.id]
        if (pending == null || pending.retryCount < MAX_RETRY_COUNT) {
            pendingMessages[message.id] = PendingMessage(
                message = message,
                retryCount = (pending?.retryCount ?: 0) + 1,
                lastRetryTime = System.currentTimeMillis()
            )
            Log.d(TAG, "Message ${message.id} added to retry queue (attempt ${pending?.retryCount ?: 1}/$MAX_RETRY_COUNT)")
        } else {
            Log.e(TAG, "Message ${message.id} exceeded max retry count, giving up")
            pendingMessages.remove(message.id)
        }
    }

    private fun startMessageRetryProcessor() {
        retryJob?.cancel()
        retryJob = ioScope.launch {
            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    val toRetry = pendingMessages.filterValues { now - it.lastRetryTime >= RETRY_DELAY_MS }
                    
                    toRetry.forEach { (messageId, pending) ->
                        Log.d(TAG, "Retrying message: $messageId (attempt ${pending.retryCount})")
                        sendMessage(pending.message)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Retry processor error", e)
                }
                delay(RETRY_DELAY_MS)
            }
        }
    }

    suspend fun enableHotspot(enabled: Boolean) = withContext(Dispatchers.IO) {
        try {
            virtualNode?.setWifiHotspotEnabled(
                enabled = enabled,
                preferredBand = ConnectBand.BAND_2GHZ,
                hotspotType = HotspotType.AUTO
            )

            if (enabled) {
                // Wait for hotspot to start
                virtualNode?.state?.first { it.wifiState.hotspotIsStarted }
                
                connectLink = virtualNode?.state?.first()?.connectUri
                Log.d(TAG, "Hotspot enabled. Connect link: $connectLink")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable hotspot", e)
            withContext(Dispatchers.Main) {
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    suspend fun connectToHotspot(connectLink: String) = withContext(Dispatchers.IO) {
        try {
            val link = MeshrabiyaConnectLink.parseUri(connectLink, json)
            link.hotspotConfig?.let { config ->
                Log.d(TAG, "Connecting to hotspot: ${config.ssid}")
                virtualNode?.connectAsStation(config)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to hotspot", e)
        }
    }

    fun cleanup() {
        ioScope.launch {
            try {
                receiveJob?.cancel()
                discoveryJob?.cancel()
                retryJob?.cancel()
                chatSocket.close()
                virtualNode?.close()
                pendingMessages.clear()
                _connectionState.value = ConnectionState.DISCONNECTED
                Log.d(TAG, "MeshManager cleaned up")
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
    }

    private fun messageToJson(message: Message): String {
        return JSONObject().apply {
            put("id", message.id)
            put("senderId", message.senderId)
            put("senderName", message.senderName ?: "")
            put("content", message.content)
            put("timestamp", message.timestamp)
            put("messageType", message.messageType.name)
            put("latitude", message.latitude ?: 0.0)
            put("longitude", message.longitude ?: 0.0)
            put("isRead", message.isRead)
            put("hopCount", message.hopCount)
        }.toString()
    }

    private fun parseMessage(messageData: String): Message? {
        return try {
            if (messageData.length > MAX_MESSAGE_SIZE || messageData.isBlank()) {
                return null
            }
            
            val json = JSONObject(messageData)
            Message(
                id = json.getString("id"),
                senderId = json.getString("senderId"),
                senderName = json.optString("senderName", null),
                content = json.getString("content"),
                timestamp = json.getLong("timestamp"),
                messageType = MessageType.valueOf(json.getString("messageType")),
                latitude = json.optDouble("latitude", Double.NaN).let { if (it.isNaN()) null else it },
                longitude = json.optDouble("longitude", Double.NaN).let { if (it.isNaN()) null else it },
                isRead = json.getBoolean("isRead"),
                hopCount = json.getInt("hopCount"),
                expiresAt = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message", e)
            null
        }
    }

    companion object {
        private const val TAG = "MeshManager"
        private const val MESSAGE_PORT = 8080
        private const val MAX_MESSAGE_SIZE = 65535
        private const val DISCOVERY_INTERVAL_MS = 5000L
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class PendingMessage(
    val message: Message,
    val retryCount: Int,
    val lastRetryTime: Long
)

// Extension to convert Int IP to dotted notation
fun Int.addressToDotNotation(): String {
    return "${(this shr 24) and 0xFF}.${(this shr 16) and 0xFF}.${(this shr 8) and 0xFF}.${this and 0xFF}"
}

// Extension to convert InetAddress to dotted notation
fun InetAddress.addressToDotNotation(): String {
    return "${(this.address[0].toInt() and 0xFF)}.${(this.address[1].toInt() and 0xFF)}.${(this.address[2].toInt() and 0xFF)}.${(this.address[3].toInt() and 0xFF)}"
}
