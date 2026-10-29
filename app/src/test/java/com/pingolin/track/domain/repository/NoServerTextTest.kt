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

package com.pingolin.track.domain.repository

import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.dto.AlreadyExistsException
import com.pingolin.track.data.remote.dto.ApiErrors
import com.pingolin.track.data.remote.dto.ApiResponse
import com.pingolin.track.data.remote.dto.EncryptedBlobResponse
import com.pingolin.track.data.remote.dto.EncryptedKeysResponse
import com.pingolin.track.data.remote.dto.KdfParamsResponse
import com.pingolin.track.data.remote.dto.QuotaError
import com.pingolin.track.data.remote.dto.QuotaException
import com.pingolin.track.data.remote.dto.ReauthStep2Response
import com.pingolin.track.data.remote.dto.SrpStep1Response
import com.pingolin.track.util.AuthRateLimiter
import com.pingolin.track.util.RateLimitResult
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.SrpClientSession
import com.pingolin.track.util.SrpStep2Result
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import uniffi.pingolin_mobile.CryptoManager

/**
 * App-wide: no server `error`/`message` text ever reaches a toast or dialog. A refusal is
 * told in this app's words: by its `error_code`, else its HTTP status, else a plain "Couldn't
 * … just now." The server's words can carry key talk ("at_version is not this circle's
 * current key version"), or whatever a hacked server wants a person to read; they are for
 * logs.
 */
class NoServerTextTest {

    /** What a server might say. None of it is ever on screen. */
    private val said = "Invalid membership statement: at_version is not this circle's current key version. Call 0800 555 0100"

    private fun body(code: String? = null, extra: Map<String, Any> = emptyMap()) =
        com.google.gson.Gson().toJson(
            mapOf("success" to false, "error" to said) + (code?.let { mapOf("error_code" to it) } ?: emptyMap()) + extra
        ).toResponseBody(null)

    private fun <T> refused(status: Int, code: String? = null): Response<T> = Response.error(status, body(code))

    private fun assertNotTheServers(line: String?) {
        assertFalse("the server's words reached the screen: $line", line.orEmpty().contains("at_version") || line.orEmpty().contains("0800"))
    }

    // ===== The lines, by code and by status =====

    @Test
    fun `a refusal is told by its code, else by its status, else by what didn't happen`() {
        val fallback = "Couldn't do that just now."
        val cases = listOf(
            Triple(402, null, ApiErrors.PLAN_LIMIT),
            Triple(400, "quota_places_exceeded", ApiErrors.PLAN_LIMIT),
            Triple(403, "premium_required_destinations", ApiErrors.PLAN_LIMIT),
            Triple(429, null, ApiErrors.TOO_MANY),
            Triple(400, "rate_limit_exceeded", ApiErrors.TOO_MANY),
            Triple(500, null, ApiErrors.TROUBLE),
            Triple(503, "server_unavailable", ApiErrors.TROUBLE),
            Triple(409, "server_timeout", ApiErrors.TROUBLE),
            // The status first: auth's rate limiter being down is trouble, not too many tries.
            Triple(503, "rate_limiter_unavailable", ApiErrors.TROUBLE),
            Triple(403, null, ApiErrors.NOT_ALLOWED),
            Triple(400, "auth_not_circle_admin", ApiErrors.NOT_ALLOWED),
            Triple(400, "auth_insufficient_permissions", ApiErrors.NOT_ALLOWED),
            Triple(404, null, ApiErrors.GONE),
            Triple(400, "circle_not_found", ApiErrors.GONE),
            Triple(400, null, fallback),
            Triple(400, "invite_invalid_statement", fallback),
            Triple(409, "invite_lookup_changed", fallback),
        )
        for ((status, code, line) in cases) {
            assertEquals("$status $code", line, ApiErrors.plainLine(status, code, fallback))
        }
        // The same from a response: its status and its error body's code.
        assertEquals(ApiErrors.GONE, ApiErrors.plainLine(refused<Unit>(400, "place_not_found"), fallback))
        assertEquals(fallback, ApiErrors.plainLine(refused<Unit>(400), fallback))
    }

