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

package com.pingolin.track.ui.circles

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.PersistableBundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.pingolin.track.R
import com.pingolin.track.databinding.FragmentMemberDetailBinding
import com.pingolin.track.domain.repository.CircleRepository
import com.pingolin.track.util.MemberVerificationStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Member detail screen with safety number (SAS) verification.
 *
 * Required arguments (passed via [Bundle]):
 *  - `circle_id`: String
 *  - `user_id`: String
 *
 * The display name is read off the cached member list via the
 * repository; sharing/signing pubkeys come from the repo's per-circle
 * cache populated during [CircleRepository.refreshMembers].
 *
 * "Mark verified" is a local-only mark (MemberVerificationStore, by
 * circle and member): the SDK keeps no verification timestamps.
 */
@AndroidEntryPoint
class MemberDetailFragment : Fragment() {

    @Inject
    lateinit var circleRepository: CircleRepository

    @Inject
    lateinit var sessionManager: com.pingolin.track.util.SessionManager

    private var _binding: FragmentMemberDetailBinding? = null
    private val binding get() = _binding!!

    private lateinit var circleId: String
    private lateinit var userId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        circleId = requireArguments().getString(ARG_CIRCLE_ID)
            ?: throw IllegalStateException("circle_id required")
        userId = requireArguments().getString(ARG_USER_ID)
            ?: throw IllegalStateException("user_id required")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMemberDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        binding.btnCopy.setOnClickListener {
            val number = binding.tvSafetyNumber.text?.toString() ?: return@setOnClickListener
            val clipboard = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("security code", number).apply {
                // SECURITY: flag the clip sensitive so Android 13+ omits the cleartext
                // preview in the clipboard UI and keeps the safety number out of clipboard
                // history. EXTRA_IS_SENSITIVE is a compile-time String constant (inlined),
                // harmlessly ignored on pre-33 devices (minSdk 26).
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
        }

        binding.btnTechnicalDetails.setOnClickListener {
            val nowVisible = binding.groupTechnicalDetails.visibility != View.VISIBLE
            binding.groupTechnicalDetails.visibility = if (nowVisible) View.VISIBLE else View.GONE
            binding.btnTechnicalDetails.setText(
                if (nowVisible) R.string.member_detail_hide_technical_details
                else R.string.member_detail_show_technical_details
            )
        }

        // The "verified" switch is bound once the member's keys are known, below: a
        // mark vouches for exactly the keys that were compared.
        binding.switchVerified.isEnabled = false

        // Resolve member display name + keys from the repo, then compute SAS.
        viewLifecycleOwner.lifecycleScope.launch {
            circleRepository.observeMembers(circleId).collect { members ->
                val member = members.firstOrNull { it.userId == userId } ?: return@collect
                renderHeader(member.displayName)

                val sharingHex = circleRepository.cachedMemberSharingPubkey(circleId, userId).orEmpty()
                val signingHex = circleRepository.cachedMemberSigningPubkey(circleId, userId).orEmpty()
                renderTechnicalDetails(sharingHex, signingHex)
                bindVerifiedSwitch(sharingHex, signingHex)

                val sas = if (sharingHex.isNotEmpty() && signingHex.isNotEmpty()) {
                    circleRepository.computeSafetyNumber(userId, sharingHex, signingHex)
                } else {
                    null
                }
                binding.tvSafetyNumber.text = sas
                    ?: "Safety number unavailable — member hasn't migrated to the new identity scheme yet."
            }
        }
    }

    private fun renderHeader(displayName: String) {
        val name = displayName.ifBlank { "Unknown" }
        binding.tvDisplayName.text = name
        binding.tvSubtitle.text = "Compare with $name out of band before continuing"
        // Both hints name the screen on the OTHER device that shows the same
        // value, so the number and the hex each have a stated counterpart. The
        // safety number is pairwise and has no equivalent on the Encryption
        // screen; the hex does, and matches it character for character.
        binding.tvSafetyNumberHint.text =
            getString(R.string.member_detail_safety_number_hint, name)
        binding.tvTechnicalDetailsHint.text =
            getString(R.string.member_detail_technical_hint, name)
    }

    private fun renderTechnicalDetails(sharingHex: String, signingHex: String) {
        binding.tvSharingPubkey.text = sharingHex.ifBlank { "—" }
        binding.tvSigningPubkey.text = signingHex.ifBlank { "—" }
    }

    /**
     * Local-only "verified" marker for the keys on screen, persisted through the
     * shared MemberVerificationStore so the approve-key-change path clears it from the
     * same source of truth. Bound to [sharingHex] and [signingHex]: once the member's
     * keys change the switch reads off, however the mark survived (marks are kept
     * across a sign-out of the same account).
     */
    private fun bindVerifiedSwitch(sharingHex: String, signingHex: String) {
        val owner = sessionManager.getUserId().orEmpty()
        val canVerify = owner.isNotEmpty() && sharingHex.isNotEmpty() && signingHex.isNotEmpty()
        binding.switchVerified.setOnCheckedChangeListener(null)
        val verifiedAt = if (canVerify) {
            MemberVerificationStore.getVerifiedAt(requireContext(), owner, circleId, userId, sharingHex, signingHex)
        } else {
            0L
        }
        binding.switchVerified.isChecked = verifiedAt > 0L
        binding.switchVerified.isEnabled = canVerify
        renderVerifiedSubtitle(verifiedAt)
        binding.switchVerified.setOnCheckedChangeListener { _, checked ->
            val now = if (checked) System.currentTimeMillis() else 0L
            if (checked) {
                MemberVerificationStore.setVerified(
                    requireContext(), owner, circleId, userId, sharingHex, signingHex, now
                )
            } else {
                MemberVerificationStore.clearVerified(requireContext(), owner, circleId, userId)
            }
            renderVerifiedSubtitle(now)
        }
    }

    private fun renderVerifiedSubtitle(verifiedAt: Long) {
        binding.tvVerifiedSubtitle.text = if (verifiedAt > 0L) {
            val formatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            "Verified ${formatter.format(Date(verifiedAt))}"
        } else {
            "Not yet verified out of band"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val ARG_CIRCLE_ID = "circle_id"
        const val ARG_USER_ID = "user_id"
    }
}
