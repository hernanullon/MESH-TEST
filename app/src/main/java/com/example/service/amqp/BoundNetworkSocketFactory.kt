package com.example.service.amqp

import android.net.Network
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SSLSocketFactory

/**
 * SocketFactory that binds every created socket directly to a specific Android Network interface
 * (e.g. Cellular SIM or Wi-Fi station) before connection, preventing routing leaks across Hotspot/Mesh.
 */
class BoundNetworkSocketFactory(
    private val network: Network,
    private val timeoutMs: Int = 8000,
    private val sslSocketFactory: SSLSocketFactory? = null
) : SocketFactory() {

    private val baseFactory: SocketFactory = sslSocketFactory ?: SocketFactory.getDefault()

    override fun createSocket(): Socket {
        val socket = baseFactory.createSocket()
        bindSafely(socket)
        return socket
    }

    override fun createSocket(host: String, port: Int): Socket {
        val socket = baseFactory.createSocket()
        bindSafely(socket)
        val resolved = resolveHostSafely(host)
        socket.connect(InetSocketAddress(resolved, port), timeoutMs)
        return socket
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        val socket = baseFactory.createSocket()
        bindSafely(socket)
        socket.bind(InetSocketAddress(localHost, localPort))
        val resolved = resolveHostSafely(host)
        socket.connect(InetSocketAddress(resolved, port), timeoutMs)
        return socket
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        val socket = baseFactory.createSocket()
        bindSafely(socket)
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        return socket
    }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        val socket = baseFactory.createSocket()
        bindSafely(socket)
        socket.bind(InetSocketAddress(localAddress, localPort))
        socket.connect(InetSocketAddress(address, port), timeoutMs)
        return socket
    }

    private fun bindSafely(socket: Socket) {
        try {
            network.bindSocket(socket)
        } catch (ignored: Exception) {}
    }

    private fun resolveHostSafely(host: String): InetAddress {
        return try {
            network.getByName(host)
        } catch (e: Exception) {
            InetAddress.getByName(host)
        }
    }
}
