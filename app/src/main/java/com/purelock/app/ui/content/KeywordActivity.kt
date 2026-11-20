package com.purelock.app.ui.content

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.purelock.app.data.prefs.ContentFilterStore
import com.purelock.app.databinding.ActivityKeywordsBinding
import com.purelock.app.databinding.ItemKeywordBinding
import com.purelock.app.ui.ThemeManager

/**
 * Keyword blocking: the user adds words or phrases; when the accessibility service sees one of
 * them in visible on-screen text, it sends the user home. Best-effort only (see the on-screen
 * note): text inside images, video, or apps that hide their content cannot be seen.
 */
class KeywordActivity : AppCompatActivity() {

    private lateinit var b: ActivityKeywordsBinding
    private val adapter = KeywordAdapter { keyword ->
        ContentFilterStore.removeKeyword(this, keyword)
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        b = ActivityKeywordsBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }
        b.rvKeywords.layoutManager = LinearLayoutManager(this)
        b.rvKeywords.adapter = adapter

        b.switchKeywords.isChecked = ContentFilterStore.keywordsEnabled(this)
        b.switchKeywords.setOnClickListener {
            ContentFilterStore.setKeywordsEnabled(this, b.switchKeywords.isChecked)
        }

        b.btnAdd.setOnClickListener { addFromInput() }
        b.etKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addFromInput()
                true
            } else false
        }
    }

    override fun onResume() {
        super.onResume()
        b.switchKeywords.isChecked = ContentFilterStore.keywordsEnabled(this)
        refresh()
    }

    private fun addFromInput() {
        val text = b.etKeyword.text?.toString().orEmpty()
        if (ContentFilterStore.addKeyword(this, text)) {
            b.etKeyword.text?.clear()
        }
        refresh()
    }

    private fun refresh() {
        val keywords = ContentFilterStore.keywords(this)
        adapter.submitList(keywords)
        b.tvEmpty.isVisible = keywords.isEmpty()
    }

    private class KeywordAdapter(
        private val onRemove: (String) -> Unit
    ) : ListAdapter<String, KeywordAdapter.ViewHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemKeywordBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(private val binding: ItemKeywordBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(keyword: String) {
                binding.tvKeyword.text = keyword
                binding.btnRemove.setOnClickListener { onRemove(keyword) }
            }
        }

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<String>() {
                override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
                override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
            }
        }
    }
}
