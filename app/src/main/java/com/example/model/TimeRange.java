package com.example.model;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.Locale;
import java.util.UUID;

/**
 * Represents a configurable time interval [startTime - endTime] for module power scheduling.
 */
public class TimeRange {
    private String id;
    private int startHour;
    private int startMinute;
    private int endHour;
    private int endMinute;
    private boolean enabled;
    private String label;

    public TimeRange(int startHour, int startMinute, int endHour, int endMinute, String label) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.startHour = startHour;
        this.startMinute = startMinute;
        this.endHour = endHour;
        this.endMinute = endMinute;
        this.enabled = true;
        this.label = label != null ? label : "";
    }

    public TimeRange(String id, int startHour, int startMinute, int endHour, int endMinute, boolean enabled, String label) {
        this.id = id;
        this.startHour = startHour;
        this.startMinute = startMinute;
        this.endHour = endHour;
        this.endMinute = endMinute;
        this.enabled = enabled;
        this.label = label != null ? label : "";
    }

    public String getId() {
        return id;
    }

    public int getStartHour() {
        return startHour;
    }

    public int getStartMinute() {
        return startMinute;
    }

    public int getEndHour() {
        return endHour;
    }

    public int getEndMinute() {
        return endMinute;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public void setStart(int hour, int minute) {
        this.startHour = hour;
        this.startMinute = minute;
    }

    public void setEnd(int hour, int minute) {
        this.endHour = hour;
        this.endMinute = minute;
    }

    public String getFormattedRange() {
        return String.format(Locale.US, "%02d:%02d - %02d:%02d", startHour, startMinute, endHour, endMinute);
    }

    /**
     * Checks if the given hour and minute fall inside this time range.
     * Supports both standard same-day intervals (e.g., 04:00 - 06:00)
     * and overnight intervals crossing midnight (e.g., 22:00 - 04:00).
     */
    public boolean isInside(int hour, int minute) {
        if (!enabled) {
            return false;
        }

        int currentMins = hour * 60 + minute;
        int startMins = startHour * 60 + startMinute;
        int endMins = endHour * 60 + endMinute;

        if (startMins <= endMins) {
            // Normal interval in the same day (e.g. 04:00 to 06:00)
            return currentMins >= startMins && currentMins <= endMins;
        } else {
            // Interval spanning across midnight (e.g. 22:00 to 04:00)
            return currentMins >= startMins || currentMins <= endMins;
        }
    }

    public boolean isInside(Calendar cal) {
        return isInside(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("startHour", startHour);
            obj.put("startMinute", startMinute);
            obj.put("endHour", endHour);
            obj.put("endMinute", endMinute);
            obj.put("enabled", enabled);
            obj.put("label", label);
        } catch (JSONException ignored) {}
        return obj;
    }

    public static TimeRange fromJson(JSONObject obj) {
        try {
            String id = obj.optString("id", UUID.randomUUID().toString().substring(0, 8));
            int startHour = obj.getInt("startHour");
            int startMinute = obj.getInt("startMinute");
            int endHour = obj.getInt("endHour");
            int endMinute = obj.getInt("endMinute");
            boolean enabled = obj.optBoolean("enabled", true);
            String label = obj.optString("label", "");
            return new TimeRange(id, startHour, startMinute, endHour, endMinute, enabled, label);
        } catch (Exception e) {
            return null;
        }
    }
}
