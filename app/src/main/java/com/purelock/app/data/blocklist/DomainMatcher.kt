package com.purelock.app.data.blocklist

/** Pure domain helpers (no Android dependencies) used by the filter and the lists UI. */
object DomainMatcher {

    private val DOMAIN_REGEX =
        Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{1,62}$")

    fun normalize(host: String): String = host.trim().lowercase().trimEnd('.')

    /** True if [host] or any parent domain of it is in [domains]. "a.b.example.com" matches "example.com". */
    fun matches(host: String, domains: Set<String>): Boolean {
        if (domains.isEmpty()) return false
        var h = normalize(host)
        while (true) {
            if (h in domains) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun isValidDomain(domain: String): Boolean = DOMAIN_REGEX.matches(domain)

    /**
     * Turns user input such as "https://www.Example.com/path?x=1" into "www.example.com",
     * or null if it is not a valid domain name.
     */
    fun sanitizeInput(raw: String): String? {
        var s = raw.trim().lowercase()
        s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfter('@').substringBefore(':')
        s = s.trimEnd('.')
        return if (isValidDomain(s)) s else null
    }

    /** Parses one line of a hosts-format list ("0.0.0.0 example.com") or a bare domain. */
    fun parseListLine(line: String): String? {
        val t = line.substringBefore('#').trim()
        if (t.isEmpty()) return null
        val parts = t.split(Regex("\\s+"))
        val candidate = when {
            parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1") -> parts[1]
            parts.size == 1 -> parts[0]
            else -> return null
        }.lowercase().trimEnd('.')
        if (candidate == "localhost" || candidate == "0.0.0.0") return null
        return if (isValidDomain(candidate)) candidate else null
    }
}