    @Test
    fun `the exception for a refusal says this app's words, and still carries the premium prompt's facts`() {
        val quota = ApiErrors.toException(
            Response.error<Unit>(402, body("quota_places_exceeded", mapOf("quota" to mapOf(
                "resource" to "places", "is_count_limit" to true, "current" to 2, "limit" to 2, "tier" to "free",
            )))),
            "Couldn't save the place just now.",
        ) as QuotaException
        assertEquals(ApiErrors.PLAN_LIMIT, quota.message)
        assertEquals("quota_places_exceeded", quota.errorCode)
        assertEquals(QuotaError("places", true, 2, 2, "free"), quota.quota)

        val exists = ApiErrors.toException(refused<Unit>(409, "PLACE_ALREADY_EXISTS"), "Couldn't save the place just now.")
        assertTrue(exists is AlreadyExistsException)
        assertEquals("Couldn't save the place just now.", exists.message)

        assertEquals("Couldn't save the place just now.", ApiErrors.toException(refused<Unit>(400), "Couldn't save the place just now.").message)
    }

    @Test
    fun `a refusal's fields are read in a JVM test too`() {
        // org.json is a stub under the JVM tests: a parse through it reads nothing
        // there, so a refusal by its code (here an owner's) would look like any
        // other.
        val parsed = ApiErrors.parse("""{"success":false,"error":"x","error_code":"circle_owner_cannot_leave"}""")
        assertEquals("circle_owner_cannot_leave", parsed?.errorCode)
        assertEquals("x", parsed?.error)
        assertEquals(null, ApiErrors.parse("not json"))
        assertEquals(null, ApiErrors.parse("""{"success":false}"""))
    }

    // ===== Signing in and the account =====

    private val authApi = mockk<AuthApi>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val cryptoManager = mockk<CryptoManager>(relaxed = true)
    private val srp = mockk<SrpClientSession>()
    private val rateLimiter = mockk<AuthRateLimiter>(relaxed = true)
    private lateinit var auth: AuthRepository

    private fun blob(tag: String) = EncryptedBlobResponse(nonce = "n-$tag", ciphertext = "c-$tag", keyId = "v1")

    private fun step1() = Response.success(ApiResponse(success = true, data = SrpStep1Response(
        salt = "salt", serverPublic = "B", sessionId = "sid", bcryptSalt = null, email = "dana@x.test",
    ), error = null))

    @Before
    fun setUp() {
        every { rateLimiter.checkAttempt(any()) } returns RateLimitResult.Allowed
        every { sessionManager.getEmail() } returns "dana@x.test"
        every { sessionManager.getUserId() } returns "user-dana"
        every { srp.step1(any(), any(), any()) } just Runs
        every { srp.step2(any(), any()) } returns SrpStep2Result(clientPublic = "A", clientProof = "M1")
        every { srp.step3("M2") } returns true
        coEvery { authApi.srpStep1(any()) } returns step1()
        coEvery { authApi.reauthStep1() } returns step1()
        coEvery { authApi.reauthStep2(any()) } returns Response.success(ApiResponse(success = true, data = ReauthStep2Response(
            reauthToken = "reauth", serverProof = "M2",
            kdfParams = KdfParamsResponse(salt = "kdf", memoryKib = 65536, iterations = 3, parallelism = 1),
            encryptedKeys = EncryptedKeysResponse(blob("master"), blob("sharing"), blob("signing")),
            sharingPublicKey = null, signingPublicKey = null,
        ), error = null))
        auth = AuthRepository(
            context = mockk(relaxed = true),
            authApi = authApi,
            tracksApi = mockk(relaxed = true),
            database = mockk(relaxed = true),
            userDao = mockk(relaxed = true),
            circleDao = mockk(relaxed = true),
            sessionManager = sessionManager,
            cryptoManager = cryptoManager,
            keystoreEncryption = mockk(relaxed = true),
            databasePassphraseManager = mockk(relaxed = true),
            encryptedPrefs = mockk(relaxed = true),
            rateLimiter = rateLimiter,
            locationRepository = mockk(relaxed = true),
            circleRepository = mockk(relaxed = true),
            quotaManager = mockk(relaxed = true),
            boundaryRepository = mockk(relaxed = true),
            monitoringManager = mockk(relaxed = true),
            hiddenDestinations = mockk(relaxed = true),
            geocodeTokenManager = mockk(relaxed = true),
            crashReporter = mockk(relaxed = true),
            retainedTrust = mockk(relaxed = true),
            keyStoreRecovery = mockk(relaxed = true),
            alertRulesReload = mockk(relaxed = true),
        )
        auth.srpSessions = { srp }
    }

