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

import com.pingolin.track.data.local.TrackDatabase
import com.pingolin.track.data.local.dao.CircleDao
import com.pingolin.track.data.local.dao.UserDao
import com.pingolin.track.data.local.entity.UserEntity
import com.pingolin.track.data.remote.GeocodeTokenManager
import com.pingolin.track.data.remote.api.AuthApi
import com.pingolin.track.data.remote.api.TrackApi
import com.pingolin.track.data.remote.dto.UpdateTracksProfileRequest
import com.pingolin.track.data.remote.dto.KdfParamsRequest
import com.pingolin.track.data.remote.dto.RefreshTokenRequest
import com.pingolin.track.data.remote.dto.SrpStep1Request
import com.pingolin.track.data.remote.dto.SrpStep2Request
import com.pingolin.track.data.remote.dto.TwoFactorVerifyRequest
import com.pingolin.track.data.remote.dto.VerifyEmailRequest
import com.pingolin.track.data.remote.dto.EmailVerificationStatusResponse
import com.pingolin.track.util.AccountRecovery
import com.pingolin.track.util.AuthRateLimiter
import com.pingolin.track.util.CrashReporter
import com.pingolin.track.util.DatabasePassphraseManager
import com.pingolin.track.util.KeystoreEncryption
import com.pingolin.track.util.MonitoringManager
import com.pingolin.track.util.RateLimitResult
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.SigningKeyCheck
import com.pingolin.track.util.SrpClient
import com.pingolin.track.util.plainText
import org.maplibre.android.offline.OfflineManager
import com.pingolin.track.worker.LocationSyncWorker
import com.pingolin.track.worker.PushTokenSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import com.pingolin.track.BuildConfig
import uniffi.pingolin_mobile.CryptoManager
import uniffi.pingolin_mobile.EncryptedData
import uniffi.pingolin_mobile.KdfParams
import java.security.SecureRandom
import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A request that failed on the way, before any answer: a timeout or a connection that dropped
 * (OkHttp's IOException, or the SDK's network error). Only this is worth asking again at
 * sign-in: an answer, whatever it says, would only be the same answer again.
 */
internal fun Throwable?.isTransportFailure(): Boolean =
    this is java.io.IOException || this is uniffi.pingolin_mobile.MobileException.NetworkException

/**
 * This phone's Keystore refusing to make or use a key, anywhere in the cause chain: the
 * database's key on a phone with no screen lock is one. Says nothing about the password.
 */
internal fun Throwable?.isSecureStorageFailure(): Boolean {
    var cur: Throwable? = this
    while (cur != null) {
        if (cur is com.pingolin.track.util.SecureStorageUnavailableException ||
            cur is com.pingolin.track.util.DeviceLockedException ||
            cur is com.pingolin.track.util.DatabaseKeyRefusedException ||
            cur is java.security.ProviderException ||
            cur is java.security.KeyStoreException ||
            cur is java.security.UnrecoverableKeyException ||
            cur is android.security.keystore.KeyPermanentlyInvalidatedException ||
            // API 33's class: by name, so no older release has to resolve it.
            cur.javaClass.name == "android.security.KeyStoreException"
        ) return true
        cur = cur.cause
    }
    return false
}

sealed class LoginResult {
    data class Success(val user: UserEntity) : LoginResult()

    /**
     * Login is incomplete until verify2fa() is called with the user's code — and with
     * the same password, which is what unlocks the keys once the code is accepted.
     */
    data class Requires2FA(
        val twoFactorToken: String,
        val availableMethods: List<String>,
        val preferredMethod: String?
    ) : LoginResult()

    /**
     * [attemptStillOpen]: the two-step sign-in's attempt survives this refusal (a wrong code),
     * so the same code box can take another code.
     */
    data class Error(val message: String, val exception: Throwable? = null, val attemptStillOpen: Boolean = false) : LoginResult()
}

/**
 * What deleting the account did to the user's billing.
 *
 * The server cancels every subscription it can. Two it cannot, and both leave
 * money moving after the account is gone, so both come back here rather than
 * being swallowed:
 *   - an App Store subscription, which only the customer can stop, in Apple's
 *     settings — there is no server-side call for it
 *   - a cancellation that failed at the provider, which deliberately does not
 *     block the deletion
 *
 * Once the account is deleted there is no screen left to tell the user any of
 * this, so whatever is in here has to be shown before they leave.
 */
data class AccountDeletionOutcome(
    val appStoreSubscriptionsRemaining: Int = 0,
    val cancellationsFailed: Int = 0,
    /** Billing needs the user to act. NOT the whole test for showing the
     *  post-deletion dialog — see [needsPostDeletionNotice]. */
    val needsAttention: Boolean = false,
    /** "completed", "not_applicable", "queued" or "failed" — what happened to
     *  the location and note data. */
    val dataErasure: String = "completed",
) {
    /** Whether there is anything at all to tell the user after deletion.
     *
     * Wider than [needsAttention] on purpose: an erasure that has only queued,
     * with the billing clean, still needs a notice, because the erasure is the
     * thing the user asked for. */
    val needsPostDeletionNotice: Boolean
        get() = needsAttention || erasureNotice(dataErasure) != null
}

/**
 * What to tell somebody about their money after their account is gone.
 *
 * The two counts are INDEPENDENT and can both be non-zero: subscriptions are
 * unique per (user, product), so one person can hold a Track subscription
 * bought through the App Store and a Note one billed by Paddle. If the Paddle
 * cancellation then fails, the server reports
 * `appStoreSubscriptionsRemaining = 1` AND `cancellationsFailed = 1`.
 *
 * Both parts are reported. With an either/or, somebody in that position
 * would cancel with Apple, believe they were done, and go on being billed
 * by the provider nobody mentioned — with no account left to find out from.
 *
 * Lives here rather than in the Fragment so it can be tested without one.
 */
fun AccountDeletionOutcome.deletionNotice(): String {
    val parts = mutableListOf<String>()

    if (appStoreSubscriptionsRemaining == 1) {
        parts += "Your App Store subscription is still running. Apple will keep charging " +
            "you until you cancel it yourself, in Settings > your name > Subscriptions " +
            "on your iPhone or iPad."
    } else if (appStoreSubscriptionsRemaining > 1) {
        parts += "Your $appStoreSubscriptionsRemaining App Store subscriptions are still " +
            "running. Apple will keep charging you until you cancel them yourself, in " +
            "Settings > your name > Subscriptions on your iPhone or iPad."
    }

    if (cancellationsFailed > 0) {
        parts += if (appStoreSubscriptionsRemaining > 0) {
            // Say plainly that this is a DIFFERENT subscription, or it reads as a
            // restatement of the Apple one and gets ignored.
            "Separately, we could not cancel a subscription that is not billed through " +
                "the App Store, so you may still be charged for that one too. Write to " +
                "help@support.pingolin.com and we will stop it."
        } else {
            "We could not cancel your subscription, so you may still be charged. " +
                "Write to help@support.pingolin.com and we will stop it."
        }
    }

    erasureNotice(dataErasure)?.let { parts += it }

    // Unreachable while needsAttention is (remaining > 0 || failed > 0), but if
    // the server ever widens that flag, say something rather than raising an
    // empty dialog.
    if (parts.isEmpty()) {
        return "Your subscription may need attention. Write to help@support.pingolin.com " +
            "and we will check it."
    }
    return parts.joinToString("\n\n")
}

/**
 * What to say about the erasure of the location and note data, or null when
 * there is nothing to say.
 *
 * A queued erasure is not a failure — the outboxes retry until it goes through
 * — but somebody who asked to be erased is entitled to know it has not finished
 * yet.
 */
internal fun erasureNotice(dataErasure: String): String? = when (dataErasure) {
    "queued" ->
        "Your locations and notes could not be erased at that moment — one of the services " +
            "was unreachable. The erasure is recorded and will be retried until it goes " +
            "through. If you want to know that it has, write to privacy@mail.pingolin.com."
    "failed" ->
        "We could not record the erasure of your locations and notes, so it may not have " +
            "happened. Write to privacy@mail.pingolin.com and we will complete it."
    // "completed" and "not_applicable" — nothing outstanding.
    else -> null
}

