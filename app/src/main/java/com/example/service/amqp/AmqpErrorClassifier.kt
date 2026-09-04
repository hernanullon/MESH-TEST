package com.example.service.amqp

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Categorizes and translates low-level network/socket/AMQP exceptions into concise,
 * user-friendly diagnostic messages in English, cleanly separating Mobile Network issues
 * from Broker/Server failures.
 */
object AmqpErrorClassifier {

    /**
     * Translates exceptions occurring during Cellular Real-Time AMQP streaming.
     */
    fun classifyRealtimeError(t: Throwable): String {
        val root = getRootCause(t)
        val msg = root.message ?: root.javaClass.simpleName
        val msgLower = msg.lowercase()

        // --- 1. SMARTPHONE MOBILE NETWORK / INTERNET ISSUES ---

        // Specific user scenario: Mobile data on, SIM active with IP, but no credit/plan or carrier firewall (connection timeout)
        if (root is SocketTimeoutException || msgLower.contains("timed out") || msgLower.contains("after 8000ms") || msgLower.contains("after 10000ms")) {
            return "[Mobile Network] No Internet access (Connection timed out - check mobile data plan/balance)"
        }

        // Hostname / DNS resolution failure (no cellular internet or invalid host)
        if (root is UnknownHostException) {
            return "[Mobile Network] No Internet access (Cannot resolve broker host)"
        }

        // Network unreachable or route unavailable
        if (root is NoRouteToHostException || msgLower.contains("enetunreach") || msgLower.contains("network is unreachable")) {
            return "[Mobile Network] Cellular network unreachable"
        }

        // Carrier dropped route or route not found
        if (msgLower.contains("ehostunreach") || msgLower.contains("no route to host")) {
            return "[Mobile Network] No Internet route on mobile data"
        }

        // Socket binding error on cellular interface
        if (msgLower.contains("bind failed") || msgLower.contains("ebadf")) {
            return "[Mobile Network] Failed to bind to cellular interface"
        }

        // --- 2. BROKER / RABBITMQ ISSUES (Internet is reachable, but broker fails) ---

        // Port closed or service down on server (TCP RST returned by server/firewall)
        if (root is ConnectException || msgLower.contains("connection refused") || msgLower.contains("econnrefused")) {
            return "[Broker Error] Server unreachable or port closed"
        }

        // AMQP Authentication failure (invalid credentials)
        if (msgLower.contains("possibleauthenticationfailure") ||
            msgLower.contains("authentication") ||
            msgLower.contains("access_refused") ||
            msgLower.contains("530")) {
            return "[Broker Error] Invalid username or password"
        }

        // Virtual host not found or unauthorized
        if (msgLower.contains("vhost") || msgLower.contains("not_allowed") || msgLower.contains("not allowed")) {
            return "[Broker Error] Virtual host not found or unauthorized"
        }

        // Exchange not found or invalid
        if (msgLower.contains("no exchange") || msgLower.contains("not_found") || msgLower.contains("404")) {
            return "[Broker Error] Exchange not found on server"
        }

        // SSL / TLS handshake failure
        if (msgLower.contains("ssl") || msgLower.contains("handshake") || msgLower.contains("certificate")) {
            return "[Broker Error] SSL/TLS handshake failed"
        }

        // Connection reset by peer (server closed socket)
        if (root is SocketException && (msgLower.contains("reset by peer") || msgLower.contains("econnreset") || msgLower.contains("broken pipe"))) {
            return "[Broker Error] Connection reset by server"
        }

        // Generic fallback with clean prefix
        return if (msgLower.contains("network") || msgLower.contains("socket") || msgLower.contains("route")) {
            "[Mobile Network] No Internet access: $msg"
        } else {
            "[Broker Error] Connection failed: $msg"
        }
    }

    /**
     * Translates exceptions occurring during Wi-Fi Batch Discharging.
     */
    fun classifyBatchError(t: Throwable): String {
        val root = getRootCause(t)
        val msg = root.message ?: root.javaClass.simpleName
        val msgLower = msg.lowercase()

        // Wi-Fi connectivity issues
        if (root is SocketTimeoutException || msgLower.contains("timed out")) {
            return "[Wi-Fi Network] Connection timed out (No Internet access via Wi-Fi)"
        }
        if (root is UnknownHostException) {
            return "[Wi-Fi Network] Cannot resolve host (Check Wi-Fi DNS/Internet)"
        }
        if (root is NoRouteToHostException || msgLower.contains("enetunreach") || msgLower.contains("network is unreachable")) {
            return "[Wi-Fi Network] Network unreachable"
        }

        // Broker issues over Wi-Fi
        if (root is ConnectException || msgLower.contains("connection refused") || msgLower.contains("econnrefused")) {
            return "[Broker Error] Server unreachable or port closed"
        }
        if (msgLower.contains("possibleauthenticationfailure") || msgLower.contains("access_refused") || msgLower.contains("530")) {
            return "[Broker Error] Invalid username or password"
        }
        if (msgLower.contains("not_found") || msgLower.contains("404")) {
            return "[Broker Error] Exchange or queue not found"
        }
        if (msgLower.contains("nack") || msgLower.contains("confirm timeout")) {
            return "[Broker Error] Publisher confirms timed out"
        }

        return "[Broker Error] $msg"
    }

    private fun getRootCause(throwable: Throwable): Throwable {
        var cause: Throwable = throwable
        while (cause.cause != null && cause.cause !== cause) {
            cause = cause.cause!!
        }
        return cause
    }
}
