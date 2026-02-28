package com.emergencymesh.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey val id: String,
    val senderId: String,
    val senderName: String?,
    val content: String,
    val timestamp: Long,
    val messageType: MessageType,
    val latitude: Double?,
    val longitude: Double?,
    val isRead: Boolean,
    val hopCount: Int,
    val expiresAt: Long?,
    val isEncrypted: Boolean = false,
    val deliveryStatus: DeliveryStatus = DeliveryStatus.PENDING,
    val deliveredAt: Long? = null
)

enum class MessageType {
    TEXT,
    SOS,
    LOCATION,
    EMERGENCY_INFO,
    ACK  // Delivery confirmation
}

enum class DeliveryStatus {
    PENDING,      // Waiting to be sent
    SENDING,      // Currently being sent
    SENT,         // Successfully sent
    DELIVERED,    // Recipient confirmed delivery
    FAILED        // Failed after retries
}

@Entity(tableName = "devices")
data class Device(
    @PrimaryKey val id: String,
    val name: String?,
    val virtualAddress: String,
    val signalStrength: Int,
    val hopCount: Int,
    val lastSeen: Long,
    val batteryLevel: Int?,
    val isSosActive: Boolean,
    val latitude: Double?,
    val longitude: Double?
)

@Entity(tableName = "emergency_info")
data class EmergencyInfo(
    @PrimaryKey val id: String = "user_info",
    val name: String?,
    val bloodType: String?,
    val allergies: String?,
    val medications: String?,
    val emergencyContact: String?,
    val medicalNotes: String?
)

@Entity(tableName = "message_queue")
data class QueuedMessage(
    @PrimaryKey val id: String,
    val messageJson: String,
    val retryCount: Int = 0,
    val lastRetryTime: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val priority: MessagePriority = MessagePriority.NORMAL
)

enum class MessagePriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL  // SOS messages
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE senderId = :senderId ORDER BY timestamp DESC")
    fun getMessagesFromSender(senderId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE messageType = 'SOS' ORDER BY timestamp DESC")
    fun getSosMessages(): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE deliveryStatus = 'PENDING' OR deliveryStatus = 'SENDING'")
    suspend fun getPendingMessages(): List<Message>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessage(messageId: String): Message?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<Message>)

    @Update
    suspend fun update(message: Message)

    @Query("UPDATE messages SET deliveryStatus = :status, deliveredAt = :deliveredAt WHERE id = :messageId")
    suspend fun updateDeliveryStatus(messageId: String, status: DeliveryStatus, deliveredAt: Long? = null)

    @Query("DELETE FROM messages WHERE timestamp < :cutoffTime AND messageType != 'SOS'")
    suspend fun deleteOlderThan(cutoffTime: Long)

    @Query("UPDATE messages SET isRead = 1 WHERE id = :messageId")
    suspend fun markAsRead(messageId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE deliveryStatus = 'PENDING'")
    suspend fun getPendingCount(): Int
}

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices ORDER BY lastSeen DESC")
    fun getAllDevices(): Flow<List<Device>>

    @Query("SELECT * FROM devices WHERE isSosActive = 1")
    fun getSosDevices(): Flow<List<Device>>

    @Query("SELECT * FROM devices WHERE id = :deviceId")
    suspend fun getDevice(deviceId: String): Device?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: Device)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(devices: List<Device>)

    @Update
    suspend fun update(device: Device)

    @Query("DELETE FROM devices WHERE lastSeen < :cutoffTime")
    suspend fun deleteStaleDevices(cutoffTime: Long)

    @Query("SELECT COUNT(*) FROM devices")
    fun getDeviceCount(): Int

    @Query("SELECT * FROM devices WHERE virtualAddress = :address")
    suspend fun getDeviceByAddress(address: String): Device?
}

@Dao
interface EmergencyInfoDao {
    @Query("SELECT * FROM emergency_info WHERE id = 'user_info'")
    fun getUserInfo(): Flow<EmergencyInfo?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(info: EmergencyInfo)

    @Query("DELETE FROM emergency_info")
    suspend fun deleteAll()
}

@Dao
interface MessageQueueDao {
    @Query("SELECT * FROM message_queue ORDER BY priority DESC, createdAt ASC")
    suspend fun getAllQueued(): List<QueuedMessage>

    @Query("SELECT * FROM message_queue WHERE priority = 'CRITICAL'")
    suspend fun getCriticalMessages(): List<QueuedMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(queued: QueuedMessage)

    @Update
    suspend fun update(queued: QueuedMessage)

    @Delete
    suspend fun delete(queued: QueuedMessage)

    @Query("DELETE FROM message_queue WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM message_queue")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM message_queue WHERE priority = 'CRITICAL'")
    suspend fun getCriticalCount(): Int
}

@Database(
    entities = [
        Message::class,
        Device::class,
        EmergencyInfo::class,
        QueuedMessage::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun deviceDao(): DeviceDao
    abstract fun emergencyInfoDao(): EmergencyInfoDao
    abstract fun messageQueueDao(): MessageQueueDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "emergency_mesh_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        // Migration from version 1 to 2 (added message_queue table)
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS message_queue (
                        id TEXT PRIMARY KEY NOT NULL,
                        messageJson TEXT NOT NULL,
                        retryCount INTEGER NOT NULL DEFAULT 0,
                        lastRetryTime INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL DEFAULT 0,
                        priority TEXT NOT NULL DEFAULT 'NORMAL'
                    )
                """)
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromMessageType(value: MessageType): String = value.name

    @TypeConverter
    fun toMessageType(value: String): MessageType = MessageType.valueOf(value)

    @TypeConverter
    fun fromDeliveryStatus(value: DeliveryStatus): String = value.name

    @TypeConverter
    fun toDeliveryStatus(value: String): DeliveryStatus = DeliveryStatus.valueOf(value)

    @TypeConverter
    fun fromMessagePriority(value: MessagePriority): String = value.name

    @TypeConverter
    fun toMessagePriority(value: String): MessagePriority = MessagePriority.valueOf(value)
}
