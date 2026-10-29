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

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.pingolin.track.R
import com.pingolin.track.domain.repository.AuthRepository
import com.pingolin.track.domain.repository.AuthRepository.Review
import com.pingolin.track.ui.auth.AuthActivity
import com.pingolin.track.ui.main.MainActivity
import com.pingolin.track.util.AccountRecovery
import com.pingolin.track.util.SessionManager
import dagger.hilt.internal.GeneratedComponent
import dagger.hilt.internal.GeneratedComponentManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

/**
 * Track's start for an assisted-lane account an admin hasn't approved yet. The web's words in place
 * of an app whose every Track call is refused, and Sign Out; it looks again by itself while on
 * screen, at once and then every minute, and opens Track once the account is approved. The real
 * activity runs here, its two singletons handed out by a test application the way Hilt's are.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = UnderReviewScreenTest.TestApp::class)
class UnderReviewScreenTest {

    /** Hands out the screen's entry point, as Hilt's application component does. */
    class TestApp : Application(), GeneratedComponentManager<Any> {
        private val component = object : GeneratedComponent, UnderReviewActivity.Deps {
            override fun authRepository(): AuthRepository {
                authRepositoryAsked++
                return auth
            }
            override fun sessionManager(): SessionManager = session
        }

        override fun generatedComponent(): Any = component
    }

    companion object {
        lateinit var auth: AuthRepository
        lateinit var session: SessionManager
        var authRepositoryAsked = 0
    }

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** What each look answers, in turn; WAITING once they run out. */
    private val answers = ArrayDeque<Review>()
    private var looked = 0
    private var signedIn = true

    @Before
    fun setUp() {
        auth = mockk(relaxed = true)
        session = mockk(relaxed = true)
        authRepositoryAsked = 0
        every { session.isLoggedIn() } answers { signedIn }
        every { session.getEmail() } returns "dana@pingolin.test"
        coEvery { auth.lookAtReview() } coAnswers {
            looked++
            answers.removeFirstOrNull() ?: Review.WAITING
        }
        coEvery { auth.logout() } coAnswers {
            signedIn = false
            Result.success(Unit)
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun nextOpened(): Intent? = shadowOf(app).nextStartedActivity

    private fun assertOpensFresh(target: Class<*>, opened: Intent?) {
        assertEquals(target.name, opened?.component?.className)
        val fresh = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals("in a fresh task", fresh, opened!!.flags and fresh)
    }

    @Test
    fun `the words are the web's, with no key talk`() {
        assertEquals("Your account is under review", app.getString(R.string.under_review_title))
        assertEquals(
            "Your account request is checked by an admin before access is granted. We'll email you the moment it's approved — then Track unlocks automatically.",
            app.getString(R.string.under_review_body),
        )
        assertEquals("Signed in as dana@pingolin.test", app.getString(R.string.under_review_signed_in_as, "dana@pingolin.test"))
        assertEquals("Sign Out", app.getString(R.string.settings_logout))
        for (id in listOf(R.string.under_review_title, R.string.under_review_body)) {
            val words = app.getString(id).lowercase()
            for (word in listOf("key", "token", "audience", "401")) assertFalse("no key talk: $word", words.contains(word))
        }
    }

    @Test
    fun `a turned-down account's words, with how to reach support and no key talk`() {
        assertEquals("Your application wasn't approved", app.getString(R.string.not_approved_title))
        assertEquals(
            "This account can't be used with Track. We delete it, and your application, 30 days after the decision.",
            app.getString(R.string.not_approved_body),
        )
        assertEquals("Need help?", app.getString(R.string.not_approved_need_help))
        assertEquals("Contact support", app.getString(R.string.not_approved_contact_support))
        assertEquals("https://account.pingolindev.com/public-support", AccountRecovery.publicSupportUrl("https://account.pingolindev.com/"))
        // Nothing about applying again.
        val strings = File("src/main/res/values/strings.xml").readText().lowercase()
        assertFalse(strings.contains("apply again") || strings.contains("reapply"))
        for (id in listOf(R.string.not_approved_title, R.string.not_approved_body, R.string.not_approved_need_help, R.string.not_approved_contact_support)) {
            val words = app.getString(id).lowercase()
            for (word in listOf("key", "token", "audience", "401")) assertFalse("no key talk: $word", words.contains(word))
        }
    }

    @Test
    fun `a turned-down account sees why and Sign Out, and the screen looks no more - there's nothing to wait for`() {
        every { session.isRegistrationRejected() } returns true
        val screen = Robolectric.buildActivity(UnderReviewActivity::class.java).setup().get()
        idle()

        assertEquals("Your application wasn't approved", screen.findViewById<TextView>(R.id.tvUnderReviewTitle).text.toString())
        assertEquals(app.getString(R.string.not_approved_body), screen.findViewById<TextView>(R.id.tvUnderReviewBody).text.toString())
        assertEquals("Signed in as dana@pingolin.test", screen.findViewById<TextView>(R.id.tvUnderReviewAccount).text.toString())
        assertEquals(View.VISIBLE, screen.findViewById<View>(R.id.layoutNotApprovedSupport).visibility)
        idleFor(5 * UnderReviewActivity.LOOK_EVERY_MS)
        assertEquals("no look, at once or after", 0, looked)
        assertNull(nextOpened())

        // Contact support: the portal's public page, which needs no sign-in.
        screen.findViewById<Button>(R.id.btnNotApprovedSupport).performClick()
        val support = nextOpened()
        assertEquals(Intent.ACTION_VIEW, support?.action)
        assertEquals(AccountRecovery.publicSupportUrl(com.pingolin.track.BuildConfig.ACCOUNT_BASE_URL), support?.data.toString())

        screen.findViewById<Button>(R.id.btnUnderReviewSignOut).performClick()
        idle()
        coVerify(exactly = 1) { auth.logout() }
        assertOpensFresh(AuthActivity::class.java, nextOpened())
    }

    @Test
    fun `a waiting account turned down meanwhile is shown again, as turned down, and looked at no more`() {
        answers += Review.REJECTED
        val screen = Robolectric.buildActivity(UnderReviewActivity::class.java).setup().get()
        idle()

        assertEquals(1, looked)
        assertOpensFresh(UnderReviewActivity::class.java, nextOpened())
        assertTrue(screen.isFinishing)
        idleFor(5 * UnderReviewActivity.LOOK_EVERY_MS)
        assertEquals("the waiting screen looks no more", 1, looked)
    }

    @Test
    fun `a waiting account sees why, and the screen looks again every minute until the approval opens Track`() {
        answers += listOf(Review.WAITING, Review.UNKNOWN, Review.NOT_WAITING)
        val screen = Robolectric.buildActivity(UnderReviewActivity::class.java).setup().get()
        idle()

        assertEquals("Your account is under review", screen.findViewById<TextView>(R.id.tvUnderReviewTitle).text.toString())
        assertEquals(app.getString(R.string.under_review_body), screen.findViewById<TextView>(R.id.tvUnderReviewBody).text.toString())
        assertEquals("the support line while it waits too", View.VISIBLE, screen.findViewById<View>(R.id.layoutNotApprovedSupport).visibility)
        assertEquals("Signed in as dana@pingolin.test", screen.findViewById<TextView>(R.id.tvUnderReviewAccount).text.toString())
        assertEquals("Sign Out", screen.findViewById<Button>(R.id.btnUnderReviewSignOut).text.toString())
        assertEquals("looked at once", 1, looked)
        assertNull(nextOpened())

        // In half minutes: the looper's clock moves a few milliseconds by itself as the screen is drawn.
        idleFor(UnderReviewActivity.LOOK_EVERY_MS / 2)
        assertEquals("not before the minute", 1, looked)
        idleFor(UnderReviewActivity.LOOK_EVERY_MS / 2)
        assertEquals(2, looked)
        assertNull("a look that couldn't be made opens nothing", nextOpened())
        assertFalse(screen.isFinishing)

        idleFor(UnderReviewActivity.LOOK_EVERY_MS)
        assertEquals(3, looked)
        assertOpensFresh(MainActivity::class.java, nextOpened())
        assertTrue(screen.isFinishing)
    }

    @Test
    fun `while it waits, Contact support opens the portal's public page too`() {
        val screen = Robolectric.buildActivity(UnderReviewActivity::class.java).setup().get()
        idle()
        assertEquals("Need help?", app.getString(R.string.not_approved_need_help))
        screen.findViewById<Button>(R.id.btnNotApprovedSupport).performClick()
        val support = nextOpened()
        assertEquals(Intent.ACTION_VIEW, support?.action)
        assertEquals(AccountRecovery.publicSupportUrl(com.pingolin.track.BuildConfig.ACCOUNT_BASE_URL), support?.data.toString())
        assertFalse("the screen stays", screen.isFinishing)
    }

    @Test
    fun `Sign Out ends the session and opens the sign-in, and the screen looks no more`() {
        val screen = Robolectric.buildActivity(UnderReviewActivity::class.java).setup().get()
        idle()
        val signOut = screen.findViewById<Button>(R.id.btnUnderReviewSignOut)

        signOut.performClick()
        idle()

        assertFalse("tapped once", signOut.isEnabled)
        coVerify(exactly = 1) { auth.logout() }
        assertOpensFresh(AuthActivity::class.java, nextOpened())
        assertTrue(screen.isFinishing)
        idleFor(5 * UnderReviewActivity.LOOK_EVERY_MS)
        assertEquals("no look after it", 1, looked)
        assertNull("nothing opened twice", nextOpened())
    }

    @Test
    fun `a session ended elsewhere opens the sign-in - never Track`() {
        coEvery { auth.lookAtReview() } coAnswers {
            looked++
            signedIn = false
            Review.NOT_WAITING
        }
        val screen = Robolectric.buildActivity(UnderReviewActivity::class.java).setup().get()
        idle()

        assertOpensFresh(AuthActivity::class.java, nextOpened())
        assertTrue(screen.isFinishing)
    }

    @Test
    fun `it looks only while on screen, and at once on a return`() {
        val controller = Robolectric.buildActivity(UnderReviewActivity::class.java).setup()
        idle()
        assertEquals(1, looked)

        controller.pause().stop()
        idleFor(5 * UnderReviewActivity.LOOK_EVERY_MS)
        assertEquals("nothing while away", 1, looked)

        answers += Review.NOT_WAITING
        controller.start()
        idle()
        assertEquals("at once on the return", 2, looked)
        assertOpensFresh(MainActivity::class.java, nextOpened())
    }

    @Test
    fun `a store that can't be opened shows the can't-start screen first, and asks nothing of the account`() {
        every { session.isLoggedIn() } throws SecurityException("the Keystore is down")
        val screen = Robolectric.buildActivity(UnderReviewActivity::class.java).setup().get()
        idle()

        assertEquals(CantStartActivity::class.java.name, shadowOf(screen).nextStartedActivity?.component?.className)
        assertTrue(screen.isFinishing)
        assertEquals(0, authRepositoryAsked)
        assertEquals(0, looked)
    }

    @Test
    fun `Track's start shows it as the task's only screen - a launcher tap never stacks another`() {
        val start = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java).setup().get()

        UnderReviewActivity.show(start)

        assertOpensFresh(UnderReviewActivity::class.java, shadowOf(start).nextStartedActivity)
        assertTrue(start.isFinishing)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `however long a look can't be made, Track is never opened by it`() = runTest {
        var looks = 0
        val waiting = async { UnderReviewActivity.waitForApproval({ looks++; Review.UNKNOWN }, { true }) }
        runCurrent()
        advanceTimeBy(30 * UnderReviewActivity.LOOK_EVERY_MS)
        runCurrent()
        assertTrue(waiting.isActive)
        assertEquals(31, looks)
        waiting.cancel()
    }

    @Test
    fun `Track's start shows it before anything is built or asked, and again when its own look finds the account waiting`() {
        val main = File("src/main/java/com/pingolin/track/ui/main/MainActivity.kt").readText()
        val init = main.substringAfter("private fun initializeApp() {").substringBefore("\n    }\n")
        val gate = init.indexOf("if (sessionManager.isRegistrationUnapproved()) {\n            com.pingolin.track.ui.start.UnderReviewActivity.show(this)\n            return\n        }")
        assertTrue("initializeApp is gated", gate >= 0)
        for (later in listOf("binding.mainContent.visibility", "setupNavigation()", "checkPermissions()", "premiumStatusRefresher.refresh()", "validateSessionAndInitialize()")) {
            assertTrue("gated before $later", gate < init.indexOf(later))
        }
        val start = main.substringAfter("private fun validateSessionAndInitialize() {").substringBefore("viewModel.initialize()")
        assertTrue(
            "after its own look, before anything starts",
            start.contains("if (sessionManager.isRegistrationUnapproved()) {\n                com.pingolin.track.ui.start.UnderReviewActivity.show(this@MainActivity)\n                return@launch\n            }"),
        )
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\".ui.start.UnderReviewActivity\"\n            android:exported=\"false\""))
    }
}
