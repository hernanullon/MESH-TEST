package com.example.model;

import org.json.JSONException;
import org.json.JSONObject;
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
        DISCOVER
    }

    private final String id;
    private final Type type;
    private final String senderId;
    private final String recipientId;
    private final String payload;
    private final long timestamp;

    public TcpPacket(Type type, String senderId, String recipientId, String payload) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.type = type;
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.payload = payload;
        this.timestamp = System.currentTimeMillis();
    }

    public TcpPacket(String id, Type type, String senderId, String recipientId, String payload, long timestamp) {
        this.id = id;
        this.type = type;
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.payload = payload;
        this.timestamp = timestamp;
    }

    public String getId() {
        return id;
    }

    public Type getType() {
        return type;
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
     */
    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("type", type.name());
            obj.put("sender", senderId);
            obj.put("recipient", recipientId);
            obj.put("payload", payload != null ? payload : "");
            obj.put("timestamp", timestamp);
            return obj.toString();
        } catch (JSONException e) {
            return "{\"id\":\"" + id + "\",\"type\":\"" + type.name() + "\",\"sender\":\"" + senderId + "\",\"payload\":\"" + payload + "\"}";
        }
    }

    /**
     * Parses a JSON string into a TcpPacket object.
     */
    public static TcpPacket fromJson(String jsonStr) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject obj = new JSONObject(jsonStr);
            String id = obj.optString("id", UUID.randomUUID().toString().substring(0, 8));
            String typeStr = obj.optString("type", Type.DATA.name());
            Type type;
            try {
                type = Type.valueOf(typeStr);
            } catch (Exception e) {
                type = Type.DATA;
            }
            String sender = obj.optString("sender", "unknown");
            String recipient = obj.optString("recipient", "all");
            String payload = obj.optString("payload", "");
            long ts = obj.optLong("timestamp", System.currentTimeMillis());
            return new TcpPacket(id, type, sender, recipient, payload, ts);
        } catch (JSONException e) {
            // Raw text fallback
            return new TcpPacket(Type.DATA, "raw", "all", jsonStr);
        }
    }
}
