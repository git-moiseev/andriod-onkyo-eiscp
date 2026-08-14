package com.moiseev.onkyoremote.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class OnkyoClient(private val listener: Listener) {
    private companion object { const val TAG = "OnkyoEiscp" }
    private data class QueuedCommand(val command: String, val destination: Char)

    interface Listener {
        fun onConnected(info: ReceiverInfo)
        fun onDisconnected(reason: String?)
        fun onMessage(command: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val outgoing = LinkedBlockingQueue<QueuedCommand>()
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    @Volatile private var socket: Socket? = null

    fun connect(info: ReceiverInfo) {
        Log.i(TAG, "Connecting TCP to ${info.host}:${info.port}; knownModel='${info.modelName}'")
        disconnect()
        outgoing.clear()
        val connectionId = generation.incrementAndGet()
        running.set(true)
        readerThread = Thread({ connectAndRead(info, connectionId) }, "onkyo-eiscp-reader-$connectionId").also { it.start() }
    }

    fun disconnect() {
        generation.incrementAndGet()
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        readerThread = null
        writerThread = null
        outgoing.clear()
    }

    fun send(command: String, destination: Char = '1') {
        if (running.get()) {
            Log.d(TAG, "Queue TX destination=$destination command='${EiscpProtocol.debugText(command)}'")
            outgoing.offer(QueuedCommand(command, destination))
        } else {
            Log.w(TAG, "Dropped TX while disconnected: destination=$destination command='${EiscpProtocol.debugText(command)}'")
        }
    }

    fun queryStatus() {
        send("ECNQSTN", destination = 'x')
        send("PWRQSTN")
        send("MVLQSTN")
        send("AMTQSTN")
        send("SLIQSTN")
    }

    private fun connectAndRead(info: ReceiverInfo, connectionId: Long) {
        var reason: String? = null
        var connectionSocket: Socket? = null
        try {
            if (generation.get() != connectionId) return
            val s = Socket()
            connectionSocket = s
            if (generation.get() != connectionId) return
            socket = s
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(info.host, info.port), 3000)
            if (generation.get() != connectionId) return
            Log.i(TAG, "TCP connected to ${info.host}:${info.port}")

            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            val output = BufferedOutputStream(s.getOutputStream())
            main.post { if (generation.get() == connectionId) listener.onConnected(info) }

            writerThread = Thread({ writeLoop(output, s, connectionId) }, "onkyo-eiscp-writer-$connectionId").also { it.start() }
            queryStatus()

            while (running.get() && generation.get() == connectionId) {
                val message = EiscpProtocol.readPacket(input)
                Log.d(TAG, "Dispatch RX '${EiscpProtocol.debugText(message)}'")
                main.post { if (generation.get() == connectionId) listener.onMessage(message) }
            }
        } catch (e: Exception) {
            if (generation.get() == connectionId) {
                Log.e(TAG, "TCP reader failed for ${info.host}:${info.port}", e)
                if (running.get()) reason = e.message ?: e.javaClass.simpleName
            }
        } finally {
            try { connectionSocket?.close() } catch (_: Exception) {}
            if (generation.get() == connectionId) {
                val notify = running.getAndSet(false)
                if (socket === connectionSocket) socket = null
                if (notify) main.post { if (generation.get() == connectionId) listener.onDisconnected(reason) }
            }
        }
    }

    private fun writeLoop(output: BufferedOutputStream, connectionSocket: Socket, connectionId: Long) {
        try {
            while (running.get() && generation.get() == connectionId) {
                val queued = outgoing.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (generation.get() != connectionId) break
                Log.d(TAG, "TX destination=${queued.destination} command='${EiscpProtocol.debugText(queued.command)}'")
                output.write(EiscpProtocol.packet(queued.command, queued.destination))
                output.flush()
            }
        } catch (e: Exception) {
            if (generation.get() == connectionId) Log.e(TAG, "TCP writer failed", e)
            if (generation.get() == connectionId && running.getAndSet(false)) {
                try { connectionSocket.close() } catch (_: Exception) {}
                main.post { listener.onDisconnected(e.message ?: e.javaClass.simpleName) }
            }
        }
    }
}
