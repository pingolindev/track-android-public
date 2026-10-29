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

package com.pingolin.track.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.pingolin.track.R
import com.pingolin.track.WorkManagerInitRule
import com.pingolin.track.ui.auth.AuthActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.Matchers.not
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Espresso UI tests for AuthActivity.
 *
 * The screen only signs in: registration belongs to the account portal, and the
 * "Sign up" link (`tvSwitchMode`) opens the portal's register page in a browser
 * rather than switching this screen to a sign-up form. These tests cover:
 * - The sign-in screen displays correctly
 * - The email and password fields accept input
 * - The submit button's enabled state
 * - The email-verification view starts hidden
 *
 * These tests use Hilt's test module to inject mock dependencies,
 * allowing testing of UI behavior without real network calls.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class AuthActivityTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    /**
     * AuthActivity reaches WorkManager.getInstance, and HiltTestApplication —
     * unlike TrackApplication — is not a Configuration.Provider, so without
     * this every test here dies with "WorkManager is not initialized properly".
     * SettingsFragmentTest and MapFragmentTest carry the same rule.
     */
    @get:Rule(order = 1)
    val workManagerRule = WorkManagerInitRule()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    // ===== Login Screen Display Tests =====

    @Test
    fun loginScreen_displaysCorrectly() {
        ActivityScenario.launch(AuthActivity::class.java).use {
            // Verify title displays
            onView(withId(R.id.tvTitle))
                .check(matches(isDisplayed()))
                .check(matches(withText("Welcome back")))

            // Verify subtitle displays
            onView(withId(R.id.tvSubtitle))
                .check(matches(isDisplayed()))
                .check(matches(withText("Sign in to continue")))

            // Verify email input displays
            onView(withId(R.id.tilEmail))
                .check(matches(isDisplayed()))

            onView(withId(R.id.etEmail))
                .check(matches(isDisplayed()))

            // Verify password input displays
            onView(withId(R.id.tilPassword))
                .check(matches(isDisplayed()))

            onView(withId(R.id.etPassword))
                .check(matches(isDisplayed()))

            // Verify submit button displays with correct text
            onView(withId(R.id.btnSubmit))
                .check(matches(isDisplayed()))
                .check(matches(withText("Sign in")))
                .check(matches(isEnabled()))

            // Verify the sign-up link displays
            onView(withId(R.id.tvSwitchMode))
                .check(matches(isDisplayed()))
                .check(matches(withText("Don't have an account? Sign up")))

            // Verify progress bar is initially hidden
            onView(withId(R.id.progressBar))
                .check(matches(not(isDisplayed())))
        }
    }

    @Test
    fun loginScreen_emailFieldAcceptsInput() {
        ActivityScenario.launch(AuthActivity::class.java).use {
            val testEmail = TestData.VALID_EMAIL

            onView(withId(R.id.etEmail))
                .perform(typeText(testEmail), closeSoftKeyboard())
                .check(matches(withText(testEmail)))
        }
    }

    @Test
    fun loginScreen_passwordFieldAcceptsInput() {
        ActivityScenario.launch(AuthActivity::class.java).use {
            val testPassword = TestData.VALID_PASSWORD

            onView(withId(R.id.etPassword))
                .perform(typeText(testPassword), closeSoftKeyboard())

            // Note: Password field shows dots, so we can't check the text directly
            // But we verify the field is still displayed and functional
            onView(withId(R.id.etPassword))
                .check(matches(isDisplayed()))
        }
    }

    // ===== Submit Button State Tests =====

    @Test
    fun submitButton_initiallyEnabled() {
        ActivityScenario.launch(AuthActivity::class.java).use {
            onView(withId(R.id.btnSubmit))
                .check(matches(isEnabled()))
        }
    }

    @Test
    fun submitButton_remainsEnabledWithInput() {
        ActivityScenario.launch(AuthActivity::class.java).use {
            // Enter email and password
            onView(withId(R.id.etEmail))
                .perform(typeText(TestData.VALID_EMAIL), closeSoftKeyboard())

            onView(withId(R.id.etPassword))
                .perform(typeText(TestData.VALID_PASSWORD), closeSoftKeyboard())

            // Verify button is still enabled
            onView(withId(R.id.btnSubmit))
                .check(matches(isEnabled()))
        }
    }

    // ===== Email Verification Screen Tests =====

    @Test
    fun emailVerificationContainer_initiallyHidden() {
        ActivityScenario.launch(AuthActivity::class.java).use {
            // Verify email verification container is initially hidden
            onView(withId(R.id.emailVerificationContainer))
                .check(matches(not(isDisplayed())))

            // Verify main auth form is visible
            onView(withId(R.id.authFormContainer))
                .check(matches(isDisplayed()))
        }
    }

    // ===== Input Field Interaction Tests =====

    @Test
    fun emailField_canBeCleared() {
        ActivityScenario.launch(AuthActivity::class.java).use {
            // Enter email
            onView(withId(R.id.etEmail))
                .perform(typeText(TestData.VALID_EMAIL), closeSoftKeyboard())
                .check(matches(withText(TestData.VALID_EMAIL)))

            // Clear the field using custom action
            onView(withId(R.id.etEmail))
                .perform(EspressoTestUtils.setTextDirectly(""))
                .check(matches(withText("")))
        }
    }

    @Test
    fun fullLoginFlow_enterCredentials() {
        ActivityScenario.launch(AuthActivity::class.java).use {
            // Enter email
            onView(withId(R.id.etEmail))
                .perform(typeText(TestData.VALID_EMAIL), closeSoftKeyboard())

            // Enter password
            onView(withId(R.id.etPassword))
                .perform(typeText(TestData.VALID_PASSWORD), closeSoftKeyboard())

            // Verify submit button is enabled and ready
            onView(withId(R.id.btnSubmit))
                .check(matches(isEnabled()))
                .check(matches(isDisplayed()))
        }
    }

    // ===== Accessibility Tests =====

    @Test
    fun allInteractiveElements_areEnabled() {
        ActivityScenario.launch(AuthActivity::class.java).use {
            // Check email field
            onView(withId(R.id.etEmail))
                .check(matches(isEnabled()))

            // Check password field
            onView(withId(R.id.etPassword))
                .check(matches(isEnabled()))

            // Check submit button
            onView(withId(R.id.btnSubmit))
                .check(matches(isEnabled()))

            // Check the sign-up link
            onView(withId(R.id.tvSwitchMode))
                .check(matches(isEnabled()))
        }
    }
}
