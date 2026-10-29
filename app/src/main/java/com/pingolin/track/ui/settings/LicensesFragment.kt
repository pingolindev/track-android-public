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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.pingolin.track.databinding.FragmentLicensesBinding

/**
 * "Open source licenses" — the third-party attribution notices, as required by
 * the licences of the bundled components (Apache-2.0 section 4(d) among them).
 *
 * The content is `assets/open_source_licenses.html`, generated together with the
 * repo-root THIRD_PARTY_LICENSES.md by `./gradlew :app:generateThirdPartyLicenses`.
 * Both are committed, so this screen works offline and on a fresh install.
 */
class LicensesFragment : Fragment() {

    private var _binding: FragmentLicensesBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLicensesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }

        with(binding.webViewLicenses) {
            // Static local document: nothing here needs scripting, and the page
            // is never allowed to reach the filesystem or a content provider.
            // Assets stay readable regardless — setAllowFileAccess(false) gates
            // the file system only, not file:///android_asset.
            settings.javaScriptEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false

            // The page ships light and dark palettes behind prefers-color-scheme.
            // WebView only honours that once darkening is allowed; the app drives
            // night mode through AppCompatDelegate (TrackApplication.applyThemeMode),
            // so this tracks the user's Light/Dark/System choice in Settings.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
            }

            // The notices link out to licence texts and project pages. Hand those
            // to the browser rather than navigating this WebView, so the screen
            // itself stays a fixed offline document.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    openExternally(request.url)
                    return true
                }
            }

            loadUrl(ASSET_URL)
        }
    }

    private fun openExternally(uri: Uri) {
        // Only web links; the generated page contains nothing else, and this
        // keeps a malformed URL from launching an arbitrary component.
        if (uri.scheme != "http" && uri.scheme != "https") return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            // No browser installed — the licence URLs are also in the committed
            // THIRD_PARTY_LICENSES.md, so there is nothing to recover here.
        }
    }

    override fun onDestroyView() {
        // A WebView outlives its fragment unless it is detached and destroyed by
        // hand, and it holds the whole view tree with it.
        binding.webViewLicenses.apply {
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val ASSET_URL = "file:///android_asset/open_source_licenses.html"
    }
}
