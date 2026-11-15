// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.e2e

import android.system.Os
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * A public server list of this test's own instead of the real list service, which the tests
 * never contact. The engine takes a list from `MUMCORD_PUBLIC_SERVERS_URL` and then contacts
 * nothing but this device's own loopback address, so the list is served inside the emulator, on
 * 127.0.0.1, and its one real entry (the local test server) is a loopback address too: a relay
 * there passes the ping (UDP) and the connection (TCP) on to the test server on the host, and to
 * nothing else. Only debug test code sets the variable; the app itself has no way to change the
 * list's address.
 *
 * Put it after [RequireTestServer] and before the rule that starts the app: the engine reads the
 * variable once, when the app creates it.
 */
class LocalPublicList(private val requireServer: RequireTestServer) : TestRule {
    private var list: ServerSocket? = null

    /** The port of the test server's entry in the list, on 127.0.0.1. */
    val relayPort: Int
        get() = requireServer.server.port

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val target = InetSocketAddress(requireServer.server.host, requireServer.server.port)
            val opened = mutableListOf<Closeable>()
            try {
                val served = serve(document(relayPort)).also { list = it }
                opened += served
                opened += TcpRelay(relayPort, target)
                opened += UdpRelay(relayPort, target)
                Os.setenv(URL_VARIABLE, "http://127.0.0.1:${served.localPort}/v1/list", true)
                base.evaluate()
            } finally {
                Os.unsetenv(URL_VARIABLE)
                opened.forEach { runCatching { it.close() } }
            }
        }
    }

    /** Makes the list unreachable: the next download is refused, as with a list service that is down. */
    fun takeDown() {
        list?.close()
    }

    /** Answers every request on its socket with [body], until the socket is closed. */
    private fun serve(body: String): ServerSocket {
        val socket = ServerSocket(0, BACKLOG, LOOPBACK)
        val bytes = body.toByteArray()
        thread(name = "e2e-public-list", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { connection ->
                    val reader = connection.getInputStream().bufferedReader()
                    // The request itself does not matter; read it up to its empty line.
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    connection.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 200 OK\r\nContent-Type: application/xml\r\n" +
                                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                ).toByteArray(),
                        )
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        return socket
    }

    /** Passes every connection to [port] on to [target], byte for byte in both directions. */
    private class TcpRelay(port: Int, private val target: SocketAddress) : Closeable {
        private val server = ServerSocket(port, BACKLOG, LOOPBACK)
        private val open = ConcurrentHashMap.newKeySet<Socket>()

        init {
            thread(name = "e2e-tcp-relay", isDaemon = true) {
                while (!server.isClosed) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    val upstream = runCatching { Socket().apply { connect(target, CONNECT_TIMEOUT_MILLIS) } }.getOrNull()
                    if (upstream == null) {
                        // The test server is down: the app sees a refused connection, as it would.
                        client.close()
                        continue
                    }
                    open += client
                    open += upstream
                    pump(client, upstream)
                    pump(upstream, client)
                }
            }
        }

        private fun pump(from: Socket, to: Socket) = thread(name = "e2e-tcp-relay-pump", isDaemon = true) {
            runCatching { from.getInputStream().copyTo(to.getOutputStream()) }
            runCatching { to.shutdownOutput() }
        }

        override fun close() {
            server.close()
            open.forEach { runCatching { it.close() } }
        }
    }

    /** Passes every datagram to [port] on to [target], and the answers back to whoever sent it. */
    private class UdpRelay(port: Int, private val target: SocketAddress) : Closeable {
        private val socket = DatagramSocket(InetSocketAddress(LOOPBACK, port))
        private val upstreams = ConcurrentHashMap<SocketAddress, DatagramSocket>()

        init {
            thread(name = "e2e-udp-relay", isDaemon = true) {
                val buffer = ByteArray(DATAGRAM_BYTES)
                while (!socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    if (runCatching { socket.receive(packet) }.isFailure) break
                    val upstream = upstreams.getOrPut(packet.socketAddress) { answerTo(packet.socketAddress) }
                    runCatching { upstream.send(DatagramPacket(packet.data, packet.offset, packet.length, target)) }
                }
            }
        }

        private fun answerTo(client: SocketAddress): DatagramSocket {
            val upstream = DatagramSocket()
            thread(name = "e2e-udp-relay-back", isDaemon = true) {
                val buffer = ByteArray(DATAGRAM_BYTES)
                while (!upstream.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    if (runCatching { upstream.receive(packet) }.isFailure) break
                    runCatching { socket.send(DatagramPacket(packet.data, packet.offset, packet.length, client)) }
                }
            }
            return upstream
        }

        override fun close() {
            socket.close()
            upstreams.values.forEach { it.close() }
        }
    }

    companion object {
        const val URL_VARIABLE = "MUMCORD_PUBLIC_SERVERS_URL"

        /** IPv4 on purpose: Android's own loopback address is ::1, the list's entries are 127.0.0.1. */
        private val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

        /** The entry of the local test server, the only one that answers. */
        const val TEST_SERVER_NAME = "Mumcord E2E Test Server"

        /** A name with markup in it: the browser must show it as it is, never as markup. */
        const val MARKUP_NAME = "<b>Bold</b> Club"

        private const val BACKLOG = 8
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val DATAGRAM_BYTES = 2048

        /**
         * The list in the format of the real list service. Besides the test server, every entry
         * is a closed port of this device's loopback address, so its ping gets no answer and
         * nothing outside the device is ever contacted.
         */
        fun document(relayPort: Int): String {
            val others = listOf(
                Triple("Alpine Lounge", "Switzerland", "CH"),
                Triple("Berlin Gaming", "Germany", "DE"),
                Triple("Quiet Harbor", "Germany", "DE"),
                Triple(MARKUP_NAME, "Sweden", "SE"),
            ).mapIndexed { index, (name, country, code) ->
                entry(name, relayPort + index + 1, country, code)
            }
            return (listOf("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", "<servers>", entry(TEST_SERVER_NAME, relayPort, "Netherlands", "NL")) + others + "</servers>")
                .joinToString("\n")
        }

        private fun entry(name: String, port: Int, country: String, code: String): String {
            val escaped = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            return "  <server name=\"$escaped\" country=\"$country\" country_code=\"$code\" ip=\"127.0.0.1\" port=\"$port\" />"
        }
    }
}
