package com.purelock.app.data.prefs

import android.content.Context

/**
 * Content-filter switches and lists, kept in plain SharedPreferences like [UiPrefs] because they
 * are read synchronously (including from the accessibility service, where no DataStore flow is
 * available). The adult-content master toggle is NOT here: it reuses the existing
 * [SettingsKeys.FILTER_ENABLED] in [SettingsStore], read/written through [com.purelock.app.Graph.settings].
 */
object ContentFilterStore {
    private const val FILE = "purelock_content_filters"

    private const val KEY_KEYWORDS_ENABLED = "keywords_enabled"
    private const val KEY_KEYWORDS = "keywords"
    private const val KEY_REELS_ENABLED = "reels_enabled"

    const val FEED_YOUTUBE_SHORTS = "youtube_shorts"
    const val FEED_INSTAGRAM_REELS = "instagram_reels"
    const val FEED_INSTAGRAM_STORIES = "instagram_stories"
    const val FEED_WHATSAPP_STATUS = "whatsapp_status"
    const val FEED_WHATSAPP_CHANNELS = "whatsapp_channels"
    const val FEED_SNAPCHAT_SPOTLIGHT = "snapchat_spotlight"
    const val FEED_SNAPCHAT_STORIES = "snapchat_stories"
    const val FEED_WABUSINESS_STATUS = "wabusiness_status"
    const val FEED_WABUSINESS_CHANNELS = "wabusiness_channels"

    /** Every per-feed toggle key, in the order the Reels screen shows them. */
    val ALL_FEED_KEYS: List<String> = listOf(
        FEED_YOUTUBE_SHORTS,
        FEED_INSTAGRAM_REELS,
        FEED_INSTAGRAM_STORIES,
        FEED_WHATSAPP_STATUS,
        FEED_WHATSAPP_CHANNELS,
        FEED_SNAPCHAT_SPOTLIGHT,
        FEED_SNAPCHAT_STORIES,
        FEED_WABUSINESS_STATUS,
        FEED_WABUSINESS_CHANNELS
    )

    private const val MAX_KEYWORD_LENGTH = 100
    private const val MAX_KEYWORDS = 200

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // --- keywords -------------------------------------------------------------------------

    fun keywordsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEYWORDS_ENABLED, false)

    fun setKeywordsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEYWORDS_ENABLED, enabled).apply()
    }

    /** Keywords the user added, lower-cased and sorted. Empty when the user added none. */
    fun keywords(context: Context): List<String> =
        prefs(context).getStringSet(KEY_KEYWORDS, emptySet())
            .orEmpty().sorted()

    /** Adds a keyword (trimmed, lower-cased). Returns false when it was blank or already listed. */
    fun addKeyword(context: Context, raw: String): Boolean {
        val keyword = raw.trim().lowercase()
        if (keyword.isEmpty() || keyword.length > MAX_KEYWORD_LENGTH) return false
        val current = prefs(context).getStringSet(KEY_KEYWORDS, emptySet()).orEmpty().toMutableSet()
        if (!current.add(keyword) || current.size > MAX_KEYWORDS) return false
        prefs(context).edit().putStringSet(KEY_KEYWORDS, current).apply()
        return true
    }

    fun removeKeyword(context: Context, keyword: String) {
        val current = prefs(context).getStringSet(KEY_KEYWORDS, emptySet()).orEmpty().toMutableSet()
        if (current.remove(keyword)) {
            prefs(context).edit().putStringSet(KEY_KEYWORDS, current).apply()
        }
    }

    // --- reels / shorts ---------------------------------------------------------------------

    fun reelsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REELS_ENABLED, false)

    fun setReelsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REELS_ENABLED, enabled).apply()
    }

    fun feedEnabled(context: Context, feedKey: String): Boolean =
        prefs(context).getBoolean(feedKey, false)

    fun setFeedEnabled(context: Context, feedKey: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(feedKey, enabled).apply()
    }
}
