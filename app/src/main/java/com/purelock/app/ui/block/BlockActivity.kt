package com.purelock.app.ui.block

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.purelock.app.Graph
import com.purelock.app.R
import com.purelock.app.databinding.ActivityBlockBinding
import com.purelock.app.service.BlockReason
import com.purelock.app.ui.ThemeManager
import com.purelock.app.util.Motivation

/**
 * Shown when a blocked app is opened. It always offers the way out: go home, call emergency services
 * and (if set) call the trusted contact. Nothing here can trap the user.
 */
class BlockActivity : AppCompatActivity() {

    private lateinit var b: ActivityBlockBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityBlockBinding.inflate(layoutInflater)
        setContentView(b.root)
        bind(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        b.btnHome.setOnClickListener { goHome() }
        // The dialer only opens the dial pad: the user places the call themselves.
        b.btnEmergencyCall.setOnClickListener {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (Graph.secure.hasTrustedContact()) {
            val name = Graph.secure.trustedName.ifBlank { Graph.secure.trustedPhone }
            b.btnTrusted.isVisible = true
            b.btnTrusted.text = getString(R.string.block_call_trusted, name)
            b.btnTrusted.setOnClickListener {
                startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Graph.secure.trustedPhone}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind(intent)
    }

    private fun bind(intent: Intent) {
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val reason = runCatching { BlockReason.valueOf(intent.getStringExtra(EXTRA_REASON).orEmpty()) }
            .getOrDefault(BlockReason.LOCK)
        b.tvTitle.text = getString(
            if (reason == BlockReason.SELF_PROTECT) R.string.block_title_protected else R.string.block_title,
            label
        )
        b.tvReason.setText(
            when (reason) {
                BlockReason.LOCK -> R.string.block_reason_lock
                BlockReason.DAILY_LIMIT, BlockReason.HOURLY_LIMIT -> R.string.block_reason_limit
                BlockReason.TIME_INTERVAL, BlockReason.QUICK_BLOCK, BlockReason.LAUNCH_LIMIT,
                BlockReason.WAIT_TIMER, BlockReason.GOAL, BlockReason.LOCATION -> R.string.block_reason_condition
                BlockReason.SELF_PROTECT -> R.string.block_reason_protect
            }
        )
        b.tvMotivation.text = Motivation.random(this)
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_REASON = "reason"
    }
}
