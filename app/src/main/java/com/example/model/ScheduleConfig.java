package com.example.model;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Encapsulates the entire scheduling and network configuration for both Wi-Fi Hardware,
 * Local Hotspot and Fixed SSID/Password settings.
 */
public class ScheduleConfig {
    private boolean wifiScheduleEnabled;
    private final List<TimeRange> wifiRanges;

    private boolean hotspotScheduleEnabled;
    private final List<TimeRange> hotspotRanges;

    // Inverted Schedule Model:
    // User configures the OFF window for Local TCP Network (which is the ON window for Wi-Fi)
    // Complementary window is when Local TCP Network is ON (and Wi-Fi is OFF)
    private int offStartHour = 4;
    private int offStartMinute = 0;
    private int offEndHour = 5;
    private int offEndMinute = 30;

    // Fixed Network Credentials for Mesh Nodes
    private String customSsid = "Direct-Mesh-Master";
    private String customPassphrase = "MeshPassword123";
    private int tcpPort = 8888;

    public ScheduleConfig() {
        this.wifiScheduleEnabled = true;
        this.wifiRanges = new ArrayList<>();
        this.hotspotScheduleEnabled = true;
        this.hotspotRanges = new ArrayList<>();

        // Default: Red TCP Apagada / Wi-Fi ON from 04:00 to 05:30
        // Complement: Red TCP ON / Wi-Fi OFF from 00:00-03:59 and 05:31-23:59
        applyInvertedSchedule(4, 0, 5, 30);
    }

    /**
     * Sets the OFF window for Local TCP Network (and ON window for Wi-Fi),
     * and automatically calculates the complementary ON windows for Local TCP Network.
     */
    public synchronized void applyInvertedSchedule(int offStartH, int offStartM, int offEndH, int offEndM) {
        this.offStartHour = offStartH;
        this.offStartMinute = offStartM;
        this.offEndHour = offEndH;
        this.offEndMinute = offEndM;

        this.hotspotRanges.clear();
        this.wifiRanges.clear();

        // 1. Wi-Fi Active Range = The configured window
        this.wifiRanges.add(new TimeRange(offStartH, offStartM, offEndH, offEndM, "Wi-Fi (Prioritario)"));

        // 2. Hotspot / TCP Active Ranges = Exact complement
        int startTotalMins = offStartH * 60 + offStartM;
        int endTotalMins = offEndH * 60 + offEndM;

        if (startTotalMins < endTotalMins) {
            // Same-day window (e.g., 04:00 to 05:30)
            if (startTotalMins > 0) {
                int preEndTotal = startTotalMins - 1;
                int preEndH = preEndTotal / 60;
                int preEndM = preEndTotal % 60;
                this.hotspotRanges.add(new TimeRange(0, 0, preEndH, preEndM, "Red TCP (Madrugada)"));
            }

            if (endTotalMins < (23 * 60 + 59)) {
                int postStartTotal = endTotalMins + 1;
                int postStartH = postStartTotal / 60;
                int postStartM = postStartTotal % 60;
                this.hotspotRanges.add(new TimeRange(postStartH, postStartM, 23, 59, "Red TCP (Resto del Día)"));
            }
        } else if (startTotalMins > endTotalMins) {
            // Overnight window crossing midnight (e.g., 22:00 to 04:00)
            int compStartTotal = endTotalMins + 1;
            int compEndTotal = startTotalMins - 1;
            if (compStartTotal <= compEndTotal) {
                int compStartH = compStartTotal / 60;
                int compStartM = compStartTotal % 60;
                int compEndH = compEndTotal / 60;
                int compEndM = compEndTotal % 60;
                this.hotspotRanges.add(new TimeRange(compStartH, compStartM, compEndH, compEndM, "Red TCP (Día)"));
            }
        } else {
            // Edge case: start == end (0 duration)
            this.hotspotRanges.add(new TimeRange(0, 0, 23, 59, "Red TCP (Completo)"));
        }
    }

    public int getOffStartHour() { return offStartHour; }
    public int getOffStartMinute() { return offStartMinute; }
    public int getOffEndHour() { return offEndHour; }
    public int getOffEndMinute() { return offEndMinute; }

    public String getCustomSsid() {
        return (customSsid == null || customSsid.trim().isEmpty()) ? "Direct-Mesh-Master" : customSsid.trim();
    }

    public void setCustomSsid(String customSsid) {
        if (customSsid != null && !customSsid.trim().isEmpty()) {
            this.customSsid = customSsid.trim();
        }
    }

    public String getCustomPassphrase() {
        return (customPassphrase == null || customPassphrase.trim().isEmpty()) ? "MeshPassword123" : customPassphrase.trim();
    }

    public void setCustomPassphrase(String customPassphrase) {
        if (customPassphrase != null && !customPassphrase.trim().isEmpty()) {
            this.customPassphrase = customPassphrase.trim();
        }
    }

    public int getTcpPort() { return tcpPort > 0 ? tcpPort : 8888; }
    public void setTcpPort(int tcpPort) {
        if (tcpPort >= 1024 && tcpPort <= 65535) {
            this.tcpPort = tcpPort;
        }
    }

    public String getConfiguredOffWindowFormatted() {
        return String.format(java.util.Locale.US, "%02d:%02d a %02d:%02d", offStartHour, offStartMinute, offEndHour, offEndMinute);
    }

    public String getHotspotScheduleFormatted() {
        if (hotspotRanges.isEmpty()) {
            return "Desactivado";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hotspotRanges.size(); i++) {
            if (i > 0) sb.append(" y ");
            sb.append(hotspotRanges.get(i).getFormattedRange());
        }
        return sb.toString();
    }

