package com.moiseev.onkyoremote.network

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

object OnkyoDiscovery {
    private const val TAG = "OnkyoEiscp"

    /** Queries the receiver identity directly, equivalent to python-eiscp's receiver.info. */
    fun getInfo(host: String, timeoutMs: Int = 1500): ReceiverInfo? {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val request = EiscpProtocol.packet("ECNQSTN", destination = 'x')
            val target = InetAddress.getByName(host)
            return try {
                Log.d(TAG, "Info TX $host:60128 command='!xECNQSTN\\r'")
                socket.send(DatagramPacket(request, request.size, target, 60128))

                val buffer = ByteArray(2048)
                val reply = DatagramPacket(buffer, buffer.size)
                socket.receive(reply)
                val body = parseDatagram(reply.data.copyOf(reply.length))
                val replyHost = reply.address.hostAddress ?: host
                Log.d(TAG, "Info RX from $replyHost bytes=${reply.length} body='${EiscpProtocol.debugText(body)}'")
                parseDiscoveryReply(body, replyHost).also { info ->
                    if (info == null) Log.w(TAG, "Direct info response was not recognized: '${EiscpProtocol.debugText(body)}'")
                    else Log.i(TAG, "Direct info parsed: $info")
                }
            } catch (e: SocketTimeoutException) {
                Log.w(TAG, "Info query timed out for $host after ${timeoutMs}ms")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Info query failed for $host", e)
                null
            }
        }
    }

    fun discover(timeoutMs: Int = 1500): List<ReceiverInfo> {
        val found = linkedMapOf<String, ReceiverInfo>()
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 250
            val request = EiscpProtocol.packet("ECNQSTN", destination = 'x')
            val broadcast = InetAddress.getByName("255.255.255.255")
            try {
                Log.d(TAG, "Discovery TX 255.255.255.255:60128 command='!xECNQSTN\\r'")
                socket.send(DatagramPacket(request, request.size, broadcast, 60128))
            } catch (e: Exception) {
                Log.e(TAG, "Discovery send failed", e)
                return emptyList()
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val buf = ByteArray(2048)
                    val reply = DatagramPacket(buf, buf.size)
                    socket.receive(reply)
                    val bytes = reply.data.copyOf(reply.length)
                    val body = parseDatagram(bytes)
                    val host = reply.address.hostAddress ?: continue
                    Log.d(TAG, "Discovery RX from $host bytes=${reply.length} body='${EiscpProtocol.debugText(body)}'")
                    parseDiscoveryReply(body, host)?.let {
                        Log.i(TAG, "Discovery model parsed: $it")
                        found[it.identifier.ifBlank { it.host }] = it
                    } ?: Log.w(TAG, "Discovery response was not recognized: host=$host body='${EiscpProtocol.debugText(body)}'")
                } catch (_: SocketTimeoutException) {
                    // Continue until overall discovery timeout expires.
                }
            }
        }
        return found.values.toList()
    }

    private fun parseDatagram(bytes: ByteArray): String {
        if (bytes.size < 16 || String(bytes, 0, 4, Charsets.US_ASCII) != "ISCP") return ""
        val dataSize = java.nio.ByteBuffer.wrap(bytes, 8, 4).order(java.nio.ByteOrder.BIG_ENDIAN).int
        if (dataSize <= 0 || 16 + dataSize > bytes.size) return ""
        return String(bytes, 16, dataSize, Charsets.US_ASCII).trimEnd('\u001a', '\r', '\n', '\u0000')
    }

    private fun parseDiscoveryReply(body: String, host: String): ReceiverInfo? {
        // Typical reply: !1ECNTX-NR609/60128/DX/xxxxxxxxxxxx
        val m = Regex("![0-9A-Za-z]ECN([^/]+)/([0-9]{1,5})/([^/]*)(?:/(.*))?").find(body) ?: return null
        return ReceiverInfo(
            host = host,
            port = m.groupValues[2].toIntOrNull() ?: 60128,
            model = m.groupValues[1],
            model_name = m.groupValues[1],
            area = m.groupValues[3],
            identifier = m.groupValues.getOrElse(4) { "" }
        )
    }
}
