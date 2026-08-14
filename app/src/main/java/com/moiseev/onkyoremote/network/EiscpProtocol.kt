package com.moiseev.onkyoremote.network

import android.util.Log
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object EiscpProtocol {
    private const val HEADER_SIZE = 16
    private const val TAG = "OnkyoEiscp"

    fun debugText(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '\r' -> append("\\r")
                '\n' -> append("\\n")
                '\u001a' -> append("\\x1A")
                '\u0000' -> append("\\0")
                else -> if (char.isISOControl()) append("\\x%02X".format(char.code)) else append(char)
            }
        }
    }

    fun packet(command: String, destination: Char = '1'): ByteArray {
        val body = "!$destination$command\r".toByteArray(Charsets.US_ASCII)
        val header = ByteBuffer.allocate(HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .put("ISCP".toByteArray(Charsets.US_ASCII))
            .putInt(HEADER_SIZE)
            .putInt(body.size)
            .put(0x01.toByte())
            .put(byteArrayOf(0, 0, 0))
            .array()
        return header + body
    }

    fun readPacket(input: DataInputStream): String {
        val header = ByteArray(HEADER_SIZE)
        input.readFully(header)
        require(String(header, 0, 4, Charsets.US_ASCII) == "ISCP") { "Invalid eISCP header" }

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        val headerSize = buffer.int
        val dataSize = buffer.int
        require(headerSize == HEADER_SIZE) { "Unexpected eISCP header size: $headerSize" }
        require(dataSize in 1..65536) { "Invalid eISCP payload size: $dataSize" }

        val body = ByteArray(dataSize)
        input.readFully(body)
        val rawBody = String(body, Charsets.US_ASCII)
        val parsed = parseIscpBody(rawBody)
        Log.d(TAG, "TCP frame headerSize=$headerSize dataSize=$dataSize raw='${debugText(rawBody)}' parsed='${debugText(parsed)}'")
        return parsed
    }

    fun parseIscpBody(body: String): String {
        val clean = body.trimEnd('\u001a', '\r', '\n', '\u0000')
        return if (clean.startsWith("!") && clean.length >= 2) clean.substring(2) else clean
    }

    fun parseInfoResponse(raw: String, host: String): ReceiverInfo? {
        val payload = raw.trim()
        val normalized = if (payload.startsWith("!") && payload.length >= 2) payload.substring(2) else payload
        if (!normalized.startsWith("ECN")) {
            Log.w(TAG, "Model parse skipped: response is not ECN: '${debugText(normalized)}'")
            return null
        }

        val match = Regex("ECN([^/]+)/([0-9]{1,5})/([^/]*)(?:/(.*))?").find(normalized)
        if (match == null) {
            Log.w(TAG, "Model parse failed: unsupported ECN format: '${debugText(normalized)}'")
            return null
        }
        val model = match.groupValues[1].ifBlank { "Receiver" }
        val port = match.groupValues[2].toIntOrNull() ?: 60128
        val area = match.groupValues[3]
        val identifier = match.groupValues.getOrElse(4) { "" }

        return ReceiverInfo(
            host = host,
            port = port,
            model = model,
            model_name = model,
            area = area,
            identifier = identifier
        ).also { Log.i(TAG, "Model parsed: model='${it.model}' host=${it.host} port=${it.port} area='${it.area}' id='${it.identifier}'") }
    }
}
