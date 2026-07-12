package com.nativegamepad

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class DiscoveredServer(val hostname: String, val ip: String, val port: Int)

class ServerDiscovery {
    suspend fun discoverServers(): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val servers = mutableListOf<DiscoveredServer>()
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 1500 // Wait 1.5 seconds for replies

            val message = "NYXX_DISCOVER".toByteArray()
            val broadcastAddress = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(message, message.size, broadcastAddress, 5001)
            socket.send(packet)

            val receiveBuffer = ByteArray(1024)
            while (true) {
                try {
                    val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    socket.receive(receivePacket)

                    val reply = String(receivePacket.data, 0, receivePacket.length)
                    if (reply.startsWith("NYXX_SERVER|")) {
                        val parts = reply.split("|")
                        if (parts.size >= 3) {
                            val hostname = parts[1]
                            val port = parts[2].toIntOrNull() ?: 5000
                            val ip = receivePacket.address.hostAddress
                            
                            // Prevent duplicates if a server replies multiple times
                            if (ip != null && servers.none { it.ip == ip }) {
                                servers.add(DiscoveredServer(hostname, ip, port))
                            }
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    break // Timeout reached, return what we found
                }
            }
        } catch (e: Exception) {
            Log.e("ServerDiscovery", "Discovery failed", e)
        } finally {
            socket?.close()
        }
        return@withContext servers
    }
}