    public String getWifiScheduleFormatted() {
        if (wifiRanges.isEmpty()) {
            return "Desactivado";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < wifiRanges.size(); i++) {
            if (i > 0) sb.append(" y ");
            sb.append(wifiRanges.get(i).getFormattedRange());
        }
        return sb.toString();
    }

    public boolean isWifiScheduleEnabled() {
        return wifiScheduleEnabled;
    }

    public void setWifiScheduleEnabled(boolean wifiScheduleEnabled) {
        this.wifiScheduleEnabled = wifiScheduleEnabled;
    }

    public List<TimeRange> getWifiRanges() {
        return Collections.unmodifiableList(wifiRanges);
    }

    public synchronized void setWifiRanges(List<TimeRange> ranges) {
        this.wifiRanges.clear();
        if (ranges != null) {
            this.wifiRanges.addAll(ranges);
        }
    }

    public synchronized void addWifiRange(TimeRange range) {
        if (range != null) {
            this.wifiRanges.add(range);
        }
    }

    public synchronized boolean removeWifiRange(String rangeId) {
        return this.wifiRanges.removeIf(r -> r.getId().equals(rangeId));
    }

    public boolean isHotspotScheduleEnabled() {
        return hotspotScheduleEnabled;
    }

    public void setHotspotScheduleEnabled(boolean hotspotScheduleEnabled) {
        this.hotspotScheduleEnabled = hotspotScheduleEnabled;
    }

    public List<TimeRange> getHotspotRanges() {
        return Collections.unmodifiableList(hotspotRanges);
    }

    public synchronized void setHotspotRanges(List<TimeRange> ranges) {
        this.hotspotRanges.clear();
        if (ranges != null) {
            this.hotspotRanges.addAll(ranges);
        }
    }

    public synchronized void addHotspotRange(TimeRange range) {
        if (range != null) {
            this.hotspotRanges.add(range);
        }
    }

    public synchronized boolean removeHotspotRange(String rangeId) {
        return this.hotspotRanges.removeIf(r -> r.getId().equals(rangeId));
    }

    public boolean shouldWifiBeActive(int hour, int minute) {
        if (!wifiScheduleEnabled) {
            return true;
        }
        for (TimeRange range : wifiRanges) {
            if (range.isInside(hour, minute)) {
                return true;
            }
        }
        return false;
    }

    public boolean shouldHotspotBeActive(int hour, int minute) {
        if (!hotspotScheduleEnabled) {
            return true;
        }
        for (TimeRange range : hotspotRanges) {
            if (range.isInside(hour, minute)) {
                return true;
            }
        }
        return false;
    }

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("wifiScheduleEnabled", wifiScheduleEnabled);
            root.put("hotspotScheduleEnabled", hotspotScheduleEnabled);
            root.put("offStartHour", offStartHour);
            root.put("offStartMinute", offStartMinute);
            root.put("offEndHour", offEndHour);
            root.put("offEndMinute", offEndMinute);
            root.put("customSsid", customSsid);
            root.put("customPassphrase", customPassphrase);
            root.put("tcpPort", tcpPort);

            JSONArray wifiArr = new JSONArray();
            for (TimeRange r : wifiRanges) {
                wifiArr.put(r.toJson());
            }
            root.put("wifiRanges", wifiArr);

            JSONArray hotspotArr = new JSONArray();
            for (TimeRange r : hotspotRanges) {
                hotspotArr.put(r.toJson());
            }
            root.put("hotspotRanges", hotspotArr);

            return root.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public static ScheduleConfig fromJson(String jsonStr) {
        ScheduleConfig config = new ScheduleConfig();
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            return config;
        }

        try {
            JSONObject root = new JSONObject(jsonStr);
            config.setWifiScheduleEnabled(root.optBoolean("wifiScheduleEnabled", true));
            config.setHotspotScheduleEnabled(root.optBoolean("hotspotScheduleEnabled", true));
            int sH = root.optInt("offStartHour", root.optInt("hotspotStartHour", 4));
            int sM = root.optInt("offStartMinute", root.optInt("hotspotStartMinute", 0));
            int eH = root.optInt("offEndHour", root.optInt("hotspotEndHour", 5));
            int eM = root.optInt("offEndMinute", root.optInt("hotspotEndMinute", 30));
            config.applyInvertedSchedule(sH, sM, eH, eM);

            if (root.has("customSsid")) {
                config.setCustomSsid(root.getString("customSsid"));
            }
            if (root.has("customPassphrase")) {
                config.setCustomPassphrase(root.getString("customPassphrase"));
            }
            if (root.has("tcpPort")) {
                config.setTcpPort(root.optInt("tcpPort", 8888));
            }

            if (root.has("wifiRanges")) {
                JSONArray wifiArr = root.optJSONArray("wifiRanges");
                if (wifiArr != null && wifiArr.length() > 0) {
                    List<TimeRange> list = new ArrayList<>();
                    for (int i = 0; i < wifiArr.length(); i++) {
                        TimeRange r = TimeRange.fromJson(wifiArr.getJSONObject(i));
                        if (r != null) list.add(r);
                    }
                    config.setWifiRanges(list);
                }
            }

            if (root.has("hotspotRanges")) {
                JSONArray hotspotArr = root.optJSONArray("hotspotRanges");
                if (hotspotArr != null && hotspotArr.length() > 0) {
                    List<TimeRange> list = new ArrayList<>();
                    for (int i = 0; i < hotspotArr.length(); i++) {
                        TimeRange r = TimeRange.fromJson(hotspotArr.getJSONObject(i));
                        if (r != null) list.add(r);
                    }
                    config.setHotspotRanges(list);
                }
            }
        } catch (Exception ignored) {}

        return config;
    }
}
