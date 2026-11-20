package com.purelock.app.ui.content

import android.os.Bundle
import com.google.android.material.switchmaterial.SwitchMaterial
import com.purelock.app.data.prefs.ContentFilterStore
import com.purelock.app.databinding.ActivityReelsBinding
import com.purelock.app.ui.ThemeManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Block Reels/Shorts (SPEC S11): brand groups with dotted connectors, one toggle per
 * feed, no Save button — toggles persist immediately. The exact ON/OFF defaults from
 * the screenshot (YouTube Shorts ON, Instagram Reels ON, Snapchat Spotlight ON, all
 * others OFF) are seeded on first open only; after that the user's choices win.
 * The reelsEnabled master lives on the Dashboard Reels toggle (the accessibility
 * service only enforces feeds while it is on); this screen has no master switch,
 * matching S11.
 *
 * Enforcement is best-effort: the accessibility service looks for the feed's label on
 * screen ("Shorts", "Reels", ...). Apps can rename or hide those labels, so some feeds
 * may still appear.
 */
class ReelsActivity : AppCompatActivity() {

    private lateinit var b: ActivityReelsBinding

    /** Feed switch view -> its ContentFilterStore key. */
    private lateinit var feedSwitches: List<Pair<SwitchMaterial, String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityReelsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.btnBack.setOnClickListener { finish() }

        feedSwitches = listOf(
            b.switchYoutubeShorts to ContentFilterStore.FEED_YOUTUBE_SHORTS,
            b.switchInstagramReels to ContentFilterStore.FEED_INSTAGRAM_REELS,
            b.switchInstagramStories to ContentFilterStore.FEED_INSTAGRAM_STORIES,
            b.switchWhatsappStatus to ContentFilterStore.FEED_WHATSAPP_STATUS,
            b.switchWhatsappChannels to ContentFilterStore.FEED_WHATSAPP_CHANNELS,
            b.switchSnapchatSpotlight to ContentFilterStore.FEED_SNAPCHAT_SPOTLIGHT,
            b.switchSnapchatStories to ContentFilterStore.FEED_SNAPCHAT_STORIES,
            b.switchWaBusinessStatus to ContentFilterStore.FEED_WABUSINESS_STATUS,
            b.switchWaBusinessChannels to ContentFilterStore.FEED_WABUSINESS_CHANNELS
        )

        seedDefaultsOnce()
        for ((switch, key) in feedSwitches) {
            switch.setOnClickListener {
                ContentFilterStore.setFeedEnabled(this, key, switch.isChecked)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        for ((switch, key) in feedSwitches) {
            switch.isChecked = ContentFilterStore.feedEnabled(this, key)
        }
    }

    /** S11 defaults, applied once: Shorts, Instagram Reels and Snapchat Spotlight ON. */
    private fun seedDefaultsOnce() {
        val prefs = getSharedPreferences(SEED_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) return
        ContentFilterStore.setFeedEnabled(this, ContentFilterStore.FEED_YOUTUBE_SHORTS, true)
        ContentFilterStore.setFeedEnabled(this, ContentFilterStore.FEED_INSTAGRAM_REELS, true)
        ContentFilterStore.setFeedEnabled(this, ContentFilterStore.FEED_SNAPCHAT_SPOTLIGHT, true)
        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }

    companion object {
        private const val SEED_PREFS = "purelock_content_filters"
        private const val KEY_SEEDED = "reels_defaults_seeded"
    }
}
