package com.example.model;

import org.json.JSONException;
import org.json.JSONObject;
import com.example.service.ScheduleManager;
import java.util.UUID;

/**
 * Encapsulates a structured TCP packet exchanged between local mesh nodes.
 */
public class TcpPacket {
    public enum Type {
        PING,
        PONG,
        DATA,
        COMMAND,
        HEARTBEAT,
        ACK,
        DISCOVER,
        CUSTOM
    }

    private final String id;
    private final Type type;
    private final String customType; // For fully dynamic packet types (can, gps, sensor_x, etc.)
    private final String senderId;
    private final String recipientId;
    private final String payload;
    private final long timestamp;

    public TcpPacket(Type type, String senderId, String recipientId, String payload) {
        this(UUID.randomUUID().toString().substring(0, 8), type, type.name().toLowerCase(), senderId, recipientId, payload, System.currentTimeMillis());
    }

    public TcpPacket(String id, Type type, String senderId, String recipientId, String payload, long timestamp) {
        this(id, type, type.name().toLowerCase(), senderId, recipientId, payload, timestamp);
    }

    public TcpPacket(String id, Type type, String customType, String senderId, String recipientId, String payload, long timestamp) {
        this.id = id != null ? id : UUID.randomUUID().toString().substring(0, 8);
        this.type = type != null ? type : Type.CUSTOM;
        this.customType = (customType != null && !customType.trim().isEmpty()) ? customType.trim().toLowerCase() : (type != null ? type.name().toLowerCase() : "data");
        this.senderId = senderId != null ? senderId : "unknown";
        this.recipientId = recipientId != null ? recipientId : "all";
        this.payload = payload != null ? payload : "";
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    public String getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public String getCustomType() {
        return customType;
    }

    public String getEffectiveType() {
        return (customType != null && !customType.trim().isEmpty()) ? customType.trim().toLowerCase() : type.name().toLowerCase();
    }

    public String getSenderId() {
        return senderId;
    }

    public String getRecipientId() {
        return recipientId;
    }

    public String getPayload() {
        return payload;
    }

    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Serializes this packet to JSON string format with trailing newline for line-delimited TCP framing.
     * Guaranteed to include both "type" and "device_id" (always replaced/overridden with the configured device_id).
     */
    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("type", getEffectiveType());
            obj.put("device_id", senderId);
            obj.put("sender", senderId);
            obj.put("recipient", recipientId);
            obj.put("payload", payload != null ? payload : "");
            obj.put("timestamp", timestamp);
            return obj.toString();
        } catch (JSONException e) {
            return "{\"id\":\"" + id + "\",\"type\":\"" + getEffectiveType() + "\",\"device_id\":\"" + senderId + "\",\"sender\":\"" + senderId + "\",\"payload\":\"" + payload + "\"}";
        }
    }

    /**
     * Helper to get the device_id configured in the app.
     */
    public static String getConfiguredDeviceId() {
        try {
            String configuredId = ScheduleManager.getInstance().getConfig().getDeviceId();
            if (configuredId != null && !configuredId.trim().isEmpty()) {
                return configuredId.trim();
            }
        } catch (Throwable ignored) {}
        return "device_001";
    }

    /**
     * Parses a JSON string into a TcpPacket object.
     * Strict validation:
     * 1. Must be a valid JSON object; non-JSON or invalid syntax will be discarded (returns null).
     * 2. Must contain a non-empty "type" field; packets without "type" will be discarded (returns null).
     * 3. The "device_id" field is ALWAYS overridden/replaced with the device_id configured in the app.
     */
    public static TcpPacket fromJson(String jsonStr) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            return null;
        }
        try {
            // Must be a valid JSON Object
            JSONObject obj = new JSONObject(jsonStr);

            // Validation: Must contain 'type' field
            if (!obj.has("type") || obj.isNull("type")) {
                return null;
            }
            String typeStr = obj.optString("type", "").trim();
            if (typeStr.isEmpty()) {
                return null;
            }

            String id = obj.optString("id", UUID.randomUUID().toString().substring(0, 8));
            
            Type type;
            try {
                type = Type.valueOf(typeStr.toUpperCase());
            } catch (Exception e) {
                // Any dynamic type (like "can", "obd", "temperature", etc.) maps to CUSTOM
                type = Type.CUSTOM;
            }

            // Always enforce the device_id configured in the app
            String configuredDeviceId = getConfiguredDeviceId();
            String sender = obj.optString("sender", obj.optString("device_id", configuredDeviceId));
            if (sender.isEmpty()) {
                sender = configuredDeviceId;
            }

            // Override device_id in the JSON object itself
            obj.put("device_id", configuredDeviceId);
            obj.put("sender", sender);
            obj.put("type", typeStr.toLowerCase());

            String recipient = obj.optString("recipient", "all");
            
            // If the packet has a nested payload or is itself the data
            String payload = obj.optString("payload", "");
            if (payload.isEmpty() && obj.has("data")) {
                payload = obj.opt("data").toString();
            } else if (payload.isEmpty()) {
                payload = obj.toString();
            }

            long ts = obj.optLong("timestamp", System.currentTimeMillis());
            return new TcpPacket(id, type, typeStr.toLowerCase(), sender, recipient, payload, ts);
        } catch (JSONException e) {
            // Strictly discard messages that do not conform to JSON standard
            return null;
        }
    }
}