    @Test
    fun `a refused sign-in is the credentials, too many tries, or trouble - never the server's sentence`() = runTest {
        val cases = listOf(
            Triple(401, "invalid_credentials", "Invalid email or password"),
            Triple(403, null, "Invalid email or password"),
            Triple(400, null, "Invalid email or password"),
            Triple(429, "rate_limited", ApiErrors.TOO_MANY),
            Triple(503, "server_unavailable", ApiErrors.TROUBLE),
        )
        for ((status, code, line) in cases) {
            coEvery { authApi.srpStep2(any()) } returns refused(status, code)
            val result = auth.loginSrp("dana@x.test", "correct horse".toCharArray())
            assertEquals("$status", line, (result as LoginResult.Error).message)
        }
    }

    @Test
    fun `an address never verified is told so at sign-in, not a wrong password`() = runTest {
        coEvery { authApi.srpStep2(any()) } returns refused(403, "email_not_verified")

        val result = auth.loginSrp("dana@x.test", "correct horse".toCharArray()) as LoginResult.Error

        assertEquals("Please verify your email address first. Open the link we sent you, then sign in again.", result.message)
        io.mockk.verify(exactly = 0) { rateLimiter.recordFailedAttempt(any()) }

        // Any other 403 is still the credentials.
        coEvery { authApi.srpStep2(any()) } returns refused(403)
        assertEquals("Invalid email or password", (auth.loginSrp("dana@x.test", "correct horse".toCharArray()) as LoginResult.Error).message)
    }

    @Test
    fun `a refused two-factor code is said in this app's words, and only a wrong or replayed one keeps the attempt`() = runTest {
        data class Case(val status: Int, val code: String?, val line: String, val attemptStillOpen: Boolean)
        // The auth server's replies on /login/2fa.
        val cases = listOf(
            // "Invalid 2FA code": the attempt survives until the 5th.
            Case(401, null, "That code isn't right. Please try again.", true),
            // "Code already used. Please wait for a new code."
            Case(400, "totp_replay", "That code was just used. Wait for the next one and try again.", true),
            // "Too many 2FA attempts for this token. Please start a new login."
            Case(429, "rate_limited", "Too many wrong codes. Please wait a while, then sign in again.", false),
            // "Invalid or expired 2FA token"
            Case(400, null, "That sign-in has ended. Please sign in again.", false),
            Case(503, "rate_limiter_unavailable", ApiErrors.TROUBLE, false),
        )
        for (c in cases) {
            coEvery { authApi.verify2fa(any()) } returns refused(c.status, c.code)
            val result = auth.verify2fa("2fa-token", "000000", "dana@x.test", "correct horse".toCharArray()) as LoginResult.Error
            assertEquals("${c.status}", c.line, result.message)
            assertEquals("${c.status}", c.attemptStillOpen, result.attemptStillOpen)
        }
    }