@Singleton
class AuthRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val authApi: AuthApi,
    private val tracksApi: TrackApi,
    private val database: TrackDatabase,
    private val userDao: UserDao,
    private val circleDao: CircleDao,
    private val sessionManager: SessionManager,
    private val cryptoManager: CryptoManager,
    private val keystoreEncryption: KeystoreEncryption,
    private val databasePassphraseManager: DatabasePassphraseManager,
    // App-wide EncryptedSharedPreferences ("tracks_secure_prefs") from AppModule —
    // the same instance MapFragment uses to persist the user's last coordinates
    // (KEY_SELF_LOC). Injected here so clearLocalData() can purge that key on logout.
    private val encryptedPrefs: SharedPreferences,
    private val rateLimiter: AuthRateLimiter,
    // Lazy so wiring these @Singletons here can't create an init cycle — they're
    // only touched at logout time by clearLocalData (wipeInMemory / clear), never
    // at AuthRepository construction.
    private val locationRepository: dagger.Lazy<LocationRepository>,
    private val circleRepository: dagger.Lazy<CircleRepository>,
    private val quotaManager: dagger.Lazy<QuotaManager>,
    private val boundaryRepository: dagger.Lazy<BoundaryRepository>,
    private val monitoringManager: dagger.Lazy<MonitoringManager>,
    private val hiddenDestinations: com.pingolin.track.util.HiddenDestinationsStore,
    private val geocodeTokenManager: dagger.Lazy<GeocodeTokenManager>,
    // Lazy — only touched at logout, to wipe locally-stored crash/exception logs whose
    // messages can embed ids. CrashReporter transitively takes the shared OkHttpClient
    // (whose authenticator can reach back here), so keep it Lazy to avoid an init cycle.
    private val crashReporter: dagger.Lazy<CrashReporter>,
    // Verified marks and key-access approvals: kept across this account's own sign-out,
    // dropped when another account signs in.
    private val retainedTrust: com.pingolin.track.util.RetainedTrust,
    // Stored keys that could not be read are replaced only here, at sign-in.
    private val keyStoreRecovery: com.pingolin.track.util.KeyStoreRecovery,
    // Lazy (see above) — only touched at logout: what the alert-rule reload has read goes
    // with the tables.
    private val alertRulesReload: dagger.Lazy<AlertRulesReload>,
) {
    companion object {
        private const val TAG = "AuthRepository"

        /** /auth/me's `registration_status` for an assisted-lane account an admin hasn't approved yet. */
        internal const val REGISTRATION_PENDING = "pending"

        /** ...and for one an admin turned down. The values are "approved", "pending" and "rejected". */
        internal const val REGISTRATION_REJECTED = "rejected"

        /**
         * The only `method` a typed second-step code is sent as. The server checks a
         * backup code under it too; `passkey` expects a WebAuthn assertion, which a
         * code box can never produce.
         */
        internal const val TWO_FACTOR_CODE_METHOD = "totp"

        /** A sign-in refused because the address was never verified (auth's 403 `email_not_verified`). */
        internal const val EMAIL_NOT_VERIFIED =
            "Please verify your email address first. Open the link we sent you, then sign in again."

        /**
         * SRP step 1's 429 with no code: the auth server's per-network or per-(address, network)
         * budget of failed sign-ins is spent, for a window of minutes, not a moment. No number: this
         * app can't know how much of the window is left.
         */
        internal const val SIGN_IN_TOO_MANY = "Too many attempts. Try again in a few minutes."

        /**
         * SRP step 1's 429 `captcha_required`: the address's budget across networks is spent, and the
         * check the auth server then asks for isn't one this app can show. "Email address", never
         * "account": the auth server counts addresses with no account behind them the same. Not a
         * bare "address", which on a sign-in screen can read as the network's.
         */
        internal const val SIGN_IN_TOO_MANY_FOR_ADDRESS = "Too many attempts for this email address. Try again later."

        /**
         * This phone's secure storage failed at sign-in: not the password, and never
         * counted against the address. The wording doesn't mention keys.
         */
        internal const val SECURE_STORAGE_TROUBLE = com.pingolin.track.util.SECURE_STORAGE_TEXT

        // The code step's refusals, in this app's words.
        internal const val TWO_STEP_WRONG_CODE = "That code isn't right. Please try again."
        internal const val TWO_STEP_CODE_JUST_USED = "That code was just used. Wait for the next one and try again."
        internal const val TWO_STEP_TOO_MANY = "Too many wrong codes. Please wait a while, then sign in again."
        internal const val TWO_STEP_ENDED = "That sign-in has ended. Please sign in again."

        /** The unspent-credit question (this app's words; the same as on iPhone). */
        internal const val UNSPENT_CREDIT_TEXT = "Deleting your account destroys the credit on it, and it can't be " +
            "returned afterwards because the record goes with the account. Write to help@support.pingolin.com " +
            "to have it refunded first, or delete anyway."

        /** How long a sign-out waits for the server to end the session. */
        internal const val LOGOUT_CAP_MS = 4_000L

        private fun hexEncode(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Login using the SRP (Secure Remote Password) protocol. The password NEVER
     * leaves the device — only cryptographic proofs are exchanged, and the server is
     * authenticated to the client in the same handshake.
     *
     * SECURITY: the password arrives as a CharArray and is cleared after use.
     */
    suspend fun loginSrp(email: String, password: CharArray): LoginResult {
        when (val rateLimitCheck = rateLimiter.checkAttempt(email)) {
            is RateLimitResult.RateLimited -> {
                return LoginResult.Error(
                    "Too many login attempts. Please wait ${rateLimitCheck.waitSeconds} seconds before trying again."
                )
            }
            is RateLimitResult.Allowed -> { /* Continue */ }
        }

        // SRP step1() clears its input, so key decryption needs its own copy.
        val passwordForDecryption = password.copyOf()
        val passwordForSrp = password.copyOf()
        val passwordForRetry = password.copyOf()

        var result = attemptSrpLogin(email, passwordForSrp, passwordForDecryption)

        // Retry once when the network failed on the way (a timeout, a dropped connection), never on
        // an answer: a wrong password asked twice counts twice against the address, and a 429 or a
        // server in trouble only answers the same again.
        if (result is LoginResult.Error && result.exception.isTransportFailure()) {
            Log.w(TAG, "SRP login failed, retrying once...")
            val retryPasswordForSrp = passwordForRetry.copyOf()
            val retryPasswordForDecryption = passwordForRetry.copyOf()
            result = attemptSrpLogin(email, retryPasswordForSrp, retryPasswordForDecryption)
        }

        // Clear the retry password copy
        SrpClient.clearCharArray(passwordForRetry)

        return result
    }

    private suspend fun attemptSrpLogin(
        email: String,
        passwordForSrp: CharArray,
        passwordForDecryption: CharArray
    ): LoginResult {
        return try {
            // Step 1: Call server to get salt, B, and bcrypt_salt
            val step1Response = authApi.srpStep1(SrpStep1Request(email))
            val step1ApiResponse = step1Response.body()
            if (!step1Response.isSuccessful || step1ApiResponse?.data == null) {
                SrpClient.clearCharArray(passwordForSrp)
                SrpClient.clearCharArray(passwordForDecryption)
                val status = step1Response.code()
                // Too many tries, or a server in trouble, said so in this app's words (as on iOS):
                // neither is a wrong password, and neither counts against the address. Step 1 answers an
                // unknown address like a known one, so these say nothing about who has an account.
                // The step-1 429s are the auth server's budgets of failed sign-ins (windows of minutes):
                // said as such.
                if (status == 429 || status >= 500) {
                    val refusal = com.pingolin.track.data.remote.dto.ApiErrors.parse(
                        runCatching { step1Response.errorBody()?.string() }.getOrNull()
                    )?.errorCode
                    return LoginResult.Error(
                        when {
                            // auth's 503: its rate limiter is down, so it refuses every sign-in.
                            status >= 500 -> com.pingolin.track.data.remote.dto.ApiErrors.TROUBLE
                            refusal == "captcha_required" -> SIGN_IN_TOO_MANY_FOR_ADDRESS
                            else -> SIGN_IN_TOO_MANY
                        }
                    )
                }
                // A success with nothing in it is the server's fault, not the password's.
                if (step1Response.isSuccessful) return LoginResult.Error("Couldn't sign in. Please try again.")
                rateLimiter.recordFailedAttempt(email)
                // Show generic error to prevent user enumeration
                return LoginResult.Error("Invalid email or password")
            }
            val step1 = step1ApiResponse.data!!

            // Step 2: Create SRP session and initialize with password
            // NOTE: step1() clears the password CharArray, so we use passwordForDecryption for key decryption
            val srpSession = srpSessions()

            // Step 3: Compute client proof using salt and server public B.
            // bcrypt preprocessing (step1) and the SRP modpow (step2) are
            // CPU-bound; run them off the Main thread so the login coroutine
            // (launched on viewModelScope) never blocks the UI. The network
            // round-trips (srpStep1 above / srpStep2 below) stay on their own
            // dispatcher.
            val step2Result = withContext(Dispatchers.Default) {
                srpSession.step1(email, passwordForSrp, step1.bcryptSalt)
                srpSession.step2(step1.salt, step1.serverPublic)
            }

            // Step 4: Send client proof to server, get server proof
            val step2Response = authApi.srpStep2(
                SrpStep2Request(
                    sessionId = step1.sessionId,
                    clientPublic = step2Result.clientPublic,
                    clientProof = step2Result.clientProof
                )
            )
            val step2ApiResponse = step2Response.body()
            if (!step2Response.isSuccessful || step2ApiResponse?.data == null) {
                SrpClient.clearCharArray(passwordForDecryption)
                val refusal = com.pingolin.track.data.remote.dto.ApiErrors.parse(
                    runCatching { step2Response.errorBody()?.string() }.getOrNull()
                )?.errorCode
                // The password was right, but the address was never verified (the auth server's
                // 403 `email_not_verified`): say so, and it isn't a wrong password.
                if (refusal == "email_not_verified") return LoginResult.Error(EMAIL_NOT_VERIFIED)
                // SRP step 2 failures typically mean invalid credentials. Said in this app's
                // words, never the server's: too many tries or a server in trouble say so,
                // anything else is the credentials.
                val errorMsg = when (step2Response.code()) {
                    401, 403 -> "Invalid email or password"
                    else -> com.pingolin.track.data.remote.dto.ApiErrors.plainLine(step2Response.code(), refusal, "Invalid email or password")
                }
                // Only the credentials count against the address: too many tries or trouble don't.
                if (errorMsg == "Invalid email or password") rateLimiter.recordFailedAttempt(email)
                return LoginResult.Error(errorMsg)
            }
            val step2 = step2ApiResponse.data!!

            // Step 5: Verify server proof (mutual authentication). Said plainly, as on every
            // client: "Server authentication failed" would be jargon.
            val serverVerified = srpSession.step3(step2.serverProof)
            if (!serverVerified) {
                rateLimiter.recordFailedAttempt(email)
                return LoginResult.Error("Couldn't sign in. Please try again.")
            }

            if (step2.requires2fa == true && step2.twoFactorToken != null) {
                // Don't record as failure - 2FA is expected flow
                return LoginResult.Requires2FA(
                    twoFactorToken = step2.twoFactorToken,
                    availableMethods = step2.availableMethods ?: listOf("totp"),
                    preferredMethod = step2.preferredMethod
                )
            }

            if (step2.accessToken != null && step2.user != null) {
                finishSignIn(
                    email,
                    passwordForDecryption,
                    SignInGrant(
                        accessToken = step2.accessToken,
                        refreshToken = step2.refreshToken,
                        user = step2.user,
                        kdfParams = step2.kdfParams,
                        encryptedKeys = step2.encryptedKeys,
                        sharingPublicKey = step2.sharingPublicKey,
                        signingPublicKey = step2.signingPublicKey,
                    )
                )
            } else {
                rateLimiter.recordFailedAttempt(email)
                Log.w(TAG, "Sign-in: step 2 answered with neither a session nor a two-step challenge")
                LoginResult.Error("Couldn't sign in. Please try again.")
            }
        } catch (e: Exception) {
            // The SDK's or the socket's words go to the log only.
            Log.w(TAG, "Sign-in failed: ${e.javaClass.simpleName}: ${e.message}")
            // This phone's Keystore failing says nothing about the password either: on a phone with
            // no screen lock it fails every time, and counting it would lock sign-in out after five
            // tries.
            if (e.isSecureStorageFailure()) return LoginResult.Error(SECURE_STORAGE_TROUBLE, e)
            // A network that failed on the way says nothing about the password.
            if (!e.isTransportFailure()) rateLimiter.recordFailedAttempt(email)
            LoginResult.Error(e.plainText("Couldn't sign in. Please try again."), e)
        } finally {
            // Clear sensitive password data from memory
            SrpClient.clearCharArray(passwordForDecryption)
        }
    }

    /**
     * What the opened signing key's shape says: never a reason to rotate it. A legacy shape is
     * logged and counted — a non-fatal entry in this phone's crash log, sent only if the person
     * turned reporting on — and a real key the server's record disagrees with is logged.
     */
    private fun noteSigningKeyShape(verdict: SigningKeyCheck.Verdict) {
        when (verdict) {
            SigningKeyCheck.Verdict.LEGACY -> {
                Log.w(TAG, "Signing key: the seed is the sharing key (legacy shape); not rotated")
                runCatching {
                    crashReporter.get().logException(IllegalStateException("Legacy signing-key shape at sign-in"), "signing-key-check")
                }
            }
            SigningKeyCheck.Verdict.SERVER_DISAGREES ->
                Log.w(TAG, "Signing key: the server lists another key than the one this password opened; keeping ours")
            SigningKeyCheck.Verdict.MATCHES -> Unit
        }
    }

    /**
     * What an accepted sign-in hands back, by either route to one: SRP step 2 for an
     * account without two-factor, or the code check for an account with it. The
     * server sends the same key material both ways.
     */
    private class SignInGrant(
        val accessToken: String,
        val refreshToken: String?,
        val user: com.pingolin.track.data.remote.dto.SrpUserResponse,
        val kdfParams: com.pingolin.track.data.remote.dto.KdfParamsResponse?,
        val encryptedKeys: com.pingolin.track.data.remote.dto.EncryptedKeysResponse?,
        val sharingPublicKey: String?,
        val signingPublicKey: String?,
    )

    /**
     * Everything after the server has accepted the sign-in: wipe a different
     * account's local state, unlock this account's keys with [password], then save
     * the session.
     *
     * BOTH routes come through here, and that is the point: the code proves who the
     * user is, but only the password opens their keys. A two-factor route that
     * saved the session and stopped would sign the user in with no keys at all —
     * every circle "key unavailable", on every new phone.
     *
     * Does not clear [password]; the caller owns it.
     */
    private suspend fun finishSignIn(
        email: String,
        password: CharArray,
        grant: SignInGrant,
    ): LoginResult {
        // SECURITY (account-switch wipe): if a DIFFERENT user is logging in
        // on top of a still-open SQLCipher DB — a same-process account switch,
        // or a re-login after a runtime-401 force-logout that left the process
        // alive — wipe the prior user's decrypted rows + keys BEFORE importing
        // the new user's keys or persisting the new session below. A same-user
        // re-login (ids equal) must NOT wipe, so offline-first data survives.
        // The account id is the server's word, taken only as a UUID and kept canonical
        // (AccountIds): anything else signs nobody in, before anything is imported,
        // tidied or saved.
        val incomingUserId = com.pingolin.track.util.AccountIds.canonical(grant.user.id) ?: run {
            Log.w(TAG, "Login: the server named an account id that isn't a UUID; not signing in")
            return LoginResult.Error("Couldn't sign in. Please try again.")
        }
        // All or nothing: this phone's database opens before anything of this sign-in is kept.
        // Opened only at the end, after the session and keys were saved, a failure would leave the
        // phone signed in with data it couldn't open. A failure here throws to attemptSrpLogin,
        // which says so plainly and doesn't count it.
        userDao.getById(incomingUserId)
        val priorUserId = sessionManager.getUserId()
        // The legacy device-wide Pause Tracking outlives sign-out and belongs to one account:
        // record whose it is while the id of the account this phone was signed in as is still
        // here, so another account signing in finds it parked (SessionManager.isTrackingPaused).
        sessionManager.noteTrackingPauseOwnerBeforeSignIn()
        // A saved id that isn't a UUID reads as none, and is never this account: its data goes too.
        val priorNotAnId = sessionManager.savedAccountIdInvalid()
        if (priorNotAnId || priorUserId != null && priorUserId != incomingUserId) {
            Log.w(TAG, "Account switch detected on login (different user) — wiping prior local data")
            clearLocalData()
        }

        // Decrypt and import the user's private key using two-level encryption:
        // 1. Master key is encrypted with password-derived key (Argon2id)
        // 2. Private keys are encrypted with the master key
        if (grant.encryptedKeys != null && grant.kdfParams != null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Login: encryptedKeys present, attempting to decrypt and import sharing keypair")
                Log.d(TAG, "Login: server sharing_public_key: ${grant.sharingPublicKey?.take(50)}...")
            }
            try {
                val kdfParams = KdfParams(
                    salt = grant.kdfParams.salt,
                    memoryKib = grant.kdfParams.memoryKib.toUInt(),
                    iterations = grant.kdfParams.iterations.toUInt(),
                    parallelism = grant.kdfParams.parallelism.toUInt()
                )
                // A copy of the password: SRP step1 cleared the one it was given
                val passwordStr = String(password)

                // Step 1: Decrypt master key with password
                Log.d(TAG, "Login: Decrypting master key...")
                val encryptedMasterKey = EncryptedData(
                    nonce = grant.encryptedKeys.masterKey.nonce,
                    ciphertext = grant.encryptedKeys.masterKey.ciphertext
                )
                // Argon2id is memory-hard; run it off the Main thread so the
                // login coroutine (launched on viewModelScope) never blocks the UI.
                val masterKey = withContext(Dispatchers.Default) {
                    cryptoManager.decryptWithPassword(
                        encryptedMasterKey,
                        passwordStr,
                        kdfParams
                    )
                }
                Log.d(TAG, "Login: Master key decrypted successfully")

                // Step 2: Decrypt sharing private key with master key
                Log.d(TAG, "Login: Decrypting sharing private key...")
                val encryptedSharingKey = EncryptedData(
                    nonce = grant.encryptedKeys.sharingPrivateKey.nonce,
                    ciphertext = grant.encryptedKeys.sharingPrivateKey.ciphertext
                )
                val decryptedPrivateKey = cryptoManager.decryptWithKey(
                    encryptedSharingKey,
                    masterKey
                )
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Login: Private key decrypted successfully")
                }

                // The key store, before any of this sign-in's keys goes in: a wipe still owed is
                // paid; a store that doesn't decode is cleared, so the import writes it whole;
                // entries that didn't read go, before the key sync. One the platform couldn't
                // read is never cleared: the sign-in stops.
                if (keyStoreRecovery.beforeSignInImport() == com.pingolin.track.util.KeyStoreRecovery.BeforeImport.STOP) {
                    Log.w(TAG, "Login: the stored keys can't be read now; not signing in over them")
                    return LoginResult.Error("Couldn't sign in. Please try again.")
                }

                cryptoManager.importPrivateKey(decryptedPrivateKey)
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Login: Private key imported into SDK")
                }

                // Decrypt and import the Ed25519 signing private key
                // (base64-encoded 32-byte secret seed) so we can sign wrap
                // contexts. Failure here is non-fatal: callers degrade to an
                // empty wrap_signature, which peers accept as "unsigned".
                try {
                    val encryptedSigningKey = EncryptedData(
                        nonce = grant.encryptedKeys.signingPrivateKey.nonce,
                        ciphertext = grant.encryptedKeys.signingPrivateKey.ciphertext
                    )
                    val decryptedSigningSeedB64 = cryptoManager.decryptWithKey(
                        encryptedSigningKey,
                        masterKey
                    )
                    cryptoManager.importSigningPrivateKey(decryptedSigningSeedB64)
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Login: Signing private key imported into SDK")
                    }

                    // A signing key whose seed is the X25519 sharing key (a legacy shape) is
                    // never replaced at sign-in: no real account has that shape, so seeing it
                    // means a broken or hostile server — a blob swapped into the signing slot.
                    // It is logged and counted, and nothing is rotated. Neither is a real key
                    // the server's record disagrees with: the key the password opened is kept.
                    val serverSigningHex = grant.signingPublicKey
                        ?.let { runCatching { hexEncode(java.util.Base64.getDecoder().decode(it)) }.getOrNull() }
                    val localSigningHex = runCatching {
                        cryptoManager.signingPublicKeyHex()
                    }.getOrNull()
                    noteSigningKeyShape(
                        SigningKeyCheck.verdict(
                            signingPrivateKey = runCatching { java.util.Base64.getDecoder().decode(decryptedSigningSeedB64) }
                                .getOrDefault(ByteArray(0)),
                            sharingPrivateKey = runCatching { java.util.Base64.getDecoder().decode(decryptedPrivateKey) }
                                .getOrDefault(ByteArray(0)),
                            derivedVerifyingKeyHex = localSigningHex,
                            serverVerifyingKeyHex = serverSigningHex,
                        )
                    )
                } catch (e: Exception) {
                    // Non-fatal: a client with no signing key emits empty
                    // wrap_signature strings, which peers accept as unsigned.
                    Log.w(TAG, "Login: Failed to import signing private key (non-fatal): ${e.message}")
                }

                // Persist to secure storage (saves both X25519 + Ed25519)
                cryptoManager.persistKeys()
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Login: Keys persisted to secure storage")
                }

                // Verify the imported key matches the server's expected public key
                // SECURITY: Only log key details in debug builds to prevent leakage
                if (BuildConfig.DEBUG) {
                    try {
                        val localPublicKey = cryptoManager.exportPublicKey()
                        Log.d(TAG, "Login: Local public key after import: ${localPublicKey.take(50)}...")
                        if (grant.sharingPublicKey != null) {
                            if (localPublicKey == grant.sharingPublicKey) {
                                Log.d(TAG, "Login: PUBLIC KEY MATCH - keypair imported correctly!")
                            } else {
                                Log.w(TAG, "Login: PUBLIC KEY MISMATCH - E2EE may not work correctly")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Login: Failed to verify public key", e)
                    }
                }
            } catch (e: Exception) {
                // Failing the login is intentional: without the sharing
                // private key the app is a broken half-state. The user
                // looks logged in but every downstream call
                // (storeCircleKey, device registration, location send,
                // push token registration) will fail with "no private
                // key available" and the user just sees an app that
                // silently can't do anything. Surfacing the error here
                // lets them know a retry is needed.
                Log.e(TAG, "Login: FAILED to decrypt/import private key: ${e.message}", e)
                // Undo the token state — the access token was NOT saved
                // yet (completeLogin happens below), but clear any
                // crypto state that might be half-populated from
                // earlier attempts so a retry gets a clean slate. The same
                // account's pins are kept for its next sign-in first.
                if (priorUserId == incomingUserId) runCatching { retainedTrust.onSignOut(priorUserId) }
                runCatching { keyStoreRecovery.clearKeys() }
                rateLimiter.recordFailedAttempt(email)
                return LoginResult.Error(
                    "Couldn't open your data on this phone. If you changed your password " +
                        "recently, sign in with the new one; otherwise, use account recovery.",
                    e
                )
            }
        } else {
            Log.w(TAG, "Login: No encryptedKeys in server response - E2EE keypair NOT imported!")
            Log.w(TAG, "  encryptedKeys present: ${grant.encryptedKeys != null}")
            Log.w(TAG, "  kdfParams present: ${grant.kdfParams != null}")
        }

        // Whatever another account left on this device goes; this account's own (kept across its
        // last sign-out) stays. What holds trust goes only when the email typed here isn't that
        // account's (the typed-email guard).
        val guard = runCatching { retainedTrust.onSignIn(incomingUserId, email) }
            .onFailure { Log.w(TAG, "Login: could not tidy retained trust: ${it.message}") }
            .getOrDefault { false }
        // And every other account's owed removals, role changes and the rest kept per account.
        runCatching { circleRepository.get().keepOnlyAccount(incomingUserId, guard) }
            .onFailure { Log.w(TAG, "Login: could not tidy another account's entries: ${it.javaClass.simpleName}") }
        // And the retired stores (RetiredStores), as at every launch.
        com.pingolin.track.util.RetiredStores.purge(context)

        val user = completeLogin(grant.accessToken, grant.refreshToken, grant.user.copy(id = incomingUserId), grant.sharingPublicKey)
        rateLimiter.recordSuccess(email)
        // Signed in: the sign-in screen's sync error is cleared, and the banner is set
        // from the store's current state.
        runCatching { keyStoreRecovery.signedIn() }
        return LoginResult.Success(user)
    }

    /**
     * Complete a login that returned [LoginResult.Requires2FA], passing that result's
     * token back as [twoFactorToken], then unlock the account's keys with the same
     * [password] (and [email]) the first step proved.
     *
     * The code is always sent as `totp`, which is also how the server checks a backup
     * code. A typed code can never answer a passkey challenge, so this never claims
     * one.
     *
     * A wrong code, or one just used, leaves [twoFactorToken]'s attempt open, so the code
     * box comes back on it; too many wrong codes, or an attempt that has ended, mean signing
     * in again ([twoStepRefusal]). [password] is cleared before this returns.
     */
    suspend fun verify2fa(
        twoFactorToken: String,
        code: String,
        email: String,
        password: CharArray,
    ): LoginResult {
        return try {
            val response = try {
                authApi.verify2fa(
                    TwoFactorVerifyRequest(
                        twoFactorToken = twoFactorToken,
                        code = code,
                        method = TWO_FACTOR_CODE_METHOD
                    )
                )
            } catch (e: java.io.IOException) {
                // Not reached is not refused: the attempt is still open for a code.
                return LoginResult.Error(com.pingolin.track.util.UNREACHABLE_TEXT, e, attemptStillOpen = true)
            }

            val apiResponse = response.body()
            if (!response.isSuccessful || apiResponse?.data == null) return twoStepRefusal(response)

            val body = apiResponse.data!!
            finishSignIn(
                email,
                password,
                SignInGrant(
                    accessToken = body.accessToken,
                    refreshToken = body.refreshToken,
                    user = body.user,
                    kdfParams = body.kdfParams,
                    encryptedKeys = body.encryptedKeys,
                    sharingPublicKey = body.sharingPublicKey,
                    signingPublicKey = body.signingPublicKey,
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Code step failed: ${e.javaClass.simpleName}")
            // This phone's Keystore failing is said plainly here too: finishSignIn opens the
            // database first, and its exceptions' own words are never the person's to read.
            if (e.isSecureStorageFailure()) return LoginResult.Error(SECURE_STORAGE_TROUBLE, e)
            LoginResult.Error(e.plainText(TWO_STEP_ENDED), e)
        } finally {
            SrpClient.clearCharArray(password)
        }
    }

    /**
     * The code step refused (the auth server's replies on /login/2fa): a wrong code (401, no
     * code) or one just used (400 `totp_replay`) leaves the attempt open, so the code box comes
     * back on it; too many wrong codes (429 `rate_limited`, which ends the attempt, or the
     * account's limit) and an attempt that has ended (400, no code) go back to the password step.
     * In this app's words; the server's are for logs.
     */
    private fun twoStepRefusal(response: retrofit2.Response<*>): LoginResult.Error {
        val code = com.pingolin.track.data.remote.dto.ApiErrors.parse(runCatching { response.errorBody()?.string() }.getOrNull())?.errorCode
        Log.w(TAG, "Code step refused: HTTP ${response.code()} ${code.orEmpty()}")
        return when {
            response.code() == 401 -> LoginResult.Error(TWO_STEP_WRONG_CODE, attemptStillOpen = true)
            code == "totp_replay" -> LoginResult.Error(TWO_STEP_CODE_JUST_USED, attemptStillOpen = true)
            response.code() == 429 || code == "rate_limited" -> LoginResult.Error(TWO_STEP_TOO_MANY)
            response.code() == 400 && code == null -> LoginResult.Error(TWO_STEP_ENDED)
            else -> LoginResult.Error(com.pingolin.track.data.remote.dto.ApiErrors.plainLine(response.code(), code, TWO_STEP_ENDED))
        }
    }

    /**
     * Persist the session and user after a successful authentication.
     *
     * `refreshToken` is nullable because not every auth endpoint issues one; with
     * none, the client still works via the JWT-only refresh path until a later call
     * attaches a refresh token to the session.
     */
    private suspend fun completeLogin(
        accessToken: String,
        refreshToken: String?,
        srpUser: com.pingolin.track.data.remote.dto.SrpUserResponse,
        sharingPublicKey: String?
    ): UserEntity {
        // Read BEFORE saveUser() overwrites it. Equal ids mean this is the same
        // account signing back in — the account-switch check above has already
        // wiped if it were anyone else — which is the only case where the
        // pre-sign-out tracking intent below may be honoured.
        val sameUserReturning = sessionManager.getUserId() == srpUser.id

        sessionManager.saveAccessToken(accessToken)
        refreshToken?.let { sessionManager.saveRefreshToken(it) }
        sessionManager.saveUser(
            srpUser.id,
            srpUser.email,
            srpUser.displayName,
            sharingPublicKey ?: ""
        )

        // Resume sharing if the app had signed ITSELF out while sharing was on.
        // triggerForceLogout switches tracking off (nothing can be uploaded
        // without a session) after recording that it had been on, so without
        // this the user signs back in and is silently not sharing, behind a
        // screen that looks signed in and healthy.
        //
        // Guarded on the ids matching so a different account on this device
        // never inherits the previous user's intent. The flag is cleared by
        // clearTrackingRecoveryState() once tracking is confirmed working.
        if (sameUserReturning && sessionManager.trackingWantedAtSignOut()) {
            Log.i(TAG, "Same user signed back in after a forced sign-out — restoring tracking intent")
            sessionManager.setTrackingEnabled(true)
        }

        // Force device re-registration so the device is associated with the new
        // session's user. The push-token-synced flag clears too: backend push tokens
        // are session-scoped, so the new session needs the token (re-)written. The
        // post-login device registration carries it, and MainActivity's startup
        // reconciliation force-re-registers if that registration raced ahead of
        // the FCM token being saved (onNewToken firing before login completes).
        sessionManager.setDeviceRegistered(false)
        sessionManager.setPushTokenSynced(false)

        fetchPreferences()
        // An assisted-lane account still waiting for an admin is known before Track's start, which
        // then shows the under-review screen in place of an app every call of which is refused.
        val review = lookAtReview()

        // Nothing of Track's is armed for an account held back: the look above stopped it all, and the
        // app arms it once it opens on an approval (MainActivity).
        if (review != Review.WAITING && review != Review.REJECTED) LocationSyncWorker.schedule(context)

        // Sync display name from auth to Track profile so it's visible to circle members. Not while
        // the account waits, or was turned down: Track refuses it then, and an approval sends it (keepReview).
        if (review != Review.WAITING && review != Review.REJECTED) syncDisplayNameToTrack(srpUser.displayName)

        val user = UserEntity(
            id = srpUser.id,
            email = srpUser.email,
            displayName = srpUser.displayName,
            publicKey = sharingPublicKey ?: "",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        userDao.insert(user)
        return user
    }

    /** Load language / time format / date format / theme into SessionManager. */
    suspend fun fetchPreferences(): Result<Unit> {
        return try {
            val response = authApi.getPreferences()
            val apiResponse = response.body()
            if (response.isSuccessful && apiResponse?.data != null) {
                val prefs = apiResponse.data!!
                sessionManager.savePreferences(
                    language = prefs.language,
                    timeFormat = prefs.timeFormat,
                    dateFormat = prefs.dateFormat,
                    theme = prefs.theme
                )
                Result.success(Unit)
            } else {
                // Not critical - use defaults
                Result.failure(Exception("Failed to fetch preferences"))
            }
        } catch (e: Exception) {
            // Not critical - use defaults
            Result.failure(e)
        }
    }

    /**
     * Exchange the current access + refresh tokens for a fresh pair.
     *
     * Preferred (Proton-style) flow: send the opaque refresh token via
     * `X-Refresh-Token`. The server hashes it, looks the session up by
     * the hash, atomically rotates the row, and returns a new
     * access_token + refresh_token. Crucially, the JWT signature is
     * never consulted on this path, which is why a signing-key rotation
     * or issuer change doesn't log everyone out.
     *
     * Fallback: with no refresh token stored, the standard OkHttp Bearer
     * interceptor still attaches the JWT, the server takes its JWT-based
     * path, and it attaches a fresh refresh token to the session for
     * next time.
     *
     * On a successful response BOTH stored tokens are overwritten, in one
     * write ([SessionManager.saveTokenPair]). The server rotates the
     * refresh token on every call: the previous one keeps working only
     * until the new one is first presented, so the returned pair is the
     * one to keep.
     */
    suspend fun refreshToken(): Result<Unit> {
        return try {
            val accessTokenPresent = !sessionManager.getAccessToken().isNullOrEmpty()
            val refreshTokenPresent = !sessionManager.getRefreshToken().isNullOrEmpty()
            if (!accessTokenPresent && !refreshTokenPresent) {
                Log.w(TAG, "refreshToken: no access or refresh token in SessionManager — nothing to refresh")
                return Result.failure(Exception("No tokens"))
            }

            val response = authApi.refreshToken(
                refreshToken = sessionManager.getRefreshToken(),
            )

            val body = response.body()
            if (response.isSuccessful && body?.success == true && body.data != null) {
                // The server has ALREADY rotated. Both halves go to disk in one write
                // before anything can use the new access token (SessionManager.saveTokenPair).
                // If they do not, a failed persist is a failed refresh, reported as
                // transient: the session is still valid, so retrying is right and signing
                // out is not.
                if (!sessionManager.saveTokenPair(body.data.accessToken, body.data.refreshToken)) {
                    Log.e(TAG, "refreshToken: rotated tokens did NOT persist; treating as failure")
                    return Result.failure(
                        Exception("Token refresh succeeded but the rotated tokens could not be stored")
                    )
                }
                Log.d(TAG, "refreshToken: success")
                Result.success(Unit)
            } else {
                // Capture *why* — server-side messages explain which check
                // tripped (session not found / revoked / expired / signature
                // failure). Without this the client side just sees "401" and
                // the operator can't distinguish a stale jti from a revoked
                // session.
                val serverMsg = body?.error
                    ?: response.errorBody()?.string()
                    ?: "(no body)"
                Log.w(
                    TAG,
                    "refreshToken: failed code=${response.code()} server=\"$serverMsg\""
                )
                // Only a 401 is a definitive auth rejection (session looked up
                // by refresh-token hash and not found / revoked / expired). Any
                // other status (5xx, 429, proxy 502, …) is transient and must
                // NOT cause the caller to wipe a still-valid session.
                // The server's words stay in the log above, out of the failure.
                if (response.code() == 401) {
                    Result.failure(com.pingolin.track.util.SessionExpiredException("Token refresh rejected: HTTP 401"))
                } else {
                    Result.failure(Exception("Token refresh failed transiently: HTTP ${response.code()}"))
                }
            }
        } catch (e: Exception) {
            // Network error / timeout / parse failure — transient by definition.
            // Surface as a plain exception (NOT SessionExpiredException) so the
            // caller keeps the session and retries.
            Log.w(TAG, "refreshToken: transient exception ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getAuthConfig(): com.pingolin.track.data.remote.dto.AuthConfigResponse? {
        return try {
            val response = authApi.getConfig()
            if (response.isSuccessful) response.body() else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Is the current session still usable? Returns false only when the user should be
     * logged out.
     *
     * JWTs are stateless: a token stays cryptographically valid until it expires, even
     * after the account is deleted server-side. So validity can only be established by
     * actually calling an authenticated endpoint and seeing whether the user still
     * exists behind it.
     */
    suspend fun validateSession(): Boolean {
        val accessToken = sessionManager.getAccessToken()
        if (accessToken == null) {
            Log.w(TAG, "validateSession: No access token, session invalid")
            return false
        }

        return try {
            // This endpoint requires a valid token AND an existing user.
            val response = authApi.getCurrentUser()

            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "validateSession: Session validated successfully")
                // The guardian disclosure state rides along here because this is
                // the /auth/me call that actually runs in the app's lifecycle —
                // MainActivity calls it on start. The one in getCurrentUser()
                // below has no caller outside this file, so writing the flag
                // only there would leave it permanently unset and the disclosure
                // permanently hidden.
                response.body()?.data?.let { me ->
                    sessionManager.setGuardianManaged(me.guardianManaged)
                    sessionManager.setGuardianHandoverDate(me.guardianHandoverDate)
                    keepReview(me)
                    // The address as the server has it now. Kept from sign-in only, it would go stale
                    // after a change made elsewhere, and an invitation to the new address would not
                    // name this account (acceptInvite would refuse it; the member list would judge it
                    // as no one's).
                    keepEmail(me.email)
                }
                true
            } else {
                Log.w(TAG, "validateSession: API call failed with code ${response.code()}")
                // 401 means token is invalid or user doesn't exist
                response.code() != 401
            }
        } catch (e: Exception) {
            Log.e(TAG, "validateSession: Exception during validation", e)
            // Network errors shouldn't invalidate session - only auth failures should
            true
        }
    }

    /** Sign out: the server ends this session ([endServerSession]), then everything local is wiped. */
    suspend fun logout(): Result<Unit> {
        endServerSession()
        // Clear local data even if the call failed.
        clearLocalData()
        return Result.success(Unit)
    }

    /**
     * Ask the server to end this session. The refresh token names it, sent with the Bearer this
     * phone holds, expired or not, and nothing is refreshed first, so a sign-out whose access
     * token has expired still ends the session. Best effort, within [LOGOUT_CAP_MS], before
     * anything is wiped.
     */
    internal suspend fun endServerSession() {
        val refreshToken = sessionManager.getRefreshToken()
        try {
            withTimeoutOrNull(LOGOUT_CAP_MS) {
                authApi.logout(com.pingolin.track.data.remote.dto.LogoutRequest(refreshToken))
            } ?: Log.w(TAG, "logout: the server didn't answer in time; signing out here anyway")
        } catch (e: Exception) {
            Log.w(TAG, "logout: not sent: ${e.message}")
        }
    }

    suspend fun clearLocalData() {
        // The member pins go with clearAllKeys() below: keep this account's for its
        // next sign-in first. Another account signing in drops them.
        runCatching { retainedTrust.onSignOut(sessionManager.getUserId()) }
            .onFailure { Log.w(TAG, "clearLocalData: pins not kept: ${it.message}") }

        // Every background waker must be cancelled here AND in the TrackApplication
        // force-logout callback — the two paths are independent, and a waker cancelled
        // in only one of them keeps running for a logged-out user on the other.
        //
        // SECURITY: sharing first, and all that brings it back by itself, or calls Track with
        // this session: the service, its keep-alive and geofence, the heartbeat, the watchdog,
        // the queue's sync and the notification poll. The forced sign-out stops the same list,
        // and so does an account an admin hasn't approved. First, before the keys and the rows
        // go below, so sampling halts and no post-logout fix is left encrypting/queuing.
        com.pingolin.track.service.TrackingStop.all(context)
        // A help request waiting for a connection never goes out after this sign-out, as this
        // account or another: its job and every account's record go. The dispatcher in this
        // process checks the account before each try and drops another's at the next sign-in.
        runCatching { com.pingolin.track.worker.SosRaiseWorker.cancel(context) }
        runCatching { com.pingolin.track.domain.sos.SecurePrefsPendingRaises.wipe(context) }
        // SECURITY: the push-token sync worker retries with exponential backoff until
        // the token lands, so left enqueued it keeps waking to POST push settings for
        // a logged-out device.
        runCatching { PushTokenSyncWorker.cancel(context) }
        // The changes waiting to sync. Their table goes with every other
        // below (clearAllTables); their job goes here.
        runCatching { com.pingolin.track.worker.PendingChangesWorker.cancelAll(context) }
        runCatching { locationRepository.get().cleanup() }

        // SECURITY: cancel every posted notification. Alerts, check-ins, SOS, and
        // destination pushes render DECRYPTED title/body into the status bar; on a
        // logout / account switch they must not linger for the next user to read.
        runCatching { NotificationManagerCompat.from(context).cancelAll() }

        // SECURITY: reset the decrypted in-memory state these @Singletons hold
        // (own marker + cold-start location cache; pinned member pubkeys + TOFU
        // alert queue; cached subscription tier + usage counts). clearAllTables()
        // below empties the DB but not these process-lifetime fields, so a
        // same-process account switch could otherwise surface the prior user's
        // markers / pins / plan-and-usage card.
        runCatching { locationRepository.get().wipeInMemory() }
        runCatching { circleRepository.get().wipeInMemory() }
        // The positions this phone sealed into the account's own SOS.
        runCatching { circleRepository.get().ownSosSeals.wipeAll() }
        // And the last one it sent each circle, kept for a re-seal.
        runCatching { circleRepository.get().ownLastSent.wipeAll() }
        runCatching { quotaManager.get().clear() }
        // SECURITY: reset the boundary + monitoring @Singletons too. BoundaryRepository
        // caches decrypted boundary states/events + member display names and loads
        // decrypted place geofences into the native checker; MonitoringManager holds
        // battery/inactivity monitor state. Nothing else calls their clear paths, so
        // skipping them here leaves that state alive across an account switch.
        runCatching { boundaryRepository.get().wipeInMemory() }
        runCatching { monitoringManager.get().clear() }
        // The alert rules read for this sign-in go with the tables below, so the next sign-in,
        // this account's own too, reads them at once rather than an hour on.
        runCatching { alertRulesReload.get().forget() }

        // The SAS "verified" markers are NOT wiped here: each is bound to the exact keys
        // that were compared and namespaced by account (MemberVerificationStore), so it
        // cannot vouch for a key trusted after this sign-out, nor for another account's
        // member. RetainedTrust drops another account's markers when that account signs
        // in, and deleteAccount forgets these.

        // A deliberate sign-out, journalled so it is distinguishable from the
        // app signing ITSELF out — the two look identical afterwards and only
        // one of them is a defect.
        runCatching {
            sessionManager.trackingJournal.record(
                event = com.pingolin.track.util.TrackingEvent.SIGNED_OUT,
                reason = com.pingolin.track.util.TrackingStopReason.USER_SIGNED_OUT,
                detail = "AuthRepository.logout",
            )
        }
        sessionManager.clearSession()
        // Hidden destinations are a per-account preference keyed by destination
        // id, so the next user must not inherit them — those ids mean nothing in
        // their circles, and a stale hide could silently blank a destination of
        // theirs if an id ever collided.
        runCatching { hiddenDestinations.clear() }
        // A delete the platform refuses leaves a wipe owed, paid before the next load (OwedWipe).
        keyStoreRecovery.clearKeys()
        keyStoreRecovery.signedOut()
        keystoreEncryption.deleteAllKeys()
        // SECURITY: remove the user's last-known coordinates (self_loc). MapFragment
        // persists them into this same "tracks_secure_prefs" EncryptedSharedPreferences
        // (its KEY_SELF_LOC = "self_loc") so the map can cold-start centered on the
        // user. On logout / account switch they must not survive, or the next user's
        // map would center on the prior user's location. Only this one key is removed;
        // the SDK crypto keys that also live in this file are cleared above.
        encryptedPrefs.edit().remove("self_loc").apply()
        // SECURITY: delete the legacy PLAINTEXT "map_view" SharedPreferences file too.
        // It can hold location in the clear from older builds — a "self_loc"
        // ("$lat,$lng") and a "last" camera position ("lat,lng,zoom,bearing,tilt") —
        // and the encrypted remove() above does NOT reach it. Leave it in place and
        // MapFragment.migrateLegacySelfLoc() revives "self_loc" into the NEXT signed-in
        // user's encrypted store on first map open, centering user B's map on user A's
        // last location. Nothing writes this file; this app only reads and purges it,
        // so wipe it wholesale — that also clears the never-migrated "last" fix.
        runCatching { context.deleteSharedPreferences("map_view") }
        // SECURITY: wipe the anonymous geocode credits + pinned key. They live in a
        // SEPARATE EncryptedSharedPreferences file ("geocode_token_store"), untouched
        // by the wipes above; the credits are single-use bearer tokens minted under
        // this user's premium entitlement, so a same-device account switch would
        // otherwise let the next user spend them.
        runCatching { geocodeTokenManager.get().clearAll() }
        // SECURITY: delete the plaintext reverse-geocoding tile cache. GeocodingRepository
        // stores downloaded tiles / city index / region DBs under File(filesDir, "geocoding")
        // in the CLEAR (only the region-selection preferences are encrypted). The tiles that
        // land there reveal which geographic areas the prior user reverse-geocoded, so on a
        // logout / account switch the whole dir must go. Best-effort (deleteRecursively); it
        // is plain filesystem I/O, independent of the Room DB wipe below.
        runCatching { java.io.File(context.filesDir, "geocoding").deleteRecursively() }
        // SECURITY: wipe the map tile caches. The cached tiles reveal which geographic areas
        // the prior user panned/zoomed the map over, so on a logout / account switch they must
        // go alongside the reverse-geocoding cache above.
        //
        // Versions that read archive ranges created File(cacheDir, "pmtiles_range_cache");
        // tiles now come from .mvt tile URLs, so nothing fills it. Remove any directory an
        // upgrade left behind, then clear the MapLibre ambient cache where tile bytes
        // actually live.
        //
        // Use clearAmbientCache, NOT invalidateAmbientCache: invalidate only marks entries
        // stale for revalidation and leaves the bytes on disk, which for an erasure control
        // would be a second wipe that reports success without removing anything.
        runCatching { java.io.File(context.cacheDir, "pmtiles_range_cache").deleteRecursively() }
        runCatching {
            OfflineManager.getInstance(context).clearAmbientCache(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {
                    android.util.Log.i("AuthRepository", "MapLibre ambient tile cache cleared on logout")
                }
                override fun onError(message: String) {
                    // Loud on failure: this is an erasure control, and a silent failure
                    // here would leave the tile cache in place with nobody knowing.
                    android.util.Log.e("AuthRepository", "MapLibre ambient tile cache clear FAILED: $message")
                }
            })
        }
        // SECURITY: wipe locally-stored crash/exception logs. CrashReporter writes stack
        // traces under File(filesDir, "crash_logs") whose messages can embed ids (e.g.
        // circleId). The only other way to delete them is the Settings screen, so this
        // call is what keeps them from surviving a logout / account switch.
        // deleteAllCrashLogs() empties the dir, keeping it valid for future logging.
        runCatching { crashReporter.get().deleteAllCrashLogs() }
        // SECURITY: wipe EVERY table, not just users. The SQLCipher `tracks_database`
        // holds decrypted rows (locations, places incl. per-place placeKey, circles,
        // check-ins, SOS alerts, destinations, and notifications with plaintext
        // title/body). On a same-process account switch (A logs out, B logs in
        // without the process dying) the Room @Singleton stays open, so B would
        // otherwise reuse the DB with A's rows and unscoped queries like
        // NotificationDao.observeAll() would surface them. clearAllTables() empties
        // every table while keeping the singleton connection valid (avoids the
        // close/recreate/stale-singleton hazard). Room asserts non-main and opens a
        // transaction internally, so run it off Main (MainActivity calls clearLocalData
        // from lifecycleScope on Dispatchers.Main) and not inside our own transaction.
        runCatching { withContext(Dispatchers.IO) { database.clearAllTables() } }
        // Clear the database encryption passphrase last. Keep the default
        // deleteDatabase=false: clearAllTables() already emptied the data, and deleting
        // the file out from under the still-open Room singleton risks a crash/corruption
        // on the next write. The stale key is regenerated on next login.
        databasePassphraseManager.clearPassphrase()
    }

    suspend fun getCurrentUser(): Result<UserEntity> {
        return try {
            val userId = sessionManager.getUserId()
            if (userId != null) {
                val localUser = userDao.getById(userId)
                if (localUser != null) {
                    return Result.success(localUser)
                }
            }

            val response = authApi.getCurrentUser()
            val userResponse = response.body()?.data
            if (response.isSuccessful && userResponse != null) {
                // Stash the guardian flag where the map can read it. Not on
                // UserEntity, because one bit is not worth a Room migration.
                sessionManager.setGuardianManaged(userResponse.guardianManaged)
                sessionManager.setGuardianHandoverDate(userResponse.guardianHandoverDate)
                val user = UserEntity(
                    id = userResponse.id,
                    email = userResponse.email,
                    displayName = userResponse.displayName,
                    publicKey = userResponse.publicKey,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                userDao.insert(user)
                Result.success(user)
            } else {
                Result.failure(Exception("Failed to get user"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun isLoggedIn(): Boolean = sessionManager.isLoggedIn()

    /** What the auth server last said about an assisted-lane account's approval: waiting, turned down, or neither. */
    enum class Review { WAITING, REJECTED, NOT_WAITING, UNKNOWN }

    /**
     * Asks the auth server whether this account still waits for an admin's approval, or was turned
     * down, and keeps the answer for Track's start. UNKNOWN when /auth/me can't be read: what was
     * kept stays.
     */
    suspend fun lookAtReview(): Review {
        return try {
            val response = authApi.getCurrentUser()
            val me = response.body()?.data
            if (response.isSuccessful && me != null) {
                keepReview(me)
            } else {
                Review.UNKNOWN
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "lookAtReview: could not read /me", e)
            Review.UNKNOWN
        }
    }

    /**
     * Keeps this account's address as /auth/me has it now ([validateSession]), trimmed and lower-case (every
     * check compares it ignoring case: MemberConfirmation, CircleRepository.acceptInvite). The server reads it
     * live, so an address changed elsewhere arrives here at the next start. None, or a blank one, keeps what's
     * there.
     */
    private fun keepEmail(email: String?) {
        val address = email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
        if (address != sessionManager.getEmail()) sessionManager.setEmail(address)
    }

    /**
     * Keeps whether [me] still waits for an admin's approval, or was turned down. The auth server's
     * token carries no Track audience in either case, so Track refuses every call. On an approval,
     * the display name goes to Track, which the sign-in couldn't send before it.
     */
    private suspend fun keepReview(me: com.pingolin.track.data.remote.dto.UserResponse): Review {
        val waiting = me.registrationStatus == REGISTRATION_PENDING
        val rejected = me.registrationStatus == REGISTRATION_REJECTED
        val held = sessionManager.isRegistrationPending() || sessionManager.isRegistrationRejected()
        // The one place the review is kept, and with it the stop of all that runs for an account held back.
        sessionManager.keepRegistrationReview(pending = waiting, rejected = rejected)
        if (held && !waiting && !rejected) syncDisplayNameToTrack(me.displayName ?: sessionManager.getDisplayName())
        return when {
            waiting -> Review.WAITING
            rejected -> Review.REJECTED
            else -> Review.NOT_WAITING
        }
    }

    /** The display name to Track's profile, where circle members see it. Best effort. */
    private suspend fun syncDisplayNameToTrack(displayName: String?) {
        if (displayName.isNullOrBlank()) return
        try {
            tracksApi.updateProfile(UpdateTracksProfileRequest(displayName = displayName))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync display name to Track service", e)
        }
    }

    /**
     * Whether this account has a recovery address, and whether it is verified.
     *
     * Read-only, and always from the server: there is no local cache of this and
     * there should not be one, because a stale "verified" would be read as an
     * assurance the account no longer has. Anything that goes wrong — offline,
     * 401, a body that doesn't parse — maps to
     * [AccountRecovery.Status.Unknown] rather than to "not set up", so a network
     * problem never gets shown to the user as a missing recovery address.
     */
    suspend fun getRecoveryEmailStatus(): AccountRecovery.Status {
        return try {
            val response = authApi.getCurrentUser()
            val user = response.body()?.data
            if (response.isSuccessful && user != null) {
                AccountRecovery.statusOf(user.recoveryEmail, user.recoveryEmailVerified)
            } else {
                AccountRecovery.Status.Unknown
            }
        } catch (e: Exception) {
            Log.w(TAG, "getRecoveryEmailStatus: could not read /me", e)
            AccountRecovery.Status.Unknown
        }
    }

    // ===== Email Verification =====

    /** Read the email verification status. Requires an authenticated session. */
    suspend fun getVerificationStatus(): Result<EmailVerificationStatusResponse> {
        return try {
            val response = authApi.getVerificationStatus()
            val apiResponse = response.body()
            if (response.isSuccessful && apiResponse?.data != null) {
                Result.success(apiResponse.data!!)
            } else {
                Result.failure(Exception(com.pingolin.track.data.remote.dto.ApiErrors.plainLine(response, "Couldn't check your email just now.")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Resend the verification email. Requires an authenticated session. */
    suspend fun resendVerification(): Result<Unit> {
        return try {
            val response = authApi.resendVerification()
            val apiResponse = response.body()
            if (response.isSuccessful && apiResponse?.success == true) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(com.pingolin.track.data.remote.dto.ApiErrors.plainLine(response, "Couldn't send the email just now.")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Verify an email with the token from its link. No session required. */
    suspend fun verifyEmail(token: String): Result<Unit> {
        return try {
            val response = authApi.verifyEmail(VerifyEmailRequest(token))
            val apiResponse = response.body()
            if (response.isSuccessful && apiResponse?.success == true) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(com.pingolin.track.data.remote.dto.ApiErrors.plainLine(response, "Couldn't verify your email just now.")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== Re-authentication =====

    /**
     * Zero-knowledge re-authentication for sensitive operations
     * (currently account deletion). Runs a fresh SRP handshake against
     * the /auth/reauth/srp step1|step2 endpoints — the password never
     * leaves the device — and returns a short-lived reauth token the server accepts
     * in place of the raw password.
     *
     * Mirrors the login SRP flow (attemptSrpLogin): step 1 fetches the
     * salt / server public value / bcrypt salt, then the bcrypt
     * preprocessing + SRP modpow run off the Main thread, and step 2
     * exchanges the client proof for the token. Identity for step 1 is
     * proven by the bearer token (auto-attached by the OkHttp
     * interceptor), so no email is sent; the stored email is only used
     * locally to derive the SRP proof.
     *
     * [password] is cleared before this returns. Throws on a missing email, network
     * failure, or a wrong password (the server rejects the proof at step 2 with 401).
     */
    private suspend fun reauthenticate(password: CharArray): String {
        try {
            val email = sessionManager.getEmail()
                ?: throw Exception("Re-auth failed: no signed-in email")

            // Step 1: bodyless POST; the JWT identifies the caller.
            val step1Response = authApi.reauthStep1()
            val step1 = step1Response.body()?.data
            if (!step1Response.isSuccessful || step1 == null) {
                throw Exception("Re-auth failed")
            }

            // Step 2: bcrypt preprocessing + SRP modpow are CPU-bound —
            // run them off the Main thread, matching the login flow.
            val srpSession = srpSessions()
            val proof = withContext(Dispatchers.Default) {
                // The address this challenge's verifier was made with (auth: it
                // follows an email change), else the one remembered.
                srpSession.step1(step1.email?.takeIf { it.isNotBlank() } ?: email, password, step1.bcryptSalt)
                srpSession.step2(step1.salt, step1.serverPublic)
            }

            val step2Response = authApi.reauthStep2(
                SrpStep2Request(
                    sessionId = step1.sessionId,
                    clientPublic = proof.clientPublic,
                    clientProof = proof.clientProof
                )
            )
            val step2 = step2Response.body()?.data
            if (!step2Response.isSuccessful || step2 == null) {
                // Wrong password → server rejects the proof (401).
                throw Exception("Invalid password")
            }
            // M2, as at sign-in: the server knew the verifier, or its token is not
            // used for anything. Said as the in-place password says it.
            val serverProof = step2.serverProof
            if (serverProof.isNullOrBlank() || !srpSession.step3(serverProof)) {
                throw Exception("Couldn't check your password just now.")
            }

            return step2.reauthToken
        } finally {
            // Idempotent: srpSession.step1() already zeroes the array on
            // the happy path; this also covers the pre-step1 throw paths.
            SrpClient.clearCharArray(password)
        }
    }

    // ===== The password, in place =====

    /** Where SRP sessions come from; tests swap in a stand-in. */
    internal var srpSessions: () -> com.pingolin.track.util.SrpClientSession = { SrpClient.createSession() }

    /** What [unlockSigningKeyInPlace] did. */
    enum class InPlaceUnlock { UNLOCKED, WRONG_PASSWORD, SIGN_IN_NEEDED, NOTHING_TO_UNLOCK, FAILED }

    /**
     * Signed in, holding the account's identity, but not its signing key: this phone's key
     * store lost it. Nothing the app can do about it by itself — the password opens it again.
     */
    fun signingKeyMissing(): Boolean {
        if (!sessionManager.isLoggedIn()) return false
        val identity = runCatching { cryptoManager.exportPublicKey() }.getOrNull()
        if (identity.isNullOrBlank()) return false
        return runCatching { cryptoManager.signingPublicKeyHex() }.getOrNull().isNullOrBlank()
    }

    /**
     * Put the signing key back with the password typed once, in the foreground: re-auth steps
     * 1 and 2 on THIS session — never a new sign-in, which would open a second session and ask
     * for two-factor again — then the server's proof (M2) is checked before anything it sent is
     * used, the signing key is opened with the password and installed. The identity already
     * here is kept: it must be the one the password opens. [password] is cleared whatever
     * happens.
     */
    suspend fun unlockSigningKeyInPlace(password: CharArray): InPlaceUnlock = try {
        unlockInPlace(password, handshakeRestarts = 1).also {
            // Changes waiting for the password go on now, not at the next trigger.
            if (it == InPlaceUnlock.UNLOCKED) runCatching { com.pingolin.track.worker.PendingChangesWorker.kick(context) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "In-place unlock failed: ${e.javaClass.simpleName}")
        InPlaceUnlock.FAILED
    } finally {
        SrpClient.clearCharArray(password)
    }

    private suspend fun unlockInPlace(password: CharArray, handshakeRestarts: Int): InPlaceUnlock {
        val step1Response = authApi.reauthStep1()
        val step1 = step1Response.body()?.data
        if (!step1Response.isSuccessful || step1 == null) {
            // 409 credential_unknown ("Please sign in again before re-authenticating") is the one
            // real sign-in case; anything else is for another try.
            return if (step1Response.code() == 409) InPlaceUnlock.SIGN_IN_NEEDED else InPlaceUnlock.FAILED
        }
        // The address the verifier was made with (auth: it follows an email change).
        val email = step1.email?.takeIf { it.isNotBlank() } ?: sessionManager.getEmail() ?: return InPlaceUnlock.FAILED
        val srp = srpSessions()
        val proof = withContext(Dispatchers.Default) {
            // step1 clears the array it is given: this one is a copy.
            srp.step1(email, password.copyOf(), step1.bcryptSalt)
            srp.step2(step1.salt, step1.serverPublic)
        }
        val step2Response = authApi.reauthStep2(
            SrpStep2Request(sessionId = step1.sessionId, clientPublic = proof.clientPublic, clientProof = proof.clientProof)
        )
        val step2 = step2Response.body()?.data
        if (!step2Response.isSuccessful || step2 == null) {
            val (code, message) = refusalOf(step2Response)
            return when {
                // The proof was rejected: the one answer that means the password is wrong.
                code == "invalid_credentials" || (code == null && message == "Invalid credentials") -> InPlaceUnlock.WRONG_PASSWORD
                // The handshake step 1 opened is gone: again from step 1, once, with the same password.
                code == "reauth_session_expired" && handshakeRestarts > 0 -> unlockInPlace(password, handshakeRestarts - 1)
                else -> InPlaceUnlock.FAILED
            }
        }
        // M2: the server knew the verifier. Nothing it sent is used before this holds.
        val serverProof = step2.serverProof
        if (serverProof.isNullOrBlank() || !srp.step3(serverProof)) {
            Log.w(TAG, "In-place unlock: the server's proof did not verify")
            return InPlaceUnlock.FAILED
        }
        val keys = step2.encryptedKeys
        val kdf = step2.kdfParams
        // Gson leaves a missing blob null whatever the declared type.
        val signingBlob: com.pingolin.track.data.remote.dto.EncryptedBlobResponse? = keys?.signingPrivateKey
        if (keys == null || kdf == null || signingBlob == null) return InPlaceUnlock.NOTHING_TO_UNLOCK
        val identity = runCatching { cryptoManager.exportPublicKey() }.getOrNull() ?: return InPlaceUnlock.FAILED
        val masterKey = withContext(Dispatchers.Default) {
            cryptoManager.decryptWithPassword(
                EncryptedData(nonce = keys.masterKey.nonce, ciphertext = keys.masterKey.ciphertext),
                String(password),
                KdfParams(
                    salt = kdf.salt,
                    memoryKib = kdf.memoryKib.toUInt(),
                    iterations = kdf.iterations.toUInt(),
                    parallelism = kdf.parallelism.toUInt(),
                ),
            )
        }
        val sharing = runCatching {
            cryptoManager.decryptWithKey(
                EncryptedData(nonce = keys.sharingPrivateKey.nonce, ciphertext = keys.sharingPrivateKey.ciphertext),
                masterKey,
            )
        }.getOrNull() ?: return InPlaceUnlock.FAILED
        // The identity here must be the account's: after a reset elsewhere, only a sign-in sorts
        // out whose data this phone holds. Decided from the sharing key the password just opened —
        // its public half derived here, never the one the server served — so a server that serves
        // another generation's keys gets nothing installed.
        if (!sameIdentity(sharing, identity)) return InPlaceUnlock.SIGN_IN_NEEDED
        val seed = cryptoManager.decryptWithKey(EncryptedData(nonce = signingBlob.nonce, ciphertext = signingBlob.ciphertext), masterKey)
        cryptoManager.importSigningPrivateKey(seed)
        // As at sign-in: the opened key is kept whatever shape it has.
        noteSigningKeyShape(
            SigningKeyCheck.verdict(
                signingPrivateKey = runCatching { java.util.Base64.getDecoder().decode(seed) }.getOrDefault(ByteArray(0)),
                sharingPrivateKey = sharing?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() } ?: ByteArray(0),
                derivedVerifyingKeyHex = runCatching { cryptoManager.signingPublicKeyHex() }.getOrNull(),
                serverVerifyingKeyHex = step2.signingPublicKey
                    ?.let { runCatching { hexEncode(java.util.Base64.getDecoder().decode(it)) }.getOrNull() },
            )
        )
        cryptoManager.persistKeys()
        Log.i(TAG, "Signing key restored in place")
        return InPlaceUnlock.UNLOCKED
    }

    /**
     * Whether the X25519 sharing private key [sharingPrivateB64] is the one behind the public
     * key [identityPublicB64] this phone holds: its public half, derived here.
     */
    private fun sameIdentity(sharingPrivateB64: String, identityPublicB64: String): Boolean = runCatching {
        val derived = com.google.crypto.tink.subtle.X25519.publicFromPrivate(java.util.Base64.getDecoder().decode(sharingPrivateB64))
        derived.contentEquals(java.util.Base64.getDecoder().decode(identityPublicB64))
    }.getOrDefault(false)

    /** A refusal's `error_code` and `error`, read once (the error body is a one-shot stream). */
    private fun refusalOf(response: retrofit2.Response<*>): Pair<String?, String?> = runCatching {
        val body = response.errorBody()?.string().orEmpty()
        val json = com.google.gson.JsonParser.parseString(body).asJsonObject
        fun field(name: String) = json.get(name)?.takeIf { it.isJsonPrimitive }?.asString
        field("error_code") to field("error")
    }.getOrDefault(null to null)

    // ===== Account Deletion =====

    /**
     * Delete the current user's account.
     *
     * The server stores location rows under per-circle pseudonyms, not user ids, so
     * it cannot find this user's rows on its own. This device derives a pseudonym for
     * every circle it holds a key for and hands them over, which is what makes the
     * deletion actually complete.
     *
     * SECURITY: [password] arrives as a CharArray and is cleared after use.
     */
    suspend fun deleteAccount(
        password: CharArray,
        forfeitCreditBalance: Boolean = false,
    ): Result<AccountDeletionOutcome> {
        return try {
            val userId = sessionManager.getUserId()
                ?: return Result.failure(Exception("Not logged in"))

            val pseudonymEntries = try {
                val circles = circleDao.getAll()
                circles.mapNotNull { circle ->
                    try {
                        com.pingolin.track.data.remote.dto.PseudonymEntry(
                            circleId = circle.id,
                            userPseudonym = cryptoManager.deriveUserPseudonym(circle.id, userId)
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to derive pseudonym for circle ${circle.id}", e)
                        null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to collect deletion pseudonyms, proceeding without them", e)
                emptyList()
            }

            Log.i(TAG, "Collected ${pseudonymEntries.size} pseudonyms for GDPR deletion")

            // Zero-knowledge re-auth: prove the password via a fresh SRP
            // handshake and obtain a short-lived reauth token. The raw
            // password never leaves the device. A wrong password throws
            // ("Invalid password") from reauthenticate(), which the outer
            // catch surfaces to the ViewModel. reauthenticate() clears the
            // password CharArray.
            val reauthToken = reauthenticate(password)
            val request = com.pingolin.track.data.remote.dto.DeleteAccountRequest(
                reauthToken = reauthToken,
                pseudonyms = if (pseudonymEntries.isNotEmpty()) pseudonymEntries else null,
                forfeitCreditBalance = if (forfeitCreditBalance) true else null
            )

            val response = authApi.deleteAccount(request)
            val apiResponse = response.body()
            // A refusal's code is in its error body, never in body() (read once).
            val refusal = if (response.isSuccessful) null else
                com.pingolin.track.data.remote.dto.ApiErrors.parse(runCatching { response.errorBody()?.string() }.getOrNull())

            if (response.isSuccessful && apiResponse?.data?.deleted == true) {
                val body = apiResponse.data
                Log.i(
                    TAG,
                    "Account deleted successfully (subscriptions cancelled=${body.subscriptionsCanceled}, " +
                        "App Store remaining=${body.appStoreSubscriptionsRemaining}, " +
                        "failed=${body.subscriptionCancellationsFailed})"
                )
                // Clear all local data, and the trust kept across sign-outs: the
                // account it belonged to is gone.
                clearLocalData()
                runCatching { retainedTrust.forget(userId) }
                runCatching { circleRepository.get().wipeAccountEntries() }
                Result.success(
                    AccountDeletionOutcome(
                        appStoreSubscriptionsRemaining = body.appStoreSubscriptionsRemaining,
                        cancellationsFailed = body.subscriptionCancellationsFailed,
                        needsAttention = body.billingNeedsAttention,
                        dataErasure = body.dataErasure,
                    )
                )
            } else if ((refusal?.errorCode ?: apiResponse?.errorCode) == "unspent_credit_balance") {
                // Not a failure to report as one: the server is asking a
                // question. The account still holds credit it BOUGHT, deleting
                // destroys it, and the user has to answer before we retry with
                // forfeitCreditBalance = true. The 409's code is read from the
                // error body, since body() is never filled for a refusal; and the
                // question is asked in this app's words (the same as on iPhone),
                // not the server's sentence.
                Result.failure(UnspentCreditException(UNSPENT_CREDIT_TEXT))
            } else {
                Result.failure(
                    Exception(
                        com.pingolin.track.data.remote.dto.ApiErrors.plainLine(
                            response.code(), refusal?.errorCode, "Couldn't delete your account just now.",
                        )
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Account deletion failed", e)
            Result.failure(e)
        } finally {
            // Clear sensitive password data from memory
            SrpClient.clearCharArray(password)
        }
    }

}

/**
 * The account still holds credit it paid for, and deleting destroys it because
 * the record goes with the account. Thrown so the UI can ask rather than show a
 * red error: the server refused on purpose, and the answer is the user's.
 */
class UnspentCreditException(message: String) : Exception(message)
