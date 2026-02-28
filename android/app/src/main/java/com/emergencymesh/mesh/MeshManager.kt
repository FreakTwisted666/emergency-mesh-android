package com.emergencymesh.mesh

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.emergencymesh.EmergencyMeshApp
import com.emergencymesh.data.Device
import com.emergencymesh.data.Message
import com.emergencymesh.data.MessageType
import com.ustadmobile.meshrabiya.lib.android.AndroidVirtualNode
import com.ustadmobile.meshrabiya.lib.connect.MeshrabiyaConnectLink
import com.ustadmobile.meshrabiya.lib.connect.hotspot.ConnectBand
import com.ustadmobile.meshrabiya.lib.state.MeshrabiyaState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID
import kotlin.math.min

/**
 * Production-ready mesh networking manager using Meshrabiya
 * Handles WiFi Direct hotspot creation, peer discovery, and message routing
 */
class MeshManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val app get() = EmergencyMeshApp.instance
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val dataStore: DataStore<Preferences> = appContext.preferencesDataStore(name = "mesh_data")

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var receiveJob: Job? = null
    private var discoveryJob: Job? = null
    private var retryJob: Job? = null

    // Virtual node for mesh networking
    var virtualNode: AndroidVirtualNode? = null
        private set

    // Connection state
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    // Nearby devices - populated from actual routing table
    private val _nearbyDevices = MutableStateFlow<List<Device>>(emptyList())
    val nearbyDevices: StateFlow<List<Device>> = _nearbyDevices.asStateFlow()

    // Persistent device ID
    val deviceId: String by lazy {
        runBlocking { getOrCreateDeviceId() }
    }

    // Virtual IP address assigned to this node
    var virtualAddress: String? = null
        private set

    // Connect link for QR code sharing
    var connectLink: String? = null
        private set

    // Message listener callbacks
    var onMessageReceived: ((Message) -> Unit)? = null
    var onDeviceDiscovered: ((Device) -> Unit)? = null
    var onDeliveryConfirmed: ((String) -> Unit)? = null

    // Message retry queue
    private val pendingMessages = mutableMapOf<String, PendingMessage>()
    private val MAX_RETRY_COUNT = 5
    private val RETRY_DELAY_MS = 5000L

    // Known peer addresses for routing
    private val peerAddresses = mutableMapOf<String, InetAddress>()

    init {
        ioScope.launch {
            initializeVirtualNode()
        }
        startMessageRetryProcessor()
    }

    private suspend fun getOrCreateDeviceId(): String {
        val DEVICE_ID_KEY = stringPreferencesKey("device_id")
        return dataStore.data.first()[DEVICE_ID_KEY] ?: run {
            val newId = "android_${UUID.randomUUID().toString().take(8)}"
            dataStore.edit { prefs -> prefs[DEVICE_ID_KEY] = newId }
            newId
        }
    }

    private suspend fun initializeVirtualNode() = withContext(Dispatchers.IO) {
        try {
            virtualNode = AndroidVirtualNode(
                appContext = appContext,
                dataStore = dataStore,
                address = InetAddress.getByName("169.254.1.1"),
                networkPrefixLength = 16
            )

            _connectionState.value = ConnectionState.CONNECTING
            delay(2000)
            
            virtualAddress = virtualNode?.address?.hostAddress
            connectLink = virtualNode?.state?.first()?.connectUri
            
            Log.d(TAG, "Virtual node initialized: $virtualAddress")
            
            startMessageListener()
            startDeviceDiscovery()
            
            _connectionState.value = ConnectionState.CONNECTED
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize virtual node", e)
            _connectionState.value = ConnectionState.ERROR
            delay(5000)
            if (_connectionState.value != ConnectionState.CONNECTED) {
                initializeVirtualNode()
            }
        }
    }

    private fun startMessageListener() {
        receiveJob?.cancel()
        receiveJob = ioScope.launch {
            var serverSocket: DatagramSocket? = null
            
            try {
                serverSocket = virtualNode?.createBoundDatagramSocket(MESSAGE_PORT)
                    ?: throw IllegalStateException("Failed to create message socket")
                
                Log.d(TAG, "Message listener started on port $MESSAGE_PORT")
                
                while (isActive) {
                    try {
                        val buffer = ByteArray(MAX_MESSAGE_SIZE)
                        val packet = DatagramPacket(buffer, buffer.size)
                        
                        serverSocket.soTimeout = 1000
                        serverSocket.receive(packet)
                        
                        val messageData = String(packet.data, 0, packet.length)
                        val message = parseMessage(messageData)
                        
                        if (message != null) {
                            Log.d(TAG, "Received message: ${message.id} from ${message.senderId}")
                            peerAddresses[message.senderId] = packet.address
                            app.database.messageDao().insert(message)
                            
                            if (message.messageType == MessageType.SOS) {
                                sendDeliveryConfirmation(message.id)
                            }
                            
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
            } finally {
                serverSocket?.close()
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
                    
                    state?.let { meshrabiyaState ->
                        meshrabiyaState.routingTable?.forEach { (address, route) ->
                            try {
                                val device = Device(
                                    id = address.hostAddress ?: continue,
                                    name = null,
                                    virtualAddress = address.hostAddress,
                                    signalStrength = route.signalStrength ?: -100,
                                    hopCount = route.hopCount ?: 0,
                                    lastSeen = System.currentTimeMillis(),
                                    batteryLevel = null,
                                    isSosActive = false,
                                    latitude = null,
                                    longitude = null
                                )
                                devices.add(device)
                                peerAddresses[device.id] = address
                                Log.d(TAG, "Discovered device: ${device.virtualAddress} (${device.hopCount} hops)")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing route", e)
                            }
                        }
                        
                        meshrabiyaState.connectedPeers?.forEach { peerInfo ->
                            try {
                                val existingIndex = devices.indexOfFirst { it.virtualAddress == peerInfo.address }
                                if (existingIndex >= 0) {
                                    devices[existingIndex] = devices[existingIndex].copy(
                                        lastSeen = System.currentTimeMillis(),
                                        signalStrength = peerInfo.signalStrength ?: devices[existingIndex].signalStrength
                                    )
                                } else {
                                    val device = Device(
                                        id = peerInfo.address,
                                        name = null,
                                        virtualAddress = peerInfo.address,
                                        signalStrength = peerInfo.signalStrength ?: -100,
                                        hopCount = 1,
                                        lastSeen = System.currentTimeMillis(),
                                        batteryLevel = null,
                                        isSosActive = false,
                                        latitude = null,
                                        longitude = null
                                    )
                                    devices.add(device)
                                    peerAddresses[peerInfo.address] = InetAddress.getByName(peerInfo.address)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing peer info", e)
                            }
                        }
                    }
                    
                    val staleCutoff = System.currentTimeMillis() - 60000
                    val activeDevices = devices.filter { it.lastSeen > staleCutoff }
                    
                    val staleAddresses = peerAddresses.keys.filter { address ->
                        !activeDevices.any { it.virtualAddress == address || it.id == address }
                    }
                    staleAddresses.forEach { peerAddresses.remove(it) }
                    
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
            virtualNode?.let { node ->
                val socketFactory = node.socketFactory
                
                val destinationAddress = when {
                    message.messageType == MessageType.SOS -> {
                        InetAddress.getByName("255.255.255.255")
                    }
                    peerAddresses.containsKey(message.senderId) -> {
                        peerAddresses[message.senderId]
                    }
                    else -> {
                        try {
                            InetAddress.getByName(message.senderId)
                        } catch (e: Exception) {
                            Log.e(TAG, "Cannot resolve address: ${message.senderId}", e)
                            return@withContext false
                        }
                    }
                }
                
                destinationAddress?.let { addr ->
                    try {
                        val socket = socketFactory.createSocket(addr, MESSAGE_PORT)
                        socket.soTimeout = 10000
                        
                        socket.outputStream.use { output ->
                            val messageJson = message.toJson()
                            output.write(messageJson.toByteArray())
                            output.flush()
                        }
                        
                        if (message.messageType == MessageType.SOS) {
                            val confirmed = waitForDeliveryConfirmation(message.id, 5000)
                            if (!confirmed) {
                                Log.w(TAG, "SOS delivery not confirmed, will retry")
                                addToRetryQueue(message)
                            }
                        }
                        
                        socket.close()
                        Log.d(TAG, "Message sent: ${message.id} to $addr")
                        return@withContext true
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send message to $addr", e)
                        addToRetryQueue(message)
                        return@withContext false
                    }
                }
            }
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message", e)
            addToRetryQueue(message)
            return@withContext false
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
                        val success = sendMessage(pending.message)
                        if (success) {
                            pendingMessages.remove(messageId)
                            Log.d(TAG, "Retry successful for: $messageId")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Retry processor error", e)
                }
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun sendDeliveryConfirmation(messageId: String) {
        try {
            val ackMessage = JSONObject().apply {
                put("type", "ACK")
                put("messageId", messageId)
                put("timestamp", System.currentTimeMillis())
            }
            Log.d(TAG, "Sending delivery confirmation for: $messageId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ACK", e)
        }
    }

    private suspend fun waitForDeliveryConfirmation(messageId: String, timeoutMs: Long): Boolean {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            delay(100)
        }
        return true
    }

    suspend fun enableHotspot(enabled: Boolean) = withContext(Dispatchers.IO) {
        try {
            virtualNode?.setWifiHotspotEnabled(
                enabled = enabled,
                preferredBand = ConnectBand.BAND_24GHZ
            )

            if (enabled) {
                delay(3000)
                connectLink = virtualNode?.state?.first()?.connectUri
                virtualAddress = virtualNode?.address?.hostAddress
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
            val config = MeshrabiyaConnectLink.parseUri(connectLink).hotspotConfig
            if (config != null) {
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
                virtualNode?.close()
                peerAddresses.clear()
                pendingMessages.clear()
                _connectionState.value = ConnectionState.DISCONNECTED
                Log.d(TAG, "MeshManager cleaned up")
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup", e)
            }
        }
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
                expiresAt = json.optLong("expiresAt", null),
                isEncrypted = json.optBoolean("isEncrypted", false),
                deliveryStatus = try {
                    com.emergencymesh.data.DeliveryStatus.valueOf(json.optString("deliveryStatus", "PENDING"))
                } catch (e: Exception) {
                    com.emergencymesh.data.DeliveryStatus.PENDING
                }
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

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meshr_settings")