    @Test
    fun `unspent credit is asked about - its code is read from the refusal, in this app's words`() = runTest {
        // The auth server answers 409 `unspent_credit_balance`. The code must be read from
        // the refusal's error body: body() is never filled for a refusal, so reading it
        // there would skip the question and show the server's text.
        coEvery { authApi.deleteAccount(any()) } returns refused(409, "unspent_credit_balance")

        val asked = auth.deleteAccount("correct horse".toCharArray()).exceptionOrNull()

        assertTrue("expected the question, got $asked", asked is UnspentCreditException)
        assertEquals(AuthRepository.UNSPENT_CREDIT_TEXT, asked?.message)

        coEvery { authApi.deleteAccount(any()) } returns refused(400)
        assertEquals("Couldn't delete your account just now.", auth.deleteAccount("correct horse".toCharArray()).exceptionOrNull()?.message)
        coEvery { authApi.deleteAccount(any()) } returns refused(503, "server_unavailable")
        assertEquals(ApiErrors.TROUBLE, auth.deleteAccount("correct horse".toCharArray()).exceptionOrNull()?.message)
    }

    @Test
    fun `the email checks say what didn't happen`() = runTest {
        coEvery { authApi.getVerificationStatus() } returns refused(400)
        coEvery { authApi.resendVerification() } returns refused(400)
        coEvery { authApi.verifyEmail(any()) } returns refused(400)

        assertEquals("Couldn't check your email just now.", auth.getVerificationStatus().exceptionOrNull()?.message)
        assertEquals("Couldn't send the email just now.", auth.resendVerification().exceptionOrNull()?.message)
        assertEquals("Couldn't verify your email just now.", auth.verifyEmail("t").exceptionOrNull()?.message)

        coEvery { authApi.resendVerification() } returns refused(429, "rate_limited")
        assertEquals(ApiErrors.TOO_MANY, auth.resendVerification().exceptionOrNull()?.message)
    }

    // ===== Circles =====

    @Test
    fun `a refused invitation is told in this app's words`() = runTest {
        // Not `said`: a refusal naming at_version means this phone is behind, a case of its own.
        val chatter = "Your account is locked. Call 0800 555 0100"
        val h = TrustHarness()
        h.earlierSeed("me", "alice")
        coEvery { h.tracksApi.lookupPublicKey(any()) } returns Response.error(404, """{"success":false}""".toResponseBody(null))
        fun answer(status: Int, code: String?, text: String) = Response.error<ApiResponse<com.pingolin.track.data.remote.dto.InviteResponse>>(
            status,
            com.google.gson.Gson().toJson(mapOf("success" to false, "error" to text) + (code?.let { mapOf("error_code" to it) } ?: emptyMap()))
                .toResponseBody(null),
        )
        val cases = listOf(
            // The server's plain 400s for an email invitation pick a line by their words.
            Triple(answer(400, null, "This user is already a member of this circle"), "They're already in this circle.", "already"),
            Triple(answer(400, null, "This email already has a pending invite"), "They already have an invitation to this circle.", "pending"),
            Triple(answer(400, null, "Invalid email address"), "That email address doesn't look right.", "email"),
            Triple(answer(400, null, "Email must contain only ASCII characters"), "That email address doesn't look right.", "ascii"),
            // A coded refusal goes by its code, whatever its words say.
            Triple(answer(400, "invite_invalid_statement", "Invalid membership statement: it names another email than this invitation is sent to"),
                CircleRepository.INVITE_NOT_SENT, "statement"),
            Triple(answer(400, "circle_already_member", chatter), "They're already in this circle.", "coded member"),
            Triple(answer(402, "quota_members_exceeded", chatter), ApiErrors.PLAN_LIMIT, "plan"),
            Triple(answer(500, null, chatter), ApiErrors.TROUBLE, "trouble"),
            Triple(answer(400, null, chatter), CircleRepository.INVITE_NOT_SENT, "anything else"),
            // Past the inviter's allowance of invitation e-mails: its own 429 and its
            // own line, which can't promise "a moment". Any other 429 still can.
            Triple(answer(429, "invite_limit_reached", "You've invited this address three times in the last 24 hours. Try again later."),
                CircleRepository.INVITE_LIMIT, "invite cap"),
            Triple(answer(429, "rate_limit_exceeded", chatter), ApiErrors.TOO_MANY, "a moment's wait"),
        )
        for ((response, line, why) in cases) {
            coEvery { h.tracksApi.inviteToCircle("c-1", any()) } returns response
            val message = h.repository.inviteToCircle("c-1", "dan@x.test").exceptionOrNull()?.message
            assertEquals(why, line, message)
            assertNotTheServers(message)
        }
        // The windows slide (a fourth e-mail can wait until this time tomorrow), so no day is named.
        assertEquals("Too many invitations in the last 24 hours. Try again later.", CircleRepository.INVITE_LIMIT)
    }

