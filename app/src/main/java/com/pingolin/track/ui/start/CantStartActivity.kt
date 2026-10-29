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
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pingolin.track.R
import com.pingolin.track.ui.main.MainActivity
import com.pingolin.track.util.DatabaseUnavailable
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.storeRefusal
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Track's start while this phone's session store can't be opened: its own words in place of the
 * app. Never the sign-in screen: nothing can tell whether anyone is signed in. It looks again by
 * itself while it is on screen, at once and then after 2 s, twice as long each time to a minute,
 * as the database guard does, and opens what was asked for once the store opens. Its last
 * paragraph, the way out of a store damaged for good, always shows: it sends the person to
 * restart first, and a refusal that passes clears the screen by itself. Hilt injects nothing
 * into this activity at creation, so nothing there can need the Keystore; the session manager
 * comes through a plain entry point.
 */
class CantStartActivity : AppCompatActivity() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface Store {
        fun sessionManager(): SessionManager
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cant_start)
        val sessionManager = EntryPointAccessors.fromApplication(applicationContext, Store::class.java).sessionManager()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var attempt = 0L
                while (true) {
                    if (sessionManager.storeRefusal() == null) {
                        openAsked()
                        return@repeatOnLifecycle
                    }
                    delay(DatabaseUnavailable.retryAfterMs(attempt++))
                }
            }
        }
    }

    /** What was asked for, now the store opens: the activity that met the refusal, with its intent. */
    private fun openAsked() {
        val asked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_ASKED, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_ASKED)
        }
        // A fresh task, so the asked activity is created again. Brought to the front instead,
        // the task that holds this screen would only come forward and close along with it.
        startActivity(
            (asked ?: Intent(this, MainActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    companion object {
        private const val EXTRA_ASKED = "asked"

        /**
         * Shows this in place of [activity], which finishes before its start goes any further; it is
         * opened again, with its own intent, once the store opens.
         */
        fun showFor(activity: Activity) {
            activity.startActivity(
                Intent(activity, CantStartActivity::class.java).putExtra(EXTRA_ASKED, Intent(activity.intent)),
            )
            activity.finish()
        }
    }
}
