package com.emergencymesh.data

import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
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
    val expiresAt: Long?
)

enum class MessageType {
    TEXT,
    SOS,
    LOCATION,
    EMERGENCY_INFO
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

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE senderId = :senderId ORDER BY timestamp DESC")
    fun getMessagesFromSender(senderId: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE messageType = 'SOS' ORDER BY timestamp DESC")
    fun getSosMessages(): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<Message>)

    @Query("DELETE FROM messages WHERE timestamp < :cutoffTime")
    suspend fun deleteOlderThan(cutoffTime: Long)

    @Query("UPDATE messages SET isRead = 1 WHERE id = :messageId")
    suspend fun markAsRead(messageId: String)
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

    @Query("DELETE FROM devices WHERE lastSeen < :cutoffTime")
    suspend fun deleteStaleDevices(cutoffTime: Long)

    @Query("SELECT COUNT(*) FROM devices")
    fun getDeviceCount(): Int
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

@Database(
    entities = [Message::class, Device::class, EmergencyInfo::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun deviceDao(): DeviceDao
    abstract fun emergencyInfoDao(): EmergencyInfoDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: android.content.Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "emergency_mesh_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class Converters {
    @TypeConverter
    fun fromMessageType(value: MessageType): String = value.name

    @TypeConverter
    fun toMessageType(value: String): MessageType = MessageType.valueOf(value)
}
