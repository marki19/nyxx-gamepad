package com.nativegamepad

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlin.math.max

data class ControllerState(
    var seq: Int = 0,
    var buttons: Int = 0, // bitmask
    var lx: Short = 0,
    var ly: Short = 0,
    var rx: Short = 0,
    var ry: Short = 0,
    var lt: Byte = 0,
    var rt: Byte = 0,
    var accelX: Float = 0f,
    var accelY: Float = 0f,
    var accelZ: Float = 0f,
    var gyroX: Float = 0f,
    var gyroY: Float = 0f,
    var gyroZ: Float = 0f,
    var joyconType: Byte = 0, // 0=Right, 1=Left, 2=Pro (matches server JoyConType)
    var battery: Byte = 4     // 0-4 scale, 4=Full
) {
    fun copyFrom(other: ControllerState) {
        this.seq = other.seq
        this.buttons = other.buttons
        this.lx = other.lx
        this.ly = other.ly
        this.rx = other.rx
        this.ry = other.ry
        this.lt = other.lt
        this.rt = other.rt
        this.accelX = other.accelX
        this.accelY = other.accelY
        this.accelZ = other.accelZ
        this.gyroX = other.gyroX
        this.gyroY = other.gyroY
        this.gyroZ = other.gyroZ
        this.joyconType = other.joyconType
        this.battery = other.battery
    }

    fun equalsExceptSeq(other: ControllerState): Boolean {
        return this.buttons == other.buttons &&
               this.lx == other.lx && this.ly == other.ly &&
               this.rx == other.rx && this.ry == other.ry &&
               this.lt == other.lt && this.rt == other.rt &&
               this.accelX == other.accelX && this.accelY == other.accelY && this.accelZ == other.accelZ &&
               this.gyroX == other.gyroX && this.gyroY == other.gyroY && this.gyroZ == other.gyroZ &&
               this.joyconType == other.joyconType
    }
}



class UdpSender(private val pcIp: String, private val port: Int, private val vibrator: Vibrator? = null) {
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var isRunning = false
    private var thread: Thread? = null

    val state = ControllerState()
    private val lastSentState = ControllerState()
    // Fix 5: class-level ByteBuffer to avoid re-allocating each send
    private val sendBuf: ByteBuffer = ByteBuffer.allocate(41).also { it.order(ByteOrder.BIG_ENDIAN) }
    
    var onDisconnect: (() -> Unit)? = null
    var onServerFull: (() -> Unit)? = null
    var isRumbleEnabled = true
    var isPaused = false

    companion object {
        // Returns player index 1-4 on success, 0 for server full, -1 on failure
        fun pingServer(ip: String, port: Int): Int {
            var tempSocket: DatagramSocket? = null
            try {
                tempSocket = DatagramSocket()
                tempSocket.soTimeout = 2000
                val addr = InetAddress.getByName(ip)
                val pingData = "PING".toByteArray()
                val packet = DatagramPacket(pingData, pingData.size, addr, port)
                tempSocket.send(packet)

                val buf = ByteArray(64)
                val recvPacket = DatagramPacket(buf, buf.size)
                tempSocket.receive(recvPacket)
                val response = String(recvPacket.data, 0, recvPacket.length).trim()
                if (response.startsWith("PONG:")) {
                    return response.split(":")[1].toIntOrNull() ?: 1
                }
                if (response == "FULL") return 0 // Server is at max capacity
                return if (response == "PONG") 1 else -1
            } catch (e: Exception) {
                e.printStackTrace()
                return -1
            } finally {
                tempSocket?.close()
            }
        }
    }

