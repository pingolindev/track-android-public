/*
 * Copyright (c) 2026 Pingolin LLC
 * This file is part of Pingolin Track.
 *
 * Pingolin Track is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Pingolin Track is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Pingolin Track. If not, see <https://www.gnu.org/licenses/>.
 */

package com.pingolin.track.ui.settings

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.pingolin.track.R
import com.pingolin.track.databinding.FragmentBlockedPeopleBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Settings → Blocked people: addresses whose owners may not invite this person to a circle
 * (the words are in strings.xml `blocked_people_*`).
 */
@AndroidEntryPoint
class BlockedPeopleFragment : Fragment() {

    private var _binding: FragmentBlockedPeopleBinding? = null
    private val binding get() = _binding!!

    private val viewModel: BlockedPeopleViewModel by viewModels()
    private lateinit var adapter: BlockedPeopleAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentBlockedPeopleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        adapter = BlockedPeopleAdapter { viewModel.unblock(it.blockedEmail) }
        binding.recyclerBlocked.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerBlocked.adapter = adapter

        binding.etEmail.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateBlockButton()
        })
        binding.btnBlock.setOnClickListener { viewModel.block(typed()) }
        binding.etEmail.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                viewModel.block(typed())
                true
            } else {
                false
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.collectLatest { render(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collectLatest { event ->
                if (event != null) {
                    // The block was taken: the field is free for the next address.
                    binding.etEmail.text?.clear()
                    viewModel.consumeEvent()
                }
            }
        }
    }

    private fun typed(): String = binding.etEmail.text?.toString().orEmpty()

    /** On only for an address there is something to do with (BlockedPeople.addressToBlock), and never while a request is out. */
    private fun updateBlockButton() {
        binding.btnBlock.isEnabled = !viewModel.state.value.busy && viewModel.addressToBlock(typed()) != null
    }

    private fun render(state: BlockedPeopleUiState) {
        val people = state.people
        binding.loadingContainer.visibility = if (state.loading && people == null) View.VISIBLE else View.GONE
        // A full list in its own words; anything else that failed, as it was put.
        binding.tvFailure.text = if (state.listFull) getString(R.string.blocked_people_full) else state.failure.orEmpty()
        binding.tvFailure.visibility = if (state.listFull || state.failure != null) View.VISIBLE else View.GONE
        // A list that has not been read is not shown as "nobody is blocked".
        binding.tvEmpty.visibility = if (people != null && people.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerBlocked.visibility = if (!people.isNullOrEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(people.orEmpty())
        updateBlockButton()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
