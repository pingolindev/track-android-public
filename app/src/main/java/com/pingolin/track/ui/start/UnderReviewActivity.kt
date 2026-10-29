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

package com.pingolin.track.ui.start

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pingolin.track.BuildConfig
import com.pingolin.track.R
import com.pingolin.track.domain.repository.AuthRepository
import com.pingolin.track.ui.auth.AuthActivity
import com.pingolin.track.ui.main.MainActivity
import com.pingolin.track.util.AccountRecovery
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Track's start for an assisted-lane account an admin hasn't approved yet: the web's words in
 * place of the app, and Sign Out. Until the approval, the auth server's token carries no Track
 * audience, so Track refuses every call, and the app would have only empty screens and errors to
 * show. It looks again by itself while it is on screen, at once and then every minute, and opens
 * Track once the account is approved. For an account an admin turned down, the same screen says
 * so and how to reach support, and looks no more: there is nothing to wait for. Its two
 * singletons come through an entry point, as [CantStartActivity]'s does.
 */
class UnderReviewActivity : AppCompatActivity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Deps {
        fun authRepository(): AuthRepository
        fun sessionManager(): SessionManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        val sessionManager = deps.sessionManager()
        // Made again from recents, this start had none of MainActivity's checks: the store's first.
        if (sessionManager.storeRefusal() != null) {
            CantStartActivity.showFor(this)
            return
        }
        val authRepository = deps.authRepository()
        setContentView(R.layout.activity_under_review)
        findViewById<TextView>(R.id.tvUnderReviewAccount).text =
            getString(R.string.under_review_signed_in_as, sessionManager.getEmail().orEmpty())

        // Waiting or turned down: the portal's public support page, which needs no sign-in, as the
        // portal's own under-review screen and iOS offer it. This account's token reaches no Track.
        findViewById<Button>(R.id.btnNotApprovedSupport).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AccountRecovery.publicSupportUrl(BuildConfig.ACCOUNT_BASE_URL))))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.recovery_no_browser, Toast.LENGTH_SHORT).show()
            }
        }
        val rejected = sessionManager.isRegistrationRejected()
        if (rejected) {
            findViewById<TextView>(R.id.tvUnderReviewTitle).setText(R.string.not_approved_title)
            findViewById<TextView>(R.id.tvUnderReviewBody).setText(R.string.not_approved_body)
        }
        val looking = if (rejected) null else lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                when (waitForApproval(authRepository::lookAtReview, sessionManager::isLoggedIn)) {
                    Ended.APPROVED -> openFresh(MainActivity::class.java)
                    // Turned down meanwhile: shown again, as such.
                    Ended.REJECTED -> openFresh(UnderReviewActivity::class.java)
                    Ended.SIGNED_OUT -> openFresh(AuthActivity::class.java)
                }
            }
        }
        val signOut = findViewById<Button>(R.id.btnUnderReviewSignOut)
        signOut.setOnClickListener {
            signOut.isEnabled = false
            looking?.cancel()
            lifecycleScope.launch {
                // Finished even if this screen goes meanwhile (a turn of the phone).
                withContext(NonCancellable) { authRepository.logout() }
                openFresh(AuthActivity::class.java)
            }
        }
    }

    /** [target] in a fresh task, with this screen and anything under it gone. */
    private fun openFresh(target: Class<out Activity>) {
        applicationContext.startActivity(
            Intent(applicationContext, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    internal enum class Ended { APPROVED, REJECTED, SIGNED_OUT }

    companion object {
        /** How often the screen looks again while it is on screen. */
        internal const val LOOK_EVERY_MS = 60_000L

        /**
         * Looks at once and then every [LOOK_EVERY_MS] until the account stops waiting (approved, or
         * turned down), or is signed out (a session ended elsewhere). A look that couldn't be made opens
         * nothing.
         */
        internal suspend fun waitForApproval(look: suspend () -> AuthRepository.Review, signedIn: () -> Boolean): Ended {
            while (true) {
                val seen = look()
                if (!signedIn()) return Ended.SIGNED_OUT
                if (seen == AuthRepository.Review.NOT_WAITING) return Ended.APPROVED
                if (seen == AuthRepository.Review.REJECTED) return Ended.REJECTED
                delay(LOOK_EVERY_MS)
            }
        }

        /**
         * Shows this in place of [activity], which finishes before its start goes any further: the
         * task's only screen. A launcher tap while it shows starts Track again, which lands here
         * again; on top of the task, each tap would stack one more of these.
         */
        fun show(activity: Activity) {
            activity.startActivity(
                Intent(activity, UnderReviewActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            activity.finish()
        }
    }
}