    fun start() {
        if (isRunning) return
        isRunning = true

        thread = Thread {
            try {
                socket = DatagramSocket()
                socket?.soTimeout = 2000 // Timeout for receive to occasionally unblock
                address = InetAddress.getByName(pcIp)
                val lastRecvTime = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
                
                // Launch receive thread to listen on the exact same port we send from
                Thread {
                    val recvBuf = ByteArray(256)
                    while (isRunning) {
                        try {
                            val pkt = DatagramPacket(recvBuf, recvBuf.size)
                            socket?.receive(pkt)
                            val msg = String(pkt.data, 0, pkt.length).trim()
                            lastRecvTime.set(System.currentTimeMillis())
                            when {
                                msg == "DISCONNECT" -> onDisconnect?.invoke()
                                msg == "FULL" -> onServerFull?.invoke()
                                msg.startsWith("RUMBLE:") -> {
                                    val parts = msg.split(":")
                                    if (parts.size == 3) {
                                        val large = parts[1].toIntOrNull() ?: 0
                                        val small = parts[2].toIntOrNull() ?: 0
                                        handleRumble(large, small)
                                    }
                                }
                            }
                        } catch (_: SocketTimeoutException) {
                            // Expected, just loop
                        } catch (_: Exception) {
                            if (!isRunning) break
                        }
                    }
                }.start()

                val pingPacket = "PING".toByteArray()
                var lastPingTime = System.currentTimeMillis()
                var lastSendTime = System.currentTimeMillis()

                while (isRunning) {
                    val now = System.currentTimeMillis()

                    // If we haven't heard from the server in 10 seconds, drop the connection
                    if (now - lastRecvTime.get() > 10000) {
                        onDisconnect?.invoke()
                        break
                    }

                    // Keep the server's PONG liveness reply flowing (the server no longer sends its own PING)
                    if (now - lastPingTime > 2000) {
                        try { socket?.send(DatagramPacket(pingPacket, pingPacket.size, address, port)) } catch (_: Exception) {}
                        lastPingTime = now
                    }

                    val stateChanged = !state.equalsExceptSeq(lastSentState)
                    // Fix 6: suppress heartbeats when paused (still send PING above for keepalive)
                    val heartbeatDue = !isPaused && (now - lastSendTime) > 300

                    if (stateChanged || heartbeatDue) {
                        sendBuf.clear()
                        sendBuf.put(1.toByte()) // version
                        sendBuf.putShort(state.seq.toShort())
                        sendBuf.putShort(state.buttons.toShort())
                        sendBuf.putShort(state.lx)
                        sendBuf.putShort(state.ly)
                        sendBuf.putShort(state.rx)
                        sendBuf.putShort(state.ry)
                        sendBuf.put(state.lt)
                        sendBuf.put(state.rt)
                        sendBuf.putFloat(state.accelX)
                        sendBuf.putFloat(state.accelY)
                        sendBuf.putFloat(state.accelZ)
                        sendBuf.putFloat(state.gyroX)
                        sendBuf.putFloat(state.gyroY)
                        sendBuf.putFloat(state.gyroZ)
                        // Joy-Con metadata (2 bytes) — keeps the server's offset stable at data.Length >= 41
                        sendBuf.put(state.joyconType)
                        sendBuf.put(state.battery)

                        val data = sendBuf.array()
                        val packet = DatagramPacket(data, data.size, address, port)
                        socket?.send(packet)

                        lastSentState.copyFrom(state)
                        lastSendTime = now

                        state.seq++
                        if (state.seq > 65535) state.seq = 0
                    }

                    Thread.sleep(16)

                    // ~60Hz polling
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    Thread.interrupted() // clear interrupt flag
                    val dData = "DISCONNECT".toByteArray()
                    socket?.send(DatagramPacket(dData, dData.size, address, port))
                } catch (_: Exception) {}
                socket?.close()
                socket = null
            }
        }
        thread?.start()
    }

    fun stop() {
        isRunning = false
        thread?.interrupt()
        val t = thread
        thread = null
        t?.join(500)
    }

    private fun handleRumble(large: Int, small: Int) {
        if (!isRumbleEnabled || vibrator == null || !vibrator.hasVibrator()) return
        if (large == 0 && small == 0) {
            vibrator.cancel()
            return
        }
        val intensity = maxOf(large, small)
        if (intensity == 0) return

        val effect = VibrationEffect.createOneShot(150L, intensity)
        vibrator.vibrate(effect)
    }
}


