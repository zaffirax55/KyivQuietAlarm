package com.pccontrol.client

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Local-network Wake-on-LAN magic packet. Requires hardware and firmware support. */
object WakeOnLan {
    fun normalizeMac(value: String): String? {
        val cleaned = value.trim().replace(":", "").replace("-", "")
        return if (cleaned.length == 12 && cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' })
            cleaned.uppercase() else null
    }
    fun send(mac: String, ip: String): Int {
        val normalized = normalizeMac(mac) ?: throw IllegalArgumentException("Неверный MAC-адрес")
        val hardware = ByteArray(6) { index ->
            normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        val packet = ByteArray(102)
        for (i in 0 until 6) packet[i] = 0xff.toByte()
        for (i in 0 until 16) hardware.copyInto(packet, 6 + i * 6)
        val destinations = linkedSetOf("255.255.255.255")
        if (PcApi.validIp(ip)) {
            val octets = ip.split('.').map { it.toInt() }
            // Only infer a /24 directed broadcast for typical RFC1918 home LAN addresses.
            if (octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31)) {
                destinations.add("${octets[0]}.${octets[1]}.${octets[2]}.255")
            }
        }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 3000
            for (host in destinations) {
                val datagram = DatagramPacket(packet, packet.size, InetAddress.getByName(host), 9)
                socket.send(datagram)
            }
        }
        return destinations.size
    }
}
