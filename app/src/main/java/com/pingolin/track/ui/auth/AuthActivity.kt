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

package com.pingolin.track.ui.auth

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.text.method.LinkMovementMethod
import androidx.activity.viewModels
import androidx.core.text.HtmlCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.pingolin.track.BuildConfig
import com.pingolin.track.R
import com.google.android.material.button.MaterialButton
import com.pingolin.track.databinding.ActivityAuthBinding
import com.pingolin.track.ui.main.MainActivity
import com.pingolin.track.util.AccountRecovery
import com.pingolin.track.util.SessionManager
import com.pingolin.track.util.SrpClient
import com.pingolin.track.util.storeRefusal
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private val viewModel: AuthViewModel by viewModels()

    /** The sync error, when the session ended because the stored keys don't decode. */
    @javax.inject.Inject
    lateinit var keyStoreRecovery: com.pingolin.track.util.KeyStoreRecovery

    @javax.inject.Inject
    lateinit var sessionManager: SessionManager


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // This phone's session store can't be opened now: a sign-in couldn't be kept, and reading
        // it as "signed out" would be a guess. Said in place of this, which looks again.
        if (sessionManager.storeRefusal() != null) {
            com.pingolin.track.ui.start.CantStartActivity.showFor(this)
            return
        }

        // SECURITY: Always apply FLAG_SECURE on AuthActivity.
        // Recovery keys are shown here and must never be captured in screenshots or task switcher.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The password reaches accessibility services as dots, as the screen shows it.
        com.pingolin.track.util.SecretFields.keepHidden(binding.tilPassword)
        binding.tvSignInNotice.visibility = if (keyStoreRecovery.signInNoticeDue()) View.VISIBLE else View.GONE

        setupUI()
        setupEmailVerificationUI()
        observeViewModel()

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Back on screen (typically from the mail app): check the verification
        // status at once rather than on the next poll tick.
        viewModel.onVerificationScreenStarted()
    }

    override fun onStop() {
        super.onStop()
        // Nothing shows the verification status while this is hidden.
        viewModel.onVerificationScreenStopped()
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data

        // Email-verification deep links, matching the intent filters in the
        // manifest:
        //   https://api-auth.pingolin.com/verify-email?token=…   (prod)
        //   the same path on the dev flavour's own auth host     (dev)
        //   track://verify-email?token=…                         (custom scheme)
        // Matched on path/host rather than on the full URL, so this stays correct if
        // another host is added to the manifest.
        if (data != null && (data.path?.contains("verify-email") == true || data.host == "verify-email")) {
            val token = data.getQueryParameter("token")
            // SECURITY: a deep-link token is attacker-controlled input; never pass it
            // on unvalidated.
            if (!token.isNullOrBlank() && isValidTokenFormat(token)) {
                viewModel.verifyEmail(token)
                return
            } else if (!token.isNullOrBlank()) {
                Log.w("AuthActivity", "Rejecting invalid token format in deep link")
                Toast.makeText(this, R.string.auth_invalid_verification_link, Toast.LENGTH_SHORT).show()
            }
        }

        if (viewModel.isLoggedIn()) {
            navigateToMain()
        }
    }

    /**
     * SECURITY: Validate token format to prevent injection attacks.
     * Tokens should be:
     * - UUID format (36 chars with hyphens)
     * - Base64 encoded (alphanumeric + /-_=)
     * - Reasonable length (max 256 chars)
     */
    private fun isValidTokenFormat(token: String): Boolean {
        if (token.length > 256) return false
        if (token.length < 20) return false

        val safePattern = Regex("^[A-Za-z0-9\\-_+=/.]+$")
        return safePattern.matches(token)
    }

    private fun setupUI() {
        updateUIMode()

        binding.btnSubmit.setOnClickListener {
            submit()
        }

        // Sign-up leaves the app, exactly as password reset does below and for
        // the same reason: the account portal owns registration, and the
        // recovery key it creates.
        binding.tvSwitchMode.setOnClickListener {
            val url = AccountRecovery.registerUrl(BuildConfig.ACCOUNT_BASE_URL)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.recovery_no_browser, Toast.LENGTH_SHORT).show()
            }
        }

        // Password reset lives entirely in the web portal — this app must not
        // grow its own. The reset flow binds SRP credentials to an identity and
        // hashes an address into a token; a second implementation that disagrees
        // with the first does not fail visibly, it bricks the account. So this
        // is a link and nothing else, and it is the only thing on the phone that
        // tells a phone-first user the way back in exists at all.
        binding.tvForgotPassword.setOnClickListener {
            val url = AccountRecovery.forgotPasswordUrl(BuildConfig.ACCOUNT_BASE_URL)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.recovery_no_browser, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * The screen is sign-in only: registration belongs to the account portal.
     */
    private fun updateUIMode() {
        binding.tvTitle.setText(R.string.auth_welcome_back)
        binding.tvSubtitle.setText(R.string.auth_sign_in_to_continue)
        binding.btnSubmit.setText(R.string.auth_login)
        binding.tvSwitchMode.setText(R.string.auth_no_account_sign_up)
        binding.tvForgotPassword.visibility = View.VISIBLE
        binding.etEmail.text?.clear()
        binding.etPassword.text?.clear()
        // The fields are empty again; their errors would be about what was in
        // them.
        clearFieldErrors()
        // The app ended this sign-in itself and kept the account: ask for the
        // password only, and everything carries on after it.
        viewModel.keptEmail()?.let { email ->
            binding.tvSubtitle.setText(R.string.auth_password_to_carry_on)
            binding.etEmail.setText(email)
            binding.etPassword.requestFocus()
        }
    }

    /**
     * Submit the sign-in form.
     *
     * SECURITY: Passwords are extracted as CharArray and cleared after use
     * to minimize exposure in memory.
     */
    private fun submit() {
        val email = binding.etEmail.text?.toString()?.trim() ?: ""

        // Extract password as CharArray for secure handling
        val passwordEditable = binding.etPassword.text
        val password = CharArray(passwordEditable?.length ?: 0)
        passwordEditable?.getChars(0, passwordEditable.length, password, 0)

        // Say what is wrong ON THE FIELD, before anything is sent. The ViewModel
        // still refuses an empty form, but it can only answer with a Toast, and a
        // Toast does not point at a box. Lengths rather than the strings
        // themselves: the password never leaves its CharArray.
        val errors = AuthFormValidation.forLogin(email, password.size)
        if (errors.isNotEmpty()) {
            showFieldErrors(errors)
            SrpClient.clearCharArray(password)
            return
        }
        clearFieldErrors()

        try {
            viewModel.login(email, password.copyOf())
        } finally {
            // Clear password from memory
            SrpClient.clearCharArray(password)
        }
    }

    /**
     * Paint the errors on their fields and move focus to the first one, so the
     * keyboard lands where the correction has to happen.
     */
    private fun showFieldErrors(errors: List<AuthFieldError>) {
        clearFieldErrors()
        errors.forEach { error ->
            when (error.field) {
                AuthField.EMAIL -> binding.tilEmail.error = error.message
                AuthField.PASSWORD -> binding.tilPassword.error = error.message
            }
        }
        when (errors.first().field) {
            AuthField.EMAIL -> binding.etEmail
            AuthField.PASSWORD -> binding.etPassword
        }.requestFocus()
    }

    /**
     * Errors are stale the moment the user edits the field they are about, so they
     * are cleared on the next submit. Leaving a red box under a corrected field
     * is its own small lie.
     */
    private fun clearFieldErrors() {
        binding.tilEmail.error = null
        binding.tilPassword.error = null
    }

    private fun setupEmailVerificationUI() {
        binding.btnOpenEmail.setOnClickListener {
            openEmailApp()
        }

        binding.btnResendVerification.setOnClickListener {
            viewModel.resendVerificationEmail()
        }
    }

    private fun showEmailVerification(email: String, canResend: Boolean) {
        binding.authFormContainer.visibility = View.GONE
        binding.emailVerificationContainer.visibility = View.VISIBLE
        binding.tvVerifyEmail.text = email
        binding.btnResendVerification.isEnabled = canResend
        // Show waiting indicator - polling happens automatically
        binding.waitingContainer.visibility = View.VISIBLE
    }

    private fun hideEmailVerification() {
        binding.emailVerificationContainer.visibility = View.GONE
        binding.authFormContainer.visibility = View.VISIBLE
    }

    private fun openEmailApp() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_EMAIL)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.auth_no_email_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is AuthUiState.Idle -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnSubmit.isEnabled = true
                    }
                    is AuthUiState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.btnSubmit.isEnabled = false
                    }
                    is AuthUiState.Success -> {
                        binding.progressBar.visibility = View.GONE
                        navigateToMain()
                    }
                    is AuthUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnSubmit.isEnabled = true
                        Toast.makeText(this@AuthActivity, state.message, Toast.LENGTH_LONG).show()
                        viewModel.resetState()
                    }
                    is AuthUiState.Requires2FA -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnSubmit.isEnabled = true
                        // The last code wasn't right; the same attempt takes
                        // another.
                        state.retryMessage?.let { Toast.makeText(this@AuthActivity, it, Toast.LENGTH_LONG).show() }
                        // Show 2FA input dialog
                        show2FADialog()
                    }
                    is AuthUiState.PasskeyOnlySecondStep -> {
                        binding.progressBar.visibility = View.GONE
                        binding.btnSubmit.isEnabled = true
                        showPasskeyOnlyDialog()
                    }
                    is AuthUiState.EmailVerificationRequired -> {
                        binding.progressBar.visibility = View.GONE
                        showEmailVerification(state.email, state.canResend)
                    }
                    is AuthUiState.EmailVerified -> {
                        binding.progressBar.visibility = View.GONE
                        Toast.makeText(this@AuthActivity, R.string.auth_email_verified, Toast.LENGTH_SHORT).show()
                        navigateToMain()
                    }
                }
            }
        }
    }

    /**
     * The code box. It doesn't take the account's preferred method: that could be
     * `passkey`, and a typed code sent under it can only be rejected by the server.
     * A typed code is always an authenticator or backup code.
     */
    private fun show2FADialog() {
        val input = android.widget.EditText(this).apply {
            hint = getString(R.string.two_factor_code_hint)
            // Letters as well as digits: a backup code is twelve of them, and it is
            // the way in for somebody whose authenticator was on the phone they lost.
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.two_factor_title)
            .setMessage(R.string.two_factor_prompt)
            .setView(input)
            .setPositiveButton(R.string.two_factor_verify) { _, _ ->
                viewModel.verify2fa(input.text.toString())
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                viewModel.resetState()
            }
            .setCancelable(false)
            .show()
    }

    /**
     * Two-factor is on and a passkey is the only second step: say so, and where to
     * fix it, instead of offering a code box nothing typed into could satisfy.
     */
    private fun showPasskeyOnlyDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.two_factor_passkey_only_title)
            .setMessage(R.string.two_factor_passkey_only_body)
            .setPositiveButton(R.string.two_factor_open_security_settings) { _, _ ->
                viewModel.resetState()
                openInBrowser(AccountRecovery.securitySettingsUrl(BuildConfig.ACCOUNT_BASE_URL))
            }
            .setNegativeButton(android.R.string.ok) { _, _ ->
                viewModel.resetState()
            }
            .setCancelable(false)
            .show()
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.recovery_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
