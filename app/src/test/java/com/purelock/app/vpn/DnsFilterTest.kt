package com.purelock.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DnsFilterTest {

    private fun filter(safeSearch: Boolean, blocked: Set<String> = setOf("bad.com", "www.youtube.com")) =
        DnsFilter(isBlocked = { it in blocked }, safeSearchEnabled = { safeSearch })

    @Test
    fun `blocked names are blocked`() {
        assertEquals(DnsDecision.Block, filter(true).decide("bad.com"))
        assertEquals(DnsDecision.Block, filter(false).decide("bad.com"))
    }

    @Test
    fun `blocking wins over safe search rewriting`() {
        assertEquals(DnsDecision.Block, filter(true).decide("www.youtube.com"))
    }

    @Test
    fun `search engines are rewritten when SafeSearch is on`() {
        val f = filter(true)
        assertEquals(DnsDecision.Rewrite("forcesafesearch.google.com"), f.decide("www.google.com"))
        assertEquals(DnsDecision.Rewrite("forcesafesearch.google.com"), f.decide("google.co.uk"))
        assertEquals(DnsDecision.Rewrite("strict.bing.com"), f.decide("www.bing.com"))
        assertEquals(DnsDecision.Rewrite("safe.duckduckgo.com"), f.decide("duckduckgo.com"))
        assertEquals(DnsDecision.Rewrite("restrict.youtube.com"), f.decide("m.youtube.com"))
    }

    @Test
    fun `nothing is rewritten when SafeSearch is off`() {
        assertEquals(DnsDecision.Forward, filter(false).decide("www.google.com"))
    }

    @Test
    fun `ordinary names are forwarded`() {
        assertEquals(DnsDecision.Forward, filter(true).decide("example.com"))
    }

    @Test
    fun `only the real search hosts have safe endpoints`() {
        assertNull(SafeSearch.targetFor("mail.google.com"))
        assertNull(SafeSearch.targetFor("google.evil.com"))
    }
}
