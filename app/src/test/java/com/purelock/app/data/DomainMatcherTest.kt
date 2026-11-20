package com.purelock.app.data

import com.purelock.app.data.blocklist.DomainMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainMatcherTest {

    private val blocked = setOf("example.com", "bad.org")

    @Test
    fun `subdomains match their parent`() {
        assertTrue(DomainMatcher.matches("example.com", blocked))
        assertTrue(DomainMatcher.matches("www.example.com", blocked))
        assertTrue(DomainMatcher.matches("a.b.c.example.com", blocked))
    }

    @Test
    fun `lookalike domains do not match`() {
        assertFalse(DomainMatcher.matches("notexample.com", blocked))
        assertFalse(DomainMatcher.matches("example.com.evil.net", blocked))
        assertFalse(DomainMatcher.matches("com", blocked))
    }

    @Test
    fun `matching ignores case and a trailing dot`() {
        assertTrue(DomainMatcher.matches("WWW.Example.COM.", blocked))
    }

    @Test
    fun `empty set matches nothing`() {
        assertFalse(DomainMatcher.matches("example.com", emptySet()))
    }

    @Test
    fun `domain validation`() {
        assertTrue(DomainMatcher.isValidDomain("example.com"))
        assertTrue(DomainMatcher.isValidDomain("good-one.co.uk"))
        assertFalse(DomainMatcher.isValidDomain("-bad.com"))
        assertFalse(DomainMatcher.isValidDomain("a..com"))
        assertFalse(DomainMatcher.isValidDomain("localhost"))
        assertFalse(DomainMatcher.isValidDomain("example.c"))
        assertFalse(DomainMatcher.isValidDomain("has space.com"))
    }

    @Test
    fun `user input is cleaned up`() {
        assertEquals("www.example.com", DomainMatcher.sanitizeInput("https://www.Example.com/path?x=1"))
        assertEquals("example.com", DomainMatcher.sanitizeInput("  example.com:8080/x  "))
        assertEquals("example.com", DomainMatcher.sanitizeInput("example.com."))
        assertNull(DomainMatcher.sanitizeInput("not a domain"))
        assertNull(DomainMatcher.sanitizeInput("localhost"))
        assertNull(DomainMatcher.sanitizeInput(""))
    }

    @Test
    fun `hosts file lines are parsed`() {
        assertEquals("bad.example.com", DomainMatcher.parseListLine("0.0.0.0 bad.example.com # comment"))
        assertEquals("bad.example.com", DomainMatcher.parseListLine("127.0.0.1\tbad.example.com"))
        assertEquals("example.org", DomainMatcher.parseListLine("example.org"))
        assertNull(DomainMatcher.parseListLine("127.0.0.1 localhost"))
        assertNull(DomainMatcher.parseListLine("0.0.0.0 0.0.0.0"))
        assertNull(DomainMatcher.parseListLine("# only a comment"))
        assertNull(DomainMatcher.parseListLine("1.2.3.4 other.example.com"))
        assertNull(DomainMatcher.parseListLine(""))
    }
}
