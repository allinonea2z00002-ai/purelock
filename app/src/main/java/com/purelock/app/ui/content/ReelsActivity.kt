package com.purelock.app.ui.content

import android.os.Bundle
import com.google.android.material.materialswitch.MaterialSwitch
import com.purelock.app.data.prefs.ContentFilterStore
import com.purelock.app.databinding.ActivityReelsBinding
import com.purelock.app.ui.ThemeManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Block Reels/Shorts: one master switch plus a switch per feed (YouTube Shorts, Instagram
 * Reels/Stories, WhatsApp Status/Channels, Snapchat Spotlight/Stories, WA Business Status/Channels).
 * Enforcement is best-effort: the accessibility service looks for the feed's label on screen
 * ("Shorts", "Reels", ...). Apps can rename or hide those labels, so some feeds may still appear.
 */
class ReelsActivity : AppCompatActivity() {

    private lateinit var b: ActivityReelsBinding

    /** Feed switch view -> its ContentFilterStore key. */
    private lateinit var feedSwitches: List<Pair<MaterialSwitch, String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityReelsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }

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

        b.switchReelsMaster.setOnClickListener {
            ContentFilterStore.setReelsEnabled(this, b.switchReelsMaster.isChecked)
            refresh()
        }
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
        val masterOn = ContentFilterStore.reelsEnabled(this)
        b.switchReelsMaster.isChecked = masterOn
        for ((switch, key) in feedSwitches) {
            switch.isChecked = ContentFilterStore.feedEnabled(this, key)
            // Per-feed switches only do anything while the master switch is on.
            switch.isEnabled = masterOn
        }
    }
}
