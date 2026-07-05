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
    var rt: Byte = 0
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
    }
    
    fun equalsExceptSeq(other: ControllerState): Boolean {
        return this.buttons == other.buttons &&
               this.lx == other.lx && this.ly == other.ly &&
               this.rx == other.rx && this.ry == other.ry &&
               this.lt == other.lt && this.rt == other.rt
    }
}



class UdpSender(private val pcIp: String, private val port: Int, private val vibrator: Vibrator? = null) {
    private var socket: DatagramSocket? = null
    private var address: InetAddress? = null
    private var isRunning = false
    private var thread: Thread? = null

    val state = ControllerState()
    private val lastSentState = ControllerState()
    
    var onDisconnect: (() -> Unit)? = null
    var isRumbleEnabled = true

    companion object {
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
                            if (msg == "DISCONNECT") {
                                onDisconnect?.invoke()
                            } else if (msg.startsWith("RUMBLE:")) {
                                val parts = msg.split(":")
                                if (parts.size == 3) {
                                    val large = parts[1].toIntOrNull() ?: 0
                                    val small = parts[2].toIntOrNull() ?: 0
                                    handleRumble(large, small)
                                }
                            }
                        } catch (e: SocketTimeoutException) {
                            // Expected, just loop
                        } catch (e: Exception) {
                            if (!isRunning) break
                        }
                    }
                }.start()

                val buf = ByteBuffer.allocate(15)
                buf.order(ByteOrder.BIG_ENDIAN)

                var lastSendTime = System.currentTimeMillis()
                var checkTimeout = true

                while (isRunning) {
                    val now = System.currentTimeMillis()
                    val stateChanged = !state.equalsExceptSeq(lastSentState)
                    val heartbeatDue = (now - lastSendTime) > 300 // Send heartbeat every 300ms to keep connection alive

                    if (stateChanged || heartbeatDue) {
                        buf.clear()
                        buf.put(1.toByte()) // version
                        buf.putShort(state.seq.toShort())
                        buf.putShort(state.buttons.toShort())
                        buf.putShort(state.lx)
                        buf.putShort(state.ly)
                        buf.putShort(state.rx)
                        buf.putShort(state.ry)
                        buf.put(state.lt)
                        buf.put(state.rt)

                        val data = buf.array()
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
                } catch (e: Exception) {}
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createOneShot(150, intensity)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(150)
        }
    }
}


