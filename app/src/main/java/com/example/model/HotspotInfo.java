package com.example.model;

/**
 * Encapsulates the runtime status and credentials of the Local Wi-Fi Hotspot.
 */
public class HotspotInfo {
    public enum State {
        DISABLED,
        STARTING,
        RUNNING,
        FAILED
    }

    private final State state;
    private final String ssid;
    private final String passphrase;
    private final String ipAddress;
    private final String errorMessage;
    private final long startedAt;
    private final String band;

    public HotspotInfo(State state, String ssid, String passphrase, String ipAddress, String errorMessage, long startedAt, String band) {
        this.state = state;
        this.ssid = ssid != null ? ssid : "";
        this.passphrase = passphrase != null ? passphrase : "";
        this.ipAddress = ipAddress != null ? ipAddress : "192.168.43.1";
        this.errorMessage = errorMessage != null ? errorMessage : "";
        this.startedAt = startedAt;
        this.band = band != null ? band : "2.4 GHz";
    }

    public HotspotInfo(State state, String ssid, String passphrase, String ipAddress, String errorMessage, long startedAt) {
        this(state, ssid, passphrase, ipAddress, errorMessage, startedAt, "2.4 GHz");
    }

    public static HotspotInfo disabled() {
        return new HotspotInfo(State.DISABLED, "", "", "", "", 0, "2.4 GHz");
    }

    public static HotspotInfo starting() {
        return new HotspotInfo(State.STARTING, "Starting...", "", "", "", System.currentTimeMillis(), "2.4 GHz");
    }

    public static HotspotInfo running(String ssid, String passphrase, String ipAddress) {
        return new HotspotInfo(State.RUNNING, ssid, passphrase, ipAddress, "", System.currentTimeMillis(), "2.4 GHz");
    }

    public static HotspotInfo running(String ssid, String passphrase, String ipAddress, String band) {
        return new HotspotInfo(State.RUNNING, ssid, passphrase, ipAddress, "", System.currentTimeMillis(), band);
    }

    public static HotspotInfo failed(String error) {
        return new HotspotInfo(State.FAILED, "", "", "", error, 0, "2.4 GHz");
    }

    public State getState() {
        return state;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
    }

    public boolean isEnabled() {
        return state == State.RUNNING || state == State.STARTING;
    }

    public String getSsid() {
        return ssid;
    }

    public String getPassphrase() {
        return passphrase;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public String getBand() {
        return band != null ? band : "2.4 GHz";
    }

    public boolean is5Ghz() {
        return band != null && band.contains("5 GHz");
    }

    public boolean is24Ghz() {
        return band != null && band.contains("2.4 GHz");
    }
}
