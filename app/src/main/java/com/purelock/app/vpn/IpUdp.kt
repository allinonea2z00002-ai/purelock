package com.purelock.app.vpn

/** IPv4 + UDP packet parsing/building for the VPN tunnel. No Android dependencies. */
object IpUdp {

    class Packet(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray
    )

    /** Parses an IPv4/UDP packet. Returns null for anything that is not a well-formed, unfragmented UDP/IPv4 packet. */
    fun parse(buf: ByteArray, len: Int): Packet? {
        if (len < 28) return null
        val version = (buf[0].toInt() and 0xF0) shr 4
        if (version != 4) return null
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if ((buf[9].toInt() and 0xFF) != 17) return null            // UDP only
        val fragment = ((buf[6].toInt() and 0x1F) shl 8) or (buf[7].toInt() and 0xFF)
        if (fragment != 0) return null                                // fragmented: not supported
        val total = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        val packetLen = minOf(total, len)
        val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
        val udpLen = ((buf[ihl + 4].toInt() and 0xFF) shl 8) or (buf[ihl + 5].toInt() and 0xFF)
        val payloadLen = minOf(udpLen - 8, packetLen - ihl - 8)
        if (payloadLen < 0) return null
        return Packet(
            srcIp = buf.copyOfRange(12, 16),
            dstIp = buf.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            payload = buf.copyOfRange(ihl + 8, ihl + 8 + payloadLen)
        )
    }

    fun build(srcIp: ByteArray, dstIp: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val total = 20 + 8 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45.toByte()
        p[2] = (total shr 8).toByte(); p[3] = (total and 0xFF).toByte()
        p[6] = 0x40                                                   // don't fragment
        p[8] = 64                                                     // TTL
        p[9] = 17                                                     // UDP
        System.arraycopy(srcIp, 0, p, 12, 4)
        System.arraycopy(dstIp, 0, p, 16, 4)
        val checksum = headerChecksum(p, 20)
        p[10] = (checksum shr 8).toByte(); p[11] = (checksum and 0xFF).toByte()
        p[20] = (srcPort shr 8).toByte(); p[21] = (srcPort and 0xFF).toByte()
        p[22] = (dstPort shr 8).toByte(); p[23] = (dstPort and 0xFF).toByte()
        val udpLen = 8 + payload.size
        p[24] = (udpLen shr 8).toByte(); p[25] = (udpLen and 0xFF).toByte()
        // UDP checksum 0 = "not computed", which is allowed for IPv4.
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    fun headerChecksum(buf: ByteArray, headerLen: Int): Int {
        var sum = 0
        var i = 0
        while (i < headerLen) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }
}
