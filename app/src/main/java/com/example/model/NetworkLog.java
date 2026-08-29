package com.example.model;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Model representing a network or system log event.
 */
public class NetworkLog {
    public enum Level {
        INFO,
        SUCCESS,
        WARN,
        ERROR,
        DEBUG
    }

    private final long timestamp;
    private final Level level;
    private final String tag;
    private final String message;

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    public NetworkLog(Level level, String tag, String message) {
        this.timestamp = System.currentTimeMillis();
        this.level = level;
        this.tag = tag;
        this.message = message;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getFormattedTime() {
        return TIME_FORMAT.format(new Date(timestamp));
    }

    public Level getLevel() {
        return level;
    }

    public String getTag() {
        return tag;
    }

    public String getMessage() {
        return message;
    }
}
