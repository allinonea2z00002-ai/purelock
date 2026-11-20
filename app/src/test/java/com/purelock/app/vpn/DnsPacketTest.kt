package com.purelock.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class DnsPacketTest {

    private fun query(name: String = "www.example.com", type: Int = DnsPacket.TYPE_A) =
        DnsPacket.buildQuery(name, type, Random(42))

    private fun u8(b: Byte) = b.toInt() and 0xFF
    private fun u16(b: ByteArray, off: Int) = (u8(b[off]) shl 8) or u8(b[off + 1])

    @Test
    fun `a built query can be parsed again`() {
        val q = query()
        val parsed = DnsPacket.parseQuestion(q, q.size)
        assertNotNull(parsed)
        assertEquals("www.example.com", parsed!!.name)
        assertEquals(DnsPacket.TYPE_A, parsed.type)
        assertEquals(1, parsed.clazz)
        assertEquals(u16(q, 0), parsed.id)
        assertEquals(q.size, parsed.end)
    }

    @Test
    fun `names are lower-cased`() {
        val q = query("WWW.Example.COM")
        assertEquals("www.example.com", DnsPacket.parseQuestion(q, q.size)!!.name)
    }

    @Test
    fun `responses and truncated packets are rejected`() {
        val q = query()
        val response = q.copyOf().also { it[2] = (it[2].toInt() or 0x80).toByte() }
        assertNull(DnsPacket.parseQuestion(response, response.size))
        assertNull(DnsPacket.parseQuestion(q, 10))
        assertNull(DnsPacket.parseQuestion(q, 20)) // cut inside the name
    }

    @Test
    fun `blocked names get NXDOMAIN with the same id and question`() {
        val q = query()
        val question = DnsPacket.parseQuestion(q, q.size)!!
        val r = DnsPacket.buildNxDomain(q, question.end)

        assertEquals(u16(q, 0), u16(r, 0))             // same transaction id
        assertTrue(u8(r[2]) and 0x80 != 0)             // QR: this is a response
        assertEquals(3, u8(r[3]) and 0x0F)             // RCODE 3 = NXDOMAIN
        assertEquals(1, u16(r, 4))                     // one question
        assertEquals(0, u16(r, 6))                     // no answers
        assertEquals(0x81, u8(r[2]))                   // QR + RD echoed
        assertArrayEquals(q.copyOfRange(12, question.end), r.copyOfRange(12, r.size))
    }

    @Test
    fun `empty response has no error and no answers`() {
        val q = query(type = DnsPacket.TYPE_AAAA)
        val question = DnsPacket.parseQuestion(q, q.size)!!
        val r = DnsPacket.buildEmpty(q, question.end)
        assertEquals(0, u8(r[3]) and 0x0F)
        assertEquals(0, u16(r, 6))
    }

    @Test
    fun `A records survive a build and extract round trip`() {
        val q = query()
        val question = DnsPacket.parseQuestion(q, q.size)!!
        val ips = listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8))
        val r = DnsPacket.buildARecords(q, question.end, ips, 300)

        assertEquals(2, u16(r, 6))
        val extracted = DnsPacket.extractARecords(r, r.size)
        assertEquals(2, extracted.size)
        assertArrayEquals(ips[0], extracted[0])
        assertArrayEquals(ips[1], extracted[1])
    }

    @Test
    fun `no addresses are extracted from an error response`() {
        val q = query()
        val question = DnsPacket.parseQuestion(q, q.size)!!
        val nx = DnsPacket.buildNxDomain(q, question.end)
        assertTrue(DnsPacket.extractARecords(nx, nx.size).isEmpty())
    }

    @Test
    fun `garbage does not crash the parsers`() {
        val junk = ByteArray(40) { (it * 37).toByte() }
        DnsPacket.parseQuestion(junk, junk.size)
        DnsPacket.extractARecords(junk, junk.size)
        DnsPacket.extractARecords(ByteArray(0), 0)
    }
}
