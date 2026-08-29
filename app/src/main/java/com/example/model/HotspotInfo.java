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

    public HotspotInfo(State state, String ssid, String passphrase, String ipAddress, String errorMessage, long startedAt) {
        this.state = state;
        this.ssid = ssid != null ? ssid : "";
        this.passphrase = passphrase != null ? passphrase : "";
        this.ipAddress = ipAddress != null ? ipAddress : "192.168.43.1";
        this.errorMessage = errorMessage != null ? errorMessage : "";
        this.startedAt = startedAt;
    }

    public static HotspotInfo disabled() {
        return new HotspotInfo(State.DISABLED, "", "", "", "", 0);
    }

    public static HotspotInfo starting() {
        return new HotspotInfo(State.STARTING, "Starting...", "", "", "", System.currentTimeMillis());
    }

    public static HotspotInfo running(String ssid, String passphrase, String ipAddress) {
        return new HotspotInfo(State.RUNNING, ssid, passphrase, ipAddress, "", System.currentTimeMillis());
    }

    public static HotspotInfo failed(String error) {
        return new HotspotInfo(State.FAILED, "", "", "", error, 0);
    }

    public State getState() {
        return state;
    }

    public boolean isRunning() {
        return state == State.RUNNING;
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
}
