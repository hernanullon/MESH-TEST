package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local Room Entity representing a stored raw telemetry record or TCP mesh packet.
 * Stores raw independent JSON records per sensor group (LOCATION, INERTIAL, DEVICE_STATUS, EXTERNAL_TCP).
 * Each record has its own type in the JSON and its independent sampling frequency.
 */
@Entity(
    tableName = "telemetry_buffer",
    indices = [
        Index(value = ["isSynced"]),
        Index(value = ["timestamp"]),
        Index(value = ["sourceType"])
    ]
)
data class TelemetryRecordEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Group / Source Type identifier:
     * - "LOCATION": GPS & network location readings
     * - "INERTIAL": Accelerometer, Gyroscope and Orientation
     * - "DEVICE_STATUS": Battery, RAM, Storage, Thermal health
     * - "EXTERNAL_TCP": Incoming telemetry frames from external ESP32/Nodes
     */
    val sourceType: String,

    /**
     * Source Device Identifier (e.g., "NODE-01", "ESP32-MESH-04", IP endpoint)
     */
    val deviceId: String,

    /**
     * Topic or Packet Type (e.g., "LOCATION", "INERTIAL", "DEVICE_STATUS", "DATA")
     */
    val packetType: String,

    /**
     * Timestamp of the event in Unix milliseconds
     */
    val timestamp: Long,

    /**
     * Complete raw JSON payload of the specific sensor reading
     */
    val payloadJson: String,

    /**
     * Flag indicating if the packet has been successfully published to AMQP / Cloud
     */
    val isSynced: Boolean = false,

    /**
     * Local storage insertion timestamp
     */
    val createdAt: Long = System.currentTimeMillis()
) {
    val formattedDate: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    val shortPayloadPreview: String
        get() {
            return if (payloadJson.length > 80) {
                payloadJson.substring(0, 77) + "..."
            } else {
                payloadJson
            }
        }
}
