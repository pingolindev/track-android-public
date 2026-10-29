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

package com.pingolin.track.ui.dialogs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.pingolin.track.BuildConfig
import com.pingolin.track.R
import com.pingolin.track.data.remote.dto.QuotaError
import com.pingolin.track.databinding.BottomSheetPremiumBinding
import com.pingolin.track.util.AccountRecovery
import com.pingolin.track.util.PlanManagement

/**
 * Shared read-only explainer shown when a free-tier user hits a subscription limit — either a
 * hard count cap (places / circles / members / place alerts) or a premium-only feature
 * (destinations). Smart alerts and browser push are free for everyone, so nothing here sells
 * them. It renders a per-resource title + one-line benefit, the E2EE reassurance line, and a
 * "Manage your plan at pingolin.com" pointer.
 *
 * In the Play (gms) build that pointer is deliberately plain text — no in-app checkout, no
 * IAP, no external-purchase deep link — because Google Play prohibits steering users to an
 * external purchase method for digital goods when not on Play Billing, and Track is not on
 * Play Billing (matches the existing destinations notice). The libre build ships outside Play,
 * so there the pointer becomes a "Manage plan" button that opens the account portal's
 * subscription page ([PlanManagement] decides per flavour).
 *
 * Drive it from a parsed 402 via [showForQuota], or directly with a resource key via [show]
 * for client-side pre-gates (e.g. the destinations button).
 */
class TrackPremiumBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPremiumBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = BottomSheetPremiumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = requireArguments()
        val resource = args.getString(ARG_RESOURCE)
        val errorCode = args.getString(ARG_ERROR_CODE)
        val (titleRes, bodyRes) = copyFor(resource, errorCode)
        binding.tvPremiumTitle.setText(titleRes)
        binding.tvPremiumBody.setText(bodyRes)

        // Usage line only for count limits with a real cap (limit > 0).
        val isCount = args.getBoolean(ARG_IS_COUNT, false)
        val current = args.getLong(ARG_CURRENT, -1)
        val limit = args.getLong(ARG_LIMIT, -1)
        if (isCount && limit > 0 && current >= 0) {
            binding.tvPremiumUsage.text = getString(R.string.premium_sheet_usage, current, limit)
            binding.tvPremiumUsage.visibility = View.VISIBLE
        } else {
            binding.tvPremiumUsage.visibility = View.GONE
        }

        if (PlanManagement.CAN_LINK_TO_PORTAL) {
            // Libre: the pointer is a button. Hide the plain line so the sheet
            // does not say the same thing twice.
            binding.tvPremiumManage.visibility = View.GONE
            binding.btnPremiumManage.visibility = View.VISIBLE
            binding.btnPremiumManage.setOnClickListener { openPortalSubscription() }
        }

        binding.btnPremiumDismiss.setOnClickListener { dismiss() }
    }

    /** Open the portal's plan page in the browser; same pattern as Settings' recovery link. */
    private fun openPortalSubscription() {
        val url = AccountRecovery.subscriptionUrl(BuildConfig.ACCOUNT_BASE_URL)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.recovery_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    /** Resolve (title, body) for a resource key, falling back to the error
     *  code when the resource is absent, then to a generic premium message. */
    private fun copyFor(resource: String?, errorCode: String?): Pair<Int, Int> {
        val key = (resource ?: "").lowercase().ifBlank { errorCode?.lowercase() ?: "" }
        return when {
            key.contains("place_alert") -> R.string.premium_place_alerts_title to R.string.premium_place_alerts_body
            key.contains("place") -> R.string.premium_places_title to R.string.premium_places_body
            key.contains("circle") -> R.string.premium_circles_title to R.string.premium_circles_body
            key.contains("member") -> R.string.premium_members_title to R.string.premium_members_body
            key.contains("destination") -> R.string.premium_destinations_title to R.string.premium_destinations_body
            key.contains("history") -> R.string.premium_history_title to R.string.premium_history_body
            key == "overview" -> R.string.premium_overview_title to R.string.premium_overview_body
            else -> R.string.premium_sheet_title_default to R.string.premium_sheet_default_body
        }
    }

    companion object {
        private const val TAG = "track_premium_sheet"
        private const val ARG_RESOURCE = "resource"
        private const val ARG_ERROR_CODE = "error_code"
        private const val ARG_IS_COUNT = "is_count_limit"
        private const val ARG_CURRENT = "current"
        private const val ARG_LIMIT = "limit"

        /**
         * Show the sheet for the given resource (e.g. `"places"`,
         * `"destinations"`). At most one instance shows at a time — repeated
         * calls during a single interaction are no-ops.
         */
        fun show(
            fm: FragmentManager,
            resource: String?,
            errorCode: String? = null,
            isCountLimit: Boolean = false,
            current: Long = -1,
            limit: Long = -1,
        ) {
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            TrackPremiumBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_RESOURCE, resource)
                    putString(ARG_ERROR_CODE, errorCode)
                    putBoolean(ARG_IS_COUNT, isCountLimit)
                    putLong(ARG_CURRENT, current)
                    putLong(ARG_LIMIT, limit)
                }
            }.show(fm, TAG)
        }

        /** Convenience for a 402 parsed into a [QuotaError] + error code. */
        fun showForQuota(fm: FragmentManager, quota: QuotaError?, errorCode: String?) {
            show(
                fm = fm,
                resource = quota?.resource,
                errorCode = errorCode,
                isCountLimit = quota?.isCountLimit ?: false,
                current = quota?.current ?: -1,
                limit = quota?.limit ?: -1,
            )
        }
    }
}
