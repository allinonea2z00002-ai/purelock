package com.purelock.app.data.content

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Pure logic for the content filters. No storage, no network, no side effects: given visible text
 * (or a node tree to collect it from) it answers "does this match a keyword / a distracting feed /
 * a blocked-site URL?".
 *
 * Everything here is best-effort by design. Apps can rename their feed tabs, hide text inside
 * images or video, or load pages without exposing the address bar, so a feed or a word can slip
 * through. The UI says so plainly instead of promising a perfect block.
 */
object ContentWatch {

    const val PKG_YOUTUBE = "com.google.android.youtube"
    const val PKG_INSTAGRAM = "com.instagram.android"
    const val PKG_WHATSAPP = "com.whatsapp"
    const val PKG_SNAPCHAT = "com.snapchat.android"
    const val PKG_WA_BUSINESS = "com.whatsapp.w4b"

    /** One distracting in-app feed: which app it lives in and which [ContentFilterStore] key gates it. */
    enum class Feed(val packageName: String, val storeKey: String) {
        YOUTUBE_SHORTS(PKG_YOUTUBE, com.purelock.app.data.prefs.ContentFilterStore.FEED_YOUTUBE_SHORTS),
        INSTAGRAM_REELS(PKG_INSTAGRAM, com.purelock.app.data.prefs.ContentFilterStore.FEED_INSTAGRAM_REELS),
        INSTAGRAM_STORIES(PKG_INSTAGRAM, com.purelock.app.data.prefs.ContentFilterStore.FEED_INSTAGRAM_STORIES),
        WHATSAPP_STATUS(PKG_WHATSAPP, com.purelock.app.data.prefs.ContentFilterStore.FEED_WHATSAPP_STATUS),
        WHATSAPP_CHANNELS(PKG_WHATSAPP, com.purelock.app.data.prefs.ContentFilterStore.FEED_WHATSAPP_CHANNELS),
        SNAPCHAT_SPOTLIGHT(PKG_SNAPCHAT, com.purelock.app.data.prefs.ContentFilterStore.FEED_SNAPCHAT_SPOTLIGHT),
        SNAPCHAT_STORIES(PKG_SNAPCHAT, com.purelock.app.data.prefs.ContentFilterStore.FEED_SNAPCHAT_STORIES),
        WABUSINESS_STATUS(PKG_WA_BUSINESS, com.purelock.app.data.prefs.ContentFilterStore.FEED_WABUSINESS_STATUS),
        WABUSINESS_CHANNELS(PKG_WA_BUSINESS, com.purelock.app.data.prefs.ContentFilterStore.FEED_WABUSINESS_CHANNELS);

        companion object {
            fun forPackage(packageName: String): List<Feed> =
                values().filter { it.packageName == packageName }
        }
    }

    // --- keywords -------------------------------------------------------------------------

    /** Case-insensitive substring match of one keyword against one piece of visible text. */
    fun containsKeyword(text: CharSequence?, keyword: String): Boolean {
        if (text.isNullOrBlank() || keyword.isBlank()) return false
        return text.contains(keyword, ignoreCase = true)
    }

    /** First keyword that appears in any of the given texts, or null when none matches. */
    fun findKeyword(texts: Iterable<String>, keywords: Collection<String>): String? {
        if (keywords.isEmpty()) return null
        for (text in texts) {
            for (keyword in keywords) {
                if (containsKeyword(text, keyword)) return keyword
            }
        }
        return null
    }

    // --- feed detection ---------------------------------------------------------------------

    /**
     * Detects a distracting feed from the visible text of the foreground app. Matches on the
     * feed's own tab/label words (for example "Shorts" on YouTube, "Reels" on Instagram).
     * Returns the first matching feed for this package, or null.
     */
    fun detectFeed(packageName: String, texts: Iterable<String>): Feed? {
        val feeds = Feed.forPackage(packageName)
        if (feeds.isEmpty()) return null
        val joined = texts.joinToString(" ")
        for (feed in feeds) {
            if (matchesFeed(feed, joined)) return feed
        }
        return null
    }

    private fun matchesFeed(feed: Feed, joinedText: String): Boolean = when (feed) {
        Feed.YOUTUBE_SHORTS -> containsWord(joinedText, "shorts")
        Feed.INSTAGRAM_REELS -> containsWord(joinedText, "reels")
        Feed.INSTAGRAM_STORIES -> containsWord(joinedText, "stories") ||
            joinedText.contains("your story", ignoreCase = true)
        Feed.WHATSAPP_STATUS -> containsWord(joinedText, "status")
        Feed.WHATSAPP_CHANNELS -> containsWord(joinedText, "channels")
        Feed.SNAPCHAT_SPOTLIGHT -> containsWord(joinedText, "spotlight")
        Feed.SNAPCHAT_STORIES -> containsWord(joinedText, "stories")
        Feed.WABUSINESS_STATUS -> containsWord(joinedText, "status")
        Feed.WABUSINESS_CHANNELS -> containsWord(joinedText, "channels")
    }

    /** Whole-word match, so "shorts" does not fire on "shortstop" or a video title like "shortsale". */
    private fun containsWord(text: String, word: String): Boolean =
        Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)

    // --- visible text -----------------------------------------------------------------------

    /**
     * Collects the visible text and content descriptions from a node tree (depth-first, capped so
     * a huge screen cannot stall the service). Callers pass in a list to reuse across checks.
     */
    fun collectTexts(
        root: AccessibilityNodeInfo?,
        out: MutableList<String>,
        maxNodes: Int = 400,
        maxChars: Int = 20_000
    ) {
        if (root == null) return
        var nodes = 0
        var chars = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && nodes < maxNodes && chars < maxChars) {
            val node = stack.removeLast()
            nodes++
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
                out += it
                chars += it.length
            }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
                out += it
                chars += it.length
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
    }

    // --- URL watch ----------------------------------------------------------------------------

    /** True when the text looks like something typed or shown as a web address (no spaces, has a dot). */
    fun looksLikeUrl(text: CharSequence?): Boolean {
        if (text.isNullOrBlank()) return false
        val s = text.trim().toString()
        if (s.contains(' ') || s.contains('\n')) return false
        if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) return true
        // A bare host such as "example.com" or "www.example.com/path".
        return s.contains('.') && s.length <= 2_048 &&
            s.all { it.isLetterOrDigit() || it in ".-_~:/?#[]@!$&'()*+,;=%" }
    }

    /**
     * Pulls the host out of a URL or a bare host ("https://www.example.com/x?q=1" -> "example.com").
     * Returns null when the text is not a URL-like host.
     */
    fun extractHost(candidate: String?): String? {
        if (!looksLikeUrl(candidate)) return null
        var host = candidate!!.trim()
        host = host.substringAfter("://", host)
        host = host.substringBefore('/').substringBefore('?').substringBefore('#')
        host = host.substringBefore(':')
        if (host.startsWith("www.", ignoreCase = true)) host = host.substring(4)
        host = host.lowercase()
        if (host.isEmpty() || !host.contains('.')) return null
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        return host
    }

    /**
     * Finds the browser address-bar / WebView URL shown on screen: the text of an EditText (the
     * address bar in Chrome and most browsers) that looks like a URL. Returns its host, or null.
     */
    fun findAddressBarHost(root: AccessibilityNodeInfo?): String? {
        if (root == null) return null
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var nodes = 0
        while (stack.isNotEmpty() && nodes < 400) {
            val node = stack.removeLast()
            nodes++
            if (node.className == "android.widget.EditText") {
                extractHost(node.text?.toString())?.let { return it }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }
}