    @Test
    fun `the circle routes' refusals are told by kind or by what didn't happen`() = runTest {
        val h = TrustHarness()
        h.earlierSeed("me", "alice")
        val api = h.tracksApi
        val repository = h.repository
        coEvery { api.deleteCircle("c-1") } returns refused(400)
        coEvery { api.revokeInvite("c-1", "i-1") } returns refused(403, "auth_not_circle_admin")
        coEvery { api.declineInvite("i-1") } returns refused(404, "invite_not_found")
        coEvery { api.getReceivedInvites() } returns refused(500)
        coEvery { api.getCircleInvites("c-1") } returns refused(400)
        coEvery { api.transferOwnership("c-1", any()) } returns refused(400)
        coEvery { api.getOwnershipClaim("c-1") } returns refused(404)
        coEvery { api.claimOwnership("c-1") } returns refused(400)
        coEvery { api.refuseOwnershipClaim("c-1") } returns refused(403)
        coEvery { api.withdrawOwnershipClaim("c-1") } returns refused(400, "something_new")
        // A withdrawal names its statement from the owner's list: i-1's was read while
        // the list answered; i-2's can't be, so it isn't withdrawn.
        val invite = h.invitation("dan", by = "me", id = "inv-dan")
        coEvery { api.getCircleInvites("c-1") } returns Response.success(ApiResponse(success = true, data = listOf(
            com.pingolin.track.data.remote.dto.PendingInviteResponse(
                id = "i-1", inviteType = "email", targetEmail = "dan@x.test", status = "pending",
                createdDayBucket = 0, expiresDayBucket = 7, statement = invite.statementHex,
                statementSignature = invite.signatureHex, targetUserId = "dan",
            ),
        ), error = null))
        assertTrue(repository.refreshPendingInvites("c-1").isSuccess)
        coEvery { api.getCircleInvites("c-1") } returns refused(400)

        val lines = listOf(
            "Couldn't delete the circle just now." to repository.deleteCircle("c-1"),
            ApiErrors.NOT_ALLOWED to repository.revokeInvite("c-1", "i-1"),
            "Couldn't withdraw the invitation just now." to repository.revokeInvite("c-1", "i-2"),
            ApiErrors.GONE to repository.declineInvite("i-1"),
            ApiErrors.TROUBLE to repository.fetchReceivedInvites(),
            "Couldn't load the invitations just now." to repository.refreshPendingInvites("c-1"),
            "Couldn't hand the circle over just now." to repository.transferOwnership("c-1", "alice"),
            ApiErrors.GONE to repository.getOwnershipClaim("c-1"),
            // The claim route's one 400.
            "You already own this circle." to repository.claimOwnership("c-1"),
            ApiErrors.NOT_ALLOWED to repository.refuseOwnershipClaim("c-1"),
            "Couldn't withdraw the claim just now." to repository.withdrawOwnershipClaim("c-1"),
        )
        for ((line, result) in lines) {
            assertEquals(line, result.exceptionOrNull()?.message)
            assertNotTheServers(result.exceptionOrNull()?.message)
        }

        // A claim that already exists is shown as that claim, still in this app's words.
        coEvery { api.claimOwnership("c-1") } returns refused(409, "CIRCLE_OWNERSHIP_CLAIM_EXISTS")
        val exists = repository.claimOwnership("c-1").exceptionOrNull()
        assertTrue(exists is com.pingolin.track.data.remote.dto.OwnershipClaimExistsException)
        assertEquals("Somebody has already asked to take over this circle", exists?.message)
    }

