package cl.ti3042.caracol

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("MissingPermission")
class BluetoothIo(device: BluetoothDevice, private val notify: (Event) -> Unit) : AutoCloseable {
    sealed interface Event {
        data object Connected : Event
        data object Stale : Event
        data object Disconnected : Event
        data class Data(val message: SerialMessage) : Event
    }

    private val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(
        UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    )
    private val writer = Executors.newSingleThreadScheduledExecutor()
    private val closed = AtomicBoolean(false)
    @Volatile private var ready = false
    @Volatile private var lastTelemetry = 0L

    fun start() {
        thread("Caracol RFCOMM") {
            try {
                socket.connect()
                if (closed.get()) return@thread
                notify(Event.Connected)
                arm()
                writer.scheduleWithFixedDelay(::heartbeat, 1, 1, TimeUnit.SECONDS)
                read()
            } catch (_: Exception) {
                fail()
            }
        }
    }

    fun arm() {
        ready = false
        enqueue("INICIO")
    }

    fun send(action: Action, id: Int) {
        if (ready) enqueue(action.wire + "," + id)
    }

    private fun heartbeat() {
        if (!ready) return
        if (SystemClock.elapsedRealtime() - lastTelemetry >= STALE_MS) {
            ready = false
            notify(Event.Stale)
        } else {
            write("H")
        }
    }

    private fun enqueue(line: String) {
        if (!closed.get()) runCatching { writer.execute { write(line) } }
    }

    private fun write(line: String) {
        if (closed.get()) return
        try {
            socket.outputStream.write((line + "\n").toByteArray(Charsets.US_ASCII))
            socket.outputStream.flush()
        } catch (_: IOException) {
            fail()
        }
    }

    private fun read() {
        val input = BufferedInputStream(socket.inputStream)
        val line = StringBuilder(64)
        var drop = false
        while (!closed.get()) {
            when (val byte = input.read()) {
                -1 -> throw EOFException()
                10 -> {
                    if (!drop) parseSerial(line.toString())?.let(::received)
                    line.setLength(0)
                    drop = false
                }
                in 32..126 -> if (!drop && line.length < 64) line.append(byte.toChar()) else drop = true
                else -> drop = true
            }
        }
    }

    private fun received(message: SerialMessage) {
        when (message) {
            SerialMessage.Ready -> {
                lastTelemetry = SystemClock.elapsedRealtime()
                ready = true
            }
            SerialMessage.Boot -> ready = false
            is SerialMessage.Telemetry -> {
                lastTelemetry = SystemClock.elapsedRealtime()
                if (!message.reading.armed) ready = false
            }
            is SerialMessage.Ack -> Unit
        }
        notify(Event.Data(message))
    }

    private fun fail() {
        if (closed.compareAndSet(false, true)) {
            release()
            notify(Event.Disconnected)
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }

    private fun release() {
        writer.shutdownNow()
        runCatching(socket::close)
    }
}
