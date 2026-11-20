package com.purelock.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IpUdpTest {

    private val src = byteArrayOf(10, 111, 222.toByte(), 1)
    private val dst = byteArrayOf(10, 111, 222.toByte(), 2)
    private val payload = byteArrayOf(1, 2, 3, 4, 5)

    @Test
    fun `a built packet parses back to the same values`() {
        val packet = IpUdp.build(src, dst, 40_000, 53, payload)
        val parsed = IpUdp.parse(packet, packet.size)
        assertNotNull(parsed)
        assertArrayEquals(src, parsed!!.srcIp)
        assertArrayEquals(dst, parsed.dstIp)
        assertEquals(40_000, parsed.srcPort)
        assertEquals(53, parsed.dstPort)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `built header has a valid checksum`() {
        val packet = IpUdp.build(src, dst, 40_000, 53, payload)
        // Summing a correct header including its checksum field gives zero.
        assertEquals(0, IpUdp.headerChecksum(packet, 20))
    }

    @Test
    fun `checksum matches a known reference header`() {
        // The classic worked example: header with the checksum field zeroed.
        val header = intArrayOf(
            0x45, 0x00, 0x00, 0x73, 0x00, 0x00, 0x40, 0x00, 0x40, 0x11, 0x00, 0x00,
            0xC0, 0xA8, 0x00, 0x01, 0xC0, 0xA8, 0x00, 0xC7
        ).map { it.toByte() }.toByteArray()
        assertEquals(0xB861, IpUdp.headerChecksum(header, 20))
    }

    @Test
    fun `non-UDP, non-IPv4, short and fragmented packets are ignored`() {
        val good = IpUdp.build(src, dst, 1234, 53, payload)

        val tcp = good.copyOf().also { it[9] = 6 }
        assertNull(IpUdp.parse(tcp, tcp.size))

        val v6 = good.copyOf().also { it[0] = 0x60 }
        assertNull(IpUdp.parse(v6, v6.size))

        assertNull(IpUdp.parse(good, 20))

        val fragment = good.copyOf().also { it[7] = 1 }
        assertNull(IpUdp.parse(fragment, fragment.size))
    }
}
