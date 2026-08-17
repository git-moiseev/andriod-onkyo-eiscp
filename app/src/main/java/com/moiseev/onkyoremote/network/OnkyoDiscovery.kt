package com.moiseev.onkyoremote.network

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

object OnkyoDiscovery {
    private const val TAG = "OnkyoEiscp"

    data class DiscoveryInterface(
        val id: String,
        val label: String,
        val address: InetAddress,
        val broadcast: InetAddress,
        val prefixLength: Short
    )

    fun availableInterfaces(): List<DiscoveryInterface> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { network ->
                network.interfaceAddresses.mapNotNull { interfaceAddress ->
                    val address = interfaceAddress.address
                    val broadcast = interfaceAddress.broadcast
                    if (address !is Inet4Address || broadcast !is Inet4Address) null
                    else DiscoveryInterface(
                        id = network.name,
                        label = "${friendlyInterfaceName(network.name)} (${network.name}) — ${address.hostAddress}",
                        address = address,
                        broadcast = broadcast,
                        prefixLength = interfaceAddress.networkPrefixLength
                    )
                }
            }
            .sortedBy { it.label }
    } catch (e: Exception) {
        Log.e(TAG, "Unable to enumerate discovery interfaces", e)
        emptyList()
    }

    private fun friendlyInterfaceName(name: String): String = when {
        name.startsWith("wlan", ignoreCase = true) -> "Wi-Fi"
        name.startsWith("eth", ignoreCase = true) -> "Ethernet"
        name.startsWith("usb", ignoreCase = true) -> "USB network"
        else -> "Network"
    }

    private fun selectedInterface(id: String?): DiscoveryInterface? =
        id?.takeIf(String::isNotBlank)?.let { requested -> availableInterfaces().firstOrNull { it.id == requested } }

    @Suppress("DEPRECATION")
    private fun bindToAndroidNetwork(context: Context, interfaceName: String, socket: DatagramSocket): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.allNetworks.firstOrNull {
            connectivity.getLinkProperties(it)?.interfaceName == interfaceName
        }
        if (network == null) {
            Log.w(TAG, "Android Network not found for interface $interfaceName")
            return false
        }
        return try {
            network.bindSocket(socket)
            Log.d(TAG, "Bound UDP socket to Android Network for $interfaceName")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Android Network binding denied for $interfaceName (${e.message}); using routed unicast fallback")
            false
        }
    }

    /** Queries the receiver identity directly, equivalent to python-eiscp's receiver.info. */
    fun getInfo(context: Context, host: String, timeoutMs: Int = 1500, interfaceId: String? = null): ReceiverInfo? {
        val selected = selectedInterface(interfaceId)
        if (!interfaceId.isNullOrBlank() && selected == null) {
            Log.w(TAG, "Info query interface is no longer available: $interfaceId")
            return null
        }
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            val networkBound = selected != null && bindToAndroidNetwork(context, selected.id, socket)
            socket.bind(InetSocketAddress(if (networkBound) selected?.address else null, 0))
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

    fun discover(context: Context, timeoutMs: Int = 1500, interfaceId: String? = null): List<ReceiverInfo> {
        val found = linkedMapOf<String, ReceiverInfo>()
        val selected = selectedInterface(interfaceId)
        if (!interfaceId.isNullOrBlank() && selected == null) {
            Log.w(TAG, "Discovery interface is no longer available: $interfaceId")
            return emptyList()
        }
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            val networkBound = selected != null && bindToAndroidNetwork(context, selected.id, socket)
            socket.bind(InetSocketAddress(if (networkBound) selected?.address else null, 0))
            socket.broadcast = true
            socket.soTimeout = 250
            val request = EiscpProtocol.packet("ECNQSTN", destination = 'x')
            val broadcast = selected?.broadcast ?: InetAddress.getByName("255.255.255.255")
            try {
                Log.d(TAG, "Discovery TX ${broadcast.hostAddress}:60128 via ${selected?.label ?: "system route"} command='!xECNQSTN\\r'")
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
        if (found.isEmpty() && selected != null) {
            found.putAll(discoverByUnicast(selected, timeoutMs))
        }
        return found.values.toList()
    }

    private fun discoverByUnicast(selected: DiscoveryInterface, timeoutMs: Int): Map<String, ReceiverInfo> {
        val prefix = selected.prefixLength.toInt()
        val hostCount = if (prefix in 22..30) 1L shl (32 - prefix) else {
            Log.w(TAG, "Skipping unicast fallback for ${selected.label}: unsupported /$prefix subnet size")
            return emptyMap()
        }
        val addressBytes = selected.address.address
        val addressValue = addressBytes.fold(0L) { value, byte -> (value shl 8) or (byte.toInt() and 0xFF).toLong() }
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = addressValue and mask
        val firstHost = network + 1
        val lastHost = network + hostCount - 2
        val request = EiscpProtocol.packet("ECNQSTN", destination = 'x')
        val found = linkedMapOf<String, ReceiverInfo>()

        DatagramSocket().use { socket ->
            socket.soTimeout = 100
            Log.i(TAG, "Broadcast produced no reply; probing ${lastHost - firstHost + 1} local addresses by unicast")
            for (value in firstHost..lastHost) {
                if (value == addressValue) continue
                val targetBytes = byteArrayOf(
                    (value ushr 24).toByte(), (value ushr 16).toByte(),
                    (value ushr 8).toByte(), value.toByte()
                )
                try {
                    socket.send(DatagramPacket(request, request.size, InetAddress.getByAddress(targetBytes), 60128))
                } catch (_: Exception) {
                    // Individual unreachable addresses are expected during a subnet probe.
                }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val buffer = ByteArray(2048)
                    val reply = DatagramPacket(buffer, buffer.size)
                    socket.receive(reply)
                    val body = parseDatagram(reply.data.copyOf(reply.length))
                    val host = reply.address.hostAddress ?: continue
                    parseDiscoveryReply(body, host)?.let { info ->
                        Log.i(TAG, "Unicast discovery parsed: $info")
                        found[info.identifier.ifBlank { info.host }] = info
                    }
                } catch (_: SocketTimeoutException) {
                    // Continue until the overall fallback timeout expires.
                }
            }
        }
        return found
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
