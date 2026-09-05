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
        // Collect full exception chain messages to inspect both top-level and causes
        val chain = mutableListOf<Throwable>()
        var curr: Throwable? = t
        while (curr != null && !chain.contains(curr)) {
            chain.add(curr)
            curr = curr.cause
        }

        val allMessages = chain.joinToString(" ") { it.message ?: it.javaClass.simpleName }.lowercase()
        val root = chain.last()
        val rootMsg = (root.message ?: root.javaClass.simpleName).lowercase()

        // --- 1. SMARTPHONE MOBILE NETWORK / INTERNET ISSUES ---

        // A. TIMEOUT: Sockets waiting 8000ms / 10000ms with no answer from carrier or internet
        if (chain.any { it is SocketTimeoutException } ||
            allMessages.contains("timed out") ||
            allMessages.contains("after 8000ms") ||
            allMessages.contains("after 10000ms") ||
            allMessages.contains("timeout")) {
            return "[Mobile Network] No Internet access (Connection timed out - check mobile data plan/balance)"
        }

        // B. DNS / Hostname resolution failure
        if (chain.any { it is UnknownHostException } || allMessages.contains("unknownhost")) {
            return "[Mobile Network] No Internet access (Cannot resolve broker host)"
        }

        // C. Network or Host Unreachable
        if (chain.any { it is NoRouteToHostException } ||
            allMessages.contains("enetunreach") ||
            allMessages.contains("network is unreachable") ||
            allMessages.contains("ehostunreach") ||
            allMessages.contains("no route to host")) {
            return "[Mobile Network] Cellular network unreachable (No Internet route)"
        }

        // D. Cellular interface binding failure
        if (allMessages.contains("bind failed") || allMessages.contains("ebadf")) {
            return "[Mobile Network] Failed to bind to cellular interface"
        }

        // --- 2. BROKER / RABBITMQ ISSUES (Internet exists, but broker server rejects/fails) ---

        // A. TCP RST / Connection Refused (Only when explicit refusal occurs, NOT timeout)
        if (chain.any { it is ConnectException } || allMessages.contains("connection refused") || allMessages.contains("econnrefused")) {
            // Note: If ConnectException contained "timed out", it is already caught above!
            return "[Broker Error] Server unreachable or port closed"
        }

        // B. AMQP Authentication / Credentials
        if (allMessages.contains("possibleauthenticationfailure") ||
            allMessages.contains("authentication") ||
            allMessages.contains("access_refused") ||
            allMessages.contains("530")) {
            return "[Broker Error] Invalid username or password"
        }

        // C. Virtual host error
        if (allMessages.contains("vhost") || allMessages.contains("not_allowed") || allMessages.contains("not allowed")) {
            return "[Broker Error] Virtual host not found or unauthorized"
        }

        // D. Exchange error
        if (allMessages.contains("no exchange") || allMessages.contains("not_found") || allMessages.contains("404")) {
            return "[Broker Error] Exchange not found on server"
        }

        // E. SSL / TLS handshake
        if (allMessages.contains("ssl") || allMessages.contains("handshake") || allMessages.contains("certificate")) {
            return "[Broker Error] SSL/TLS handshake failed"
        }

        // F. Socket closed / reset by server
        if (allMessages.contains("reset by peer") || allMessages.contains("econnreset") || allMessages.contains("broken pipe")) {
            return "[Broker Error] Connection reset by server"
        }

        // Fallback: If contains socket/network keyword, label as mobile network; otherwise broker
        return if (allMessages.contains("socket") || allMessages.contains("network") || allMessages.contains("carrier")) {
            "[Mobile Network] Cellular network error: ${root.message ?: root.javaClass.simpleName}"
        } else {
            "[Broker Error] Connection failed: ${root.message ?: root.javaClass.simpleName}"
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
