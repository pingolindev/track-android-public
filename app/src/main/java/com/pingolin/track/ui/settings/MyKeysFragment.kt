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
import androidx.navigation.fragment.findNavController
import com.pingolin.track.R
import com.pingolin.track.databinding.FragmentMyKeysBinding
import com.pingolin.track.util.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import uniffi.pingolin_mobile.CryptoManager
import javax.inject.Inject

/**
 * This device's own public identity: the two public keys and the user id that
 * make up our half of a safety number.
 *
 * There is deliberately no "my safety number" here, because there is no such
 * value. A safety number is computed over BOTH parties' material — see
 * `CryptoManager::compute_safety_number`, which hashes
 * `(user_id ‖ sharing_pk ‖ signing_pk)` for each side — so it only exists for a
 * pair. What this screen shows is the input the other side combines with their
 * own to get the number both of you compare, on Circles → member → Security
 * details.
 *
 * Nothing here is secret: all three values are published to the server so peers
 * can wrap circle keys for us. The value of showing them is that a member who
 * suspects a substituted key can read their own back and compare.
 */
@AndroidEntryPoint
class MyKeysFragment : Fragment() {

    @Inject
    lateinit var cryptoManager: CryptoManager

    @Inject
    lateinit var sessionManager: SessionManager


    private var _binding: FragmentMyKeysBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentMyKeysBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        // exportPublicKey() hands back base64, while every peer key in the app
        // (member security details, the wrap/rotation payloads) is hex — render
        // ours in hex too so the two are actually comparable by eye.
        val sharingHex = runCatching { base64ToHex(cryptoManager.exportPublicKey()) }.getOrNull()
        val signingHex = runCatching { cryptoManager.signingPublicKeyHex() }.getOrNull()
        val userId = sessionManager.getUserId()

        binding.tvSharingPubkey.text = sharingHex ?: getString(com.pingolin.track.R.string.my_keys_unavailable)
        binding.tvSigningPubkey.text = signingHex ?: getString(com.pingolin.track.R.string.my_keys_unavailable)
        binding.tvUserId.text = userId ?: getString(com.pingolin.track.R.string.my_keys_unavailable)

        binding.btnCopy.setOnClickListener {
            val text = buildString {
                appendLine(getString(com.pingolin.track.R.string.my_keys_sharing_label))
                appendLine(binding.tvSharingPubkey.text)
                appendLine()
                appendLine(getString(com.pingolin.track.R.string.my_keys_signing_label))
                appendLine(binding.tvSigningPubkey.text)
                appendLine()
                appendLine(getString(com.pingolin.track.R.string.my_keys_user_id_label))
                append(binding.tvUserId.text)
            }
            val clipboard = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("security details", text).apply {
                // Public material, but still identity-bearing: keep it out of the
                // Android 13+ clipboard preview and clipboard history. Mirrors
                // MemberDetailFragment's safety-number copy.
                description.extras = PersistableBundle().apply {
                    putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                }
            }
            clipboard.setPrimaryClip(clip)
            Toast.makeText(requireContext(), "Copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun base64ToHex(b64: String): String =
        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            .joinToString("") { "%02x".format(it) }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
