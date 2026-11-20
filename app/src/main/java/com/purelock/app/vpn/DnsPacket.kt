package com.purelock.app.vpn

import java.io.ByteArrayOutputStream
import java.util.Random

/** Minimal DNS wire-format helpers for the local filter (queries over UDP). No Android dependencies. */
object DnsPacket {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_HTTPS = 65

    private const val RCODE_NXDOMAIN = 3

    data class Question(val id: Int, val name: String, val type: Int, val clazz: Int, val end: Int)

    private fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    /** Parses a standard single-question query. Returns null for anything else (responses, odd packets). */
    fun parseQuestion(buf: ByteArray, len: Int): Question? {
        if (len < 12) return null
        val flags = u16(buf, 2)
        if (flags and 0x8000 != 0) return null          // a response, not a query
        if (u16(buf, 4) != 1) return null                // exactly one question
        var pos = 12
        val name = StringBuilder()
        while (true) {
            if (pos >= len) return null
            val labelLen = buf[pos].toInt() and 0xFF
            if (labelLen == 0) { pos++; break }
            if (labelLen and 0xC0 != 0) return null      // compression is not valid in a question
            if (pos + 1 + labelLen > len) return null
            if (name.isNotEmpty()) name.append('.')
            for (i in 0 until labelLen) name.append((buf[pos + 1 + i].toInt() and 0xFF).toChar())
            pos += labelLen + 1
        }
        if (pos + 4 > len) return null
        return Question(
            id = u16(buf, 0),
            name = name.toString().lowercase(),
            type = u16(buf, pos),
            clazz = u16(buf, pos + 2),
            end = pos + 4
        )
    }

    /** Response saying the name does not exist. Used for blocked domains. */
    fun buildNxDomain(query: ByteArray, questionEnd: Int): ByteArray =
        buildResponse(query, questionEnd, RCODE_NXDOMAIN, emptyList(), 0)

    /** Successful response with no records ("no such record type"). */
    fun buildEmpty(query: ByteArray, questionEnd: Int): ByteArray =
        buildResponse(query, questionEnd, 0, emptyList(), 0)

    /** Response with A records for the original question name. */
    fun buildARecords(query: ByteArray, questionEnd: Int, ips: List<ByteArray>, ttl: Int): ByteArray =
        buildResponse(query, questionEnd, 0, ips, ttl)

    private fun buildResponse(query: ByteArray, questionEnd: Int, rcode: Int, ips: List<ByteArray>, ttl: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(query[0].toInt()); out.write(query[1].toInt())                 // id
        val rd = u16(query, 2) and 0x0100
        val flags = 0x8000 or rd or 0x0080 or rcode                              // QR, RD echo, RA, RCODE
        out.write(flags shr 8); out.write(flags and 0xFF)
        out.write(0); out.write(1)                                               // QDCOUNT = 1
        out.write(ips.size shr 8); out.write(ips.size and 0xFF)                  // ANCOUNT
        out.write(0); out.write(0); out.write(0); out.write(0)                   // NSCOUNT, ARCOUNT
        out.write(query, 12, questionEnd - 12)                                   // the question
        for (ip in ips) {
            out.write(0xC0); out.write(0x0C)                                     // name: pointer to the question
            out.write(0); out.write(TYPE_A)
            out.write(0); out.write(1)                                           // class IN
            out.write((ttl shr 24) and 0xFF); out.write((ttl shr 16) and 0xFF)
            out.write((ttl shr 8) and 0xFF); out.write(ttl and 0xFF)
            out.write(0); out.write(4)
            out.write(ip, 0, 4)
        }
        return out.toByteArray()
    }

    /** Builds a plain recursive query for [name]. */
    fun buildQuery(name: String, type: Int, random: Random = Random()): ByteArray {
        val out = ByteArrayOutputStream()
        val id = random.nextInt(0x10000)
        out.write(id shr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00)                                         // RD
        out.write(0); out.write(1); out.write(0); out.write(0)
        out.write(0); out.write(0); out.write(0); out.write(0)
        for (label in name.split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size); out.write(bytes)
        }
        out.write(0)
        out.write(type shr 8); out.write(type and 0xFF)
        out.write(0); out.write(1)
        return out.toByteArray()
    }

    /** Extracts the IPv4 addresses from the answer section of a response. */
    fun extractARecords(resp: ByteArray, len: Int): List<ByteArray> {
        if (len < 12 || u16(resp, 2) and 0x000F != 0) return emptyList()
        val qd = u16(resp, 4)
        val an = u16(resp, 6)
        var pos = 12
        repeat(qd) {
            pos = skipName(resp, len, pos)
            if (pos < 0) return emptyList()
            pos += 4
        }
        val result = ArrayList<ByteArray>()
        repeat(an) {
            pos = skipName(resp, len, pos)
            if (pos < 0 || pos + 10 > len) return result
            val type = u16(resp, pos)
            val rdLen = u16(resp, pos + 8)
            val dataStart = pos + 10
            if (dataStart + rdLen > len) return result
            if (type == TYPE_A && rdLen == 4) result += resp.copyOfRange(dataStart, dataStart + 4)
            pos = dataStart + rdLen
        }
        return result
    }

    /** Returns the offset just after the (possibly compressed) name at [start], or -1 if malformed. */
    private fun skipName(buf: ByteArray, len: Int, start: Int): Int {
        var pos = start
        while (true) {
            if (pos >= len) return -1
            val l = buf[pos].toInt() and 0xFF
            when {
                l == 0 -> return pos + 1
                l and 0xC0 == 0xC0 -> return if (pos + 2 <= len) pos + 2 else -1
                else -> pos += l + 1
            }
        }
    }
}
