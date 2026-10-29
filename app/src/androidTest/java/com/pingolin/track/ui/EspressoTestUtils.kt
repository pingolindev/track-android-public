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

import android.view.View
import android.widget.EditText
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import com.google.android.material.textfield.TextInputLayout
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.TypeSafeMatcher

/**
 * Test utilities for Espresso UI tests.
 *
 * Provides custom matchers and actions for testing Material Design components
 * and common UI patterns used in the Track app.
 */
object EspressoTestUtils {

    /**
     * Custom matcher for TextInputLayout error text.
     * Used to verify validation error messages on input fields.
     */
    fun hasTextInputLayoutError(expectedError: String): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("has TextInputLayout error: $expectedError")
            }

            override fun matchesSafely(view: View): Boolean {
                if (view !is TextInputLayout) return false
                return view.error?.toString() == expectedError
            }
        }
    }

    /**
     * Custom matcher for TextInputLayout hint text.
     */
    fun hasTextInputLayoutHint(expectedHint: String): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("has TextInputLayout hint: $expectedHint")
            }

            override fun matchesSafely(view: View): Boolean {
                if (view !is TextInputLayout) return false
                return view.hint?.toString() == expectedHint
            }
        }
    }

    /**
     * Custom matcher for checking if a View has a specific content description.
     */
    fun hasContentDescription(expectedDescription: String): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("has content description: $expectedDescription")
            }

            override fun matchesSafely(view: View): Boolean {
                return view.contentDescription?.toString() == expectedDescription
            }
        }
    }

    /**
     * Custom matcher for checking if EditText has specific input type.
     */
    fun hasInputType(inputType: Int): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("has input type: $inputType")
            }

            override fun matchesSafely(view: View): Boolean {
                if (view !is EditText) return false
                return view.inputType == inputType
            }
        }
    }

    /**
     * Custom matcher for checking if a view is enabled.
     */
    fun isEnabled(): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("is enabled")
            }

            override fun matchesSafely(view: View): Boolean {
                return view.isEnabled
            }
        }
    }

    /**
     * Custom matcher for checking if a view is not enabled.
     */
    fun isNotEnabled(): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("is not enabled")
            }

            override fun matchesSafely(view: View): Boolean {
                return !view.isEnabled
            }
        }
    }

    /**
     * Custom matcher for MaterialSwitch checked state.
     */
    fun isSwitchChecked(): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("is switch checked")
            }

            override fun matchesSafely(view: View): Boolean {
                if (view is com.google.android.material.materialswitch.MaterialSwitch) {
                    return view.isChecked
                }
                if (view is android.widget.CompoundButton) {
                    return view.isChecked
                }
                return false
            }
        }
    }

    /**
     * Custom matcher for MaterialSwitch unchecked state.
     */
    fun isSwitchUnchecked(): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("is switch unchecked")
            }

            override fun matchesSafely(view: View): Boolean {
                if (view is com.google.android.material.materialswitch.MaterialSwitch) {
                    return !view.isChecked
                }
                if (view is android.widget.CompoundButton) {
                    return !view.isChecked
                }
                return false
            }
        }
    }

    /**
     * Custom action to wait for a specified duration.
     * Useful for waiting for animations or async operations.
     */
    fun waitFor(millis: Long): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> {
                return isDisplayed()
            }

            override fun getDescription(): String {
                return "wait for $millis milliseconds"
            }

            override fun perform(uiController: UiController, view: View) {
                uiController.loopMainThreadForAtLeast(millis)
            }
        }
    }

    /**
     * Custom action to type text into an EditText, clearing it first.
     */
    fun clearAndTypeText(text: String): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> {
                return allOf(isDisplayed(), isAssignableFrom(EditText::class.java))
            }

            override fun getDescription(): String {
                return "clear text and type: $text"
            }

            override fun perform(uiController: UiController, view: View) {
                val editText = view as EditText
                editText.setText("")
                editText.append(text)
            }
        }
    }

    /**
     * Custom action to set text directly on an EditText (bypasses IME).
     * Useful when typeText doesn't work well with certain input types.
     */
    fun setTextDirectly(text: String): ViewAction {
        return object : ViewAction {
            override fun getConstraints(): Matcher<View> {
                return allOf(isDisplayed(), isAssignableFrom(EditText::class.java))
            }

            override fun getDescription(): String {
                return "set text directly: $text"
            }

            override fun perform(uiController: UiController, view: View) {
                val editText = view as EditText
                editText.setText(text)
            }
        }
    }

    /**
     * Custom matcher for checking view visibility is GONE.
     */
    fun isGone(): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("view visibility is GONE")
            }

            override fun matchesSafely(view: View): Boolean {
                return view.visibility == View.GONE
            }
        }
    }

    /**
     * Custom matcher for checking view visibility is INVISIBLE.
     */
    fun isInvisible(): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("view visibility is INVISIBLE")
            }

            override fun matchesSafely(view: View): Boolean {
                return view.visibility == View.INVISIBLE
            }
        }
    }

    /**
     * Custom matcher for checking if a Chip is checked.
     */
    fun isChipChecked(): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("chip is checked")
            }

            override fun matchesSafely(view: View): Boolean {
                if (view is com.google.android.material.chip.Chip) {
                    return view.isChecked
                }
                return false
            }
        }
    }

    /**
     * Custom matcher for checking if a Chip is not checked.
     */
    fun isChipNotChecked(): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("chip is not checked")
            }

            override fun matchesSafely(view: View): Boolean {
                if (view is com.google.android.material.chip.Chip) {
                    return !view.isChecked
                }
                return false
            }
        }
    }

    /**
     * Matcher that checks if a view has at least one child.
     */
    fun hasChildren(): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("has children")
            }

            override fun matchesSafely(view: View): Boolean {
                if (view is android.view.ViewGroup) {
                    return view.childCount > 0
                }
                return false
            }
        }
    }

    /**
     * Custom matcher for checking if RecyclerView has items.
     */
    fun recyclerViewHasItems(): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("RecyclerView has items")
            }

            override fun matchesSafely(view: View): Boolean {
                if (view is androidx.recyclerview.widget.RecyclerView) {
                    return (view.adapter?.itemCount ?: 0) > 0
                }
                return false
            }
        }
    }

    /**
     * Custom matcher for checking RecyclerView item count.
     */
    fun recyclerViewHasItemCount(count: Int): Matcher<View> {
        return object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("RecyclerView has $count items")
            }

            override fun matchesSafely(view: View): Boolean {
                if (view is androidx.recyclerview.widget.RecyclerView) {
                    return view.adapter?.itemCount == count
                }
                return false
            }
        }
    }
}

/**
 * Test data constants for UI tests.
 */
object TestData {
    // Valid test credentials
    const val VALID_EMAIL = "test@pingolin.test"
    const val VALID_PASSWORD = "TestPassword123!"

    // Invalid test inputs
    const val INVALID_EMAIL = "invalid-email"
    const val SHORT_PASSWORD = "short"
    const val EMPTY_STRING = ""

    // Test location data
    const val TEST_LATITUDE = 37.7749
    const val TEST_LONGITUDE = -122.4194
    const val TEST_CIRCLE_NAME = "Family Circle"
    const val TEST_PLACE_NAME = "Home"

    // Timeouts
    const val SHORT_WAIT_MS = 500L
    const val MEDIUM_WAIT_MS = 1000L
    const val LONG_WAIT_MS = 3000L
    const val ANIMATION_WAIT_MS = 300L
}
