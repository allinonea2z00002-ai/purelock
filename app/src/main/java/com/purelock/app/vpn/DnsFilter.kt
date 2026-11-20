package com.purelock.app.vpn

/** What the local DNS filter does with one query. */
sealed interface DnsDecision {
    /** Answer "does not exist". */
    data object Block : DnsDecision
    /** Resolve normally through the upstream resolver. */
    data object Forward : DnsDecision
    /** Answer with the addresses of [target] (SafeSearch / Restricted Mode). */
    data class Rewrite(val target: String) : DnsDecision
}

/** Search-engine hosts that offer a DNS-based "safe" endpoint. */
object SafeSearch {
    private val GOOGLE = Regex("^(www\\.)?google\\.(com|[a-z]{2}|co\\.[a-z]{2}|com\\.[a-z]{2})$")

    fun targetFor(host: String): String? = when {
        GOOGLE.matches(host) -> "forcesafesearch.google.com"
        host == "bing.com" || host == "www.bing.com" -> "strict.bing.com"
        host == "duckduckgo.com" || host == "www.duckduckgo.com" -> "safe.duckduckgo.com"
        host == "www.youtube.com" || host == "m.youtube.com" || host == "youtubei.googleapis.com" ||
            host == "youtube.googleapis.com" || host == "www.youtube-nocookie.com" -> "restrict.youtube.com"
        else -> null
    }
}

class DnsFilter(
    private val isBlocked: (String) -> Boolean,
    private val safeSearchEnabled: () -> Boolean
) {
    fun decide(host: String): DnsDecision {
        if (isBlocked(host)) return DnsDecision.Block
        if (safeSearchEnabled()) SafeSearch.targetFor(host)?.let { return DnsDecision.Rewrite(it) }
        return DnsDecision.Forward
    }
}