    // ===== The sweep, kept =====

    /**
     * The same for a failure's own text: the SDK's words are key talk, a socket's are for
     * developers. In the screens and their view models a failure's `message` is read through
     * `plainText` (util/PlainText.kt) or on a log line, never shown as it is.
     */
    @Test
    fun `no screen shows a failure's own message`() {
        // A Result's failure too: `result.exceptionOrNull()?.message` would show the SDK's key talk,
        // or a refused certificate's words, on a screen.
        val read = Regex("""(\b(e|it|err|ex|exception|throwable|cause|failure|lastFailure|t|error)\??|\bexceptionOrNull\(\)(\?|!!)?)\.(message|localizedMessage)\b""")
        // It reads what it must: a sweep that matches nothing passes as clean.
        for (shown in listOf("e.message", "it?.message", "result.exceptionOrNull()?.message", "r.exceptionOrNull()!!.localizedMessage")) {
            assertTrue(shown, read.containsMatchIn(shown))
        }
        val logLine = Regex("""\bLog\.[dviwe]\(""")
        val allowed = mapOf(
            // A form field's own check (AuthField), in this app's words: not a failure.
            "AuthActivity.kt" to listOf(
                "AuthField.EMAIL -> binding.tilEmail.error = error.message",
                "AuthField.PASSWORD -> binding.tilPassword.error = error.message",
            ),
        )
        val found = mutableMapOf<String, MutableList<String>>()
        java.io.File("src/main/java/com/pingolin/track/ui").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().map { it.trim() }
                .filter { !it.startsWith("//") && !it.startsWith("*") }
                .filter { read.containsMatchIn(it) && !logLine.containsMatchIn(it) }
                .forEach { found.getOrPut(file.name) { mutableListOf() }.add(it) }
        }
        val unexpected = found.flatMap { (file, lines) ->
            val left = allowed[file].orEmpty().toMutableList()
            lines.filterNot { left.remove(it) }.map { "$file: $it" }
        }
        assertEquals("a failure's own text reaches the screen", emptyList<String>(), unexpected)
    }

    /**
     * Every read of a server's refusal text in the app's sources is on a log line, or one of
     * the reads below, each of which feeds only a log, a parse or a decision: never a
     * message. A new read fails here until it is one of those.
     */
    @Test
    fun `no read of a server's refusal text feeds anything but a log, a parse or a decision`() {
        val read = Regex("""errorBody\(\)|\b(?:body\(\)|body|apiResponse|refusal|parsed)\??\.error\b(?![A-Za-z_])""")
        val logLine = Regex("""\bLog\.[dviwe]\(""")
        val allowed = mapOf(
            // Read once, for the log line after it and ApiErrors.parse.
            "CircleRepository.kt" to listOf(
                "val errorBody = response.errorBody()?.string()",
                "val errorBody = response.errorBody()?.string()",
                "val errorBody = response.errorBody()?.string()",
                "val refusal = ApiErrors.parse(runCatching { response.errorBody()?.string() }.getOrNull())",
                "val refusal = ApiErrors.parse(runCatching { response.errorBody()?.string() }.getOrNull())",
                // Picks which of this app's lines (inviteRefusal) and spots at_version.
                "val serverError = response.body()?.error ?: refusal?.error",
                // Decides whether the member list says this user was removed; logged.
                "val error = response.body()?.error ?: response.errorBody()?.string() ?: \"Unknown error\"",
                // errorCodeOf.
                "response.errorBody()?.string()?.let { body ->",
                "val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()",
                "val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()",
                "val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()",
                // A rotation that failed: logged only.
                "val error = response.body()?.error ?: response.message() ?: \"Unknown error\"",
                // The rotation's 429 wait.
                "val error = runCatching { response.errorBody()?.string() }.getOrNull().orEmpty()",
                // isNoPrivacyRow: compared with the server's no-row words, never shown;
                // decides whether a 404 is its default posture.
                "val body = runCatching { response.errorBody()?.string() }.getOrNull() ?: return false",
            ),
            "DestinationRepository.kt" to listOf(
                "val errorBody = response.errorBody()?.string()",
                "val errorBody = response.errorBody()?.string()?.take(500) ?: \"\"",
                "val apiError = response.body()?.error",
            ),
            "AuthRepository.kt" to listOf(
                // The sign-in's step-1 refusal: its code, with its status, picks too many tries or
                // trouble.
                "runCatching { step1Response.errorBody()?.string() }.getOrNull()",
                // The sign-in's refusal: `email_not_verified` is said as such.
                "runCatching { step2Response.errorBody()?.string() }.getOrNull()",
                // The code step's refusal: its code decides which of this
                // app's lines.
                "val code = com.pingolin.track.data.remote.dto.ApiErrors.parse(runCatching { response.errorBody()?.string() }.getOrNull())?.errorCode",
                // The token refresh's log.
                "val serverMsg = body?.error",
                "?: response.errorBody()?.string()",
                // refusalOf: its code decides; its words are compared, never shown.
                "val body = response.errorBody()?.string().orEmpty()",
                "com.pingolin.track.data.remote.dto.ApiErrors.parse(runCatching { response.errorBody()?.string() }.getOrNull())",
            ),
            "LocationRepository.kt" to listOf(
                // Inside a Log.w( … ) over three lines.
                "\"Self key not published (HTTP \${uploadResponse.code()}: \${uploadResponse.body()?.error}) \" +",
                // Device registration: logged only.
                "val error = response.body()?.error ?: response.errorBody()?.string() ?: \"Unknown error\"",
            ),
            "AttestationManager.kt" to listOf("val errorBody = verifyResponse.errorBody()?.string()"),
            "PushTokenSyncWorker.kt" to listOf("val error = response.body()?.error ?: \"Unknown error\""),
            // ApiErrors itself: parse and plainLine.
            "PremiumError.kt" to listOf(
                "plainLine(response.code(), runCatching { parse(response.errorBody()?.string())?.errorCode }.getOrNull(), fallback)",
                "parse(response.errorBody()?.string())",
                // codeOf: the code a waiting change's run decides
                // by.
                "fun codeOf(response: Response<*>): String? = runCatching { parse(response.errorBody()?.string())?.errorCode }.getOrNull()",
            ),
        )
        val found = mutableMapOf<String, MutableList<String>>()
        java.io.File("src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().map { it.trim() }
                .filter { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
                .filter { read.containsMatchIn(it) && !logLine.containsMatchIn(it) }
                .forEach { found.getOrPut(file.name) { mutableListOf() }.add(it) }
        }
        val unexpected = found.flatMap { (file, lines) ->
            val left = allowed[file].orEmpty().toMutableList()
            lines.filterNot { left.remove(it) }.map { "$file: $it" }
        }
        assertEquals("server text read outside a log, a parse or a decision", emptyList<String>(), unexpected)
        // The list stays honest: every allowed read is still there.
        val stale = allowed.flatMap { (file, lines) ->
            val left = found[file].orEmpty().toMutableList()
            lines.filterNot { left.remove(it) }.map { "$file: $it" }
        }
        assertEquals("allowed reads that are gone (drop them here)", emptyList<String>(), stale)
    }
}
