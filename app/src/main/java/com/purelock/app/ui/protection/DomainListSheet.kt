package com.purelock.app.ui.protection

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.purelock.app.R
import com.purelock.app.databinding.ItemDomainBinding
import com.purelock.app.databinding.SheetDomainsBinding
import kotlinx.coroutines.launch

/** Bottom sheet that edits either the custom blacklist or the custom allowlist. */
class DomainListSheet : BottomSheetDialogFragment() {

    private var _binding: SheetDomainsBinding? = null
    private val b get() = _binding!!
    private val vm: DomainListViewModel by viewModels()
    private val isBlacklist get() = requireArguments().getBoolean(ARG_BLACKLIST)

    private val adapter = DomainAdapter { domain ->
        vm.remove(isBlacklist, domain) { result -> if (result == DomainResult.LOCKED) toastLocked() }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = SheetDomainsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.tvSheetTitle.setText(if (isBlacklist) R.string.sheet_blacklist_title else R.string.sheet_allowlist_title)
        b.tvSheetHint.setText(if (isBlacklist) R.string.sheet_blacklist_hint else R.string.sheet_allowlist_hint)
        b.rvDomains.layoutManager = LinearLayoutManager(requireContext())
        b.rvDomains.adapter = adapter

        fun submit() {
            val text = b.etDomain.text?.toString().orEmpty()
            if (text.isBlank()) return
            vm.add(isBlacklist, text) { result ->
                when (result) {
                    DomainResult.ADDED -> { b.etDomain.text?.clear(); b.tilDomain.error = null }
                    DomainResult.INVALID -> b.tilDomain.error = getString(R.string.domain_invalid)
                    DomainResult.LOCKED -> toastLocked()
                    DomainResult.REMOVED -> Unit
                }
            }
        }
        b.btnAddDomain.setOnClickListener { submit() }
        b.etDomain.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val flow = if (isBlacklist) vm.blocked else vm.allowed
                flow.collect { list ->
                    adapter.submitList(list)
                    b.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun toastLocked() {
        Toast.makeText(requireContext(), R.string.msg_locked_cannot_change, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private class DomainAdapter(private val onRemove: (String) -> Unit) :
        ListAdapter<String, DomainAdapter.Holder>(object : DiffUtil.ItemCallback<String>() {
            override fun areItemsTheSame(a: String, b: String) = a == b
            override fun areContentsTheSame(a: String, b: String) = a == b
        }) {

        class Holder(val binding: ItemDomainBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemDomainBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val domain = getItem(position)
            holder.binding.tvDomain.text = domain
            holder.binding.btnRemove.setOnClickListener { onRemove(domain) }
        }
    }

    companion object {
        private const val ARG_BLACKLIST = "blacklist"
        fun newInstance(blacklist: Boolean) = DomainListSheet().apply {
            arguments = bundleOf(ARG_BLACKLIST to blacklist)
        }
    }
}
