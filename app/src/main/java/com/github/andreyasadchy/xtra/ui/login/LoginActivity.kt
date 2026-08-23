package com.github.andreyasadchy.xtra.ui.login

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.ActivityLoginBinding
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationScheduler
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.ceil

internal suspend fun clearAccountScopedState(
    disableScheduler: () -> Unit,
    disableNotifications: () -> Unit,
    clearNotificationState: suspend () -> Unit,
    clearAccountMetadata: suspend () -> Unit,
) {
    disableScheduler()
    disableNotifications()
    clearNotificationState()
    clearAccountMetadata()
}

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var viewModel: LoginViewModel
    private lateinit var xtraApp: XtraApp
    private var previousUserId: String? = null
    private var previousUserLogin: String? = null
    private var finished = false
    private var browserOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        xtraApp = application as XtraApp
        applyTheme()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val reauthorize = intent.getBooleanExtra(EXTRA_REAUTHORIZE, false)
        val logout = intent.getBooleanExtra(EXTRA_LOGOUT, false)
        previousUserId = tokenPrefs().getString(C.USER_ID, null)
        previousUserLogin = tokenPrefs().getString(C.USERNAME, null)
        if (reauthorize && previousUserId.isNullOrBlank() && !logout) {
            Toast.makeText(this, R.string.account_reauthorize_identity_unknown, Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        viewModel = ViewModelProvider(
            this,
            LoginViewModel.Factory(application, reauthorize),
        )[LoginViewModel::class.java]
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
                leftMargin = insets.left
                rightMargin = insets.right
                bottomMargin = insets.bottom
            }
            windowInsets
        }
        setupActions()
        if (logout) viewModel.logout()
        lifecycleScope.launch {
            viewModel.state.collect(::render)
        }
        lifecycleScope.launch {
            viewModel.browserRequest.collect {
                openInBrowser(it)
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            cancelLogin()
        }
    }

    private fun setupActions() {
        binding.continueButton.setOnClickListener {
            if (viewModel.state.value is LoginUiState.CompatibilityReady) {
                viewModel.continueCompatibilityAuthorization()
            } else {
                viewModel.startAuthorization()
            }
        }
        binding.retryButton.setOnClickListener { viewModel.retry() }
        binding.openUrl.setOnClickListener { viewModel.openBrowserAgain() }
        binding.copyCode.setOnClickListener {
            val code = binding.codeText.text?.toString().orEmpty()
            if (code.isNotBlank()) {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                    ClipData.newPlainText("Twitch code", code),
                )
                Toast.makeText(this, R.string.copy_code, Toast.LENGTH_SHORT).show()
            }
        }
        binding.cancelButton.setOnClickListener { cancelLogin() }
    }

    private fun render(state: LoginUiState) {
        binding.progressRow.visibility = View.GONE
        binding.verificationCard.visibility = View.GONE
        binding.expiresText.visibility = View.GONE
        binding.errorText.visibility = View.GONE
        binding.retryButton.visibility = View.GONE
        binding.continueButton.visibility = View.GONE
        binding.continueButton.setText(R.string.continue_with_twitch)
        binding.cancelButton.visibility = View.VISIBLE

        when (state) {
            LoginUiState.Idle -> {
                binding.continueButton.visibility = View.VISIBLE
            }
            LoginUiState.Starting -> {
                showProgress(R.string.login_starting)
            }
            is LoginUiState.WaitingForAuthorization -> {
                renderAuthorization(state.userCode, state.expiresAtMillis, state.isPolling, compatibility = false)
            }
            is LoginUiState.CompatibilityAuthorization -> {
                renderAuthorization(state.userCode, state.expiresAtMillis, state.isPolling, compatibility = true)
            }
            LoginUiState.CompatibilityReady -> renderCompatibilityReady()
            LoginUiState.Validating -> {
                showProgress(R.string.login_validating)
            }
            LoginUiState.Committing -> {
                showProgress(R.string.login_finalizing)
                binding.cancelButton.visibility = View.GONE
            }
            is LoginUiState.Error -> {
                binding.errorText.visibility = View.VISIBLE
                binding.errorText.setText(errorMessage(state.type))
                if (state.recoverable) binding.retryButton.visibility = View.VISIBLE
                if (state.type == LoginError.SETUP_REQUIRED) binding.cancelButton.visibility = View.GONE
                if (browserOpened) bringLoginToFront()
            }
            is LoginUiState.CompatibilityError -> {
                binding.errorText.visibility = View.VISIBLE
                binding.errorText.setText(compatibilityErrorMessage(state.type))
                if (state.recoverable) binding.retryButton.visibility = View.VISIBLE
                if (browserOpened) bringLoginToFront()
            }
            is LoginUiState.Complete -> finishLogin(state)
            is LoginUiState.LoggedOut -> finishLogout(state)
        }
    }

    private fun renderCompatibilityReady() {
        binding.verificationCard.visibility = View.VISIBLE
        binding.verificationTitle.setText(R.string.login_compatibility_ready_title)
        binding.verificationMessage.setText(R.string.login_compatibility_ready_message)
        binding.codeLabel.visibility = View.GONE
        binding.codeText.visibility = View.GONE
        binding.copyCode.visibility = View.GONE
        binding.openUrl.visibility = View.GONE
        binding.continueButton.setText(R.string.login_continue_compatibility)
        binding.continueButton.visibility = View.VISIBLE
        if (browserOpened) bringLoginToFront()
    }

    private fun renderAuthorization(
        userCode: String,
        expiresAtMillis: Long,
        isPolling: Boolean,
        compatibility: Boolean,
    ) {
        showProgress(
            if (isPolling) {
                if (compatibility) R.string.login_compatibility_polling else R.string.login_polling
            } else {
                if (compatibility) R.string.login_compatibility_starting else R.string.login_starting
            },
        )
        binding.verificationCard.visibility = View.VISIBLE
        binding.verificationTitle.setText(if (compatibility) R.string.login_compatibility_title else R.string.login_waiting_title)
        binding.verificationMessage.setText(if (compatibility) R.string.login_compatibility_message else R.string.login_waiting_message)
        binding.codeLabel.visibility = View.VISIBLE
        binding.codeText.text = userCode
        binding.codeText.visibility = View.VISIBLE
        binding.copyCode.visibility = View.VISIBLE
        binding.openUrl.visibility = View.VISIBLE
        binding.expiresText.visibility = View.VISIBLE
        val minutes = ceil((expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(0L) / 60_000.0)
            .toInt()
            .coerceAtLeast(1)
        binding.expiresText.text = getString(R.string.login_expires_in, minutes)
    }

    private fun showProgress(message: Int) {
        binding.progressRow.visibility = View.VISIBLE
        binding.statusText.setText(message)
    }

    private fun errorMessage(error: LoginError): Int = when (error) {
        LoginError.SETUP_REQUIRED -> R.string.login_setup_required
        LoginError.NETWORK -> R.string.login_error_network
        LoginError.SERVER -> R.string.login_error_server
        LoginError.DENIED -> R.string.login_error_denied
        LoginError.EXPIRED -> R.string.login_error_expired
        LoginError.ACCOUNT_MISMATCH -> R.string.account_reauthorize_same_account
        LoginError.MISSING_SCOPES -> R.string.account_reauthorize_helix_required
        LoginError.VALIDATION,
        LoginError.MALFORMED_RESPONSE -> R.string.login_error_validation
        LoginError.PERSISTENCE -> R.string.login_error_persistence
        LoginError.BROWSER_UNAVAILABLE -> R.string.login_error_browser
        LoginError.UNKNOWN -> R.string.login_error_unknown
    }

    private fun compatibilityErrorMessage(error: LoginError): Int = when (error) {
        LoginError.ACCOUNT_MISMATCH -> R.string.login_compatibility_same_account
        else -> errorMessage(error)
    }

    private fun openInBrowser(uri: Uri) {
        try {
            val customTabsPackage = CustomTabsClient.getPackageName(this, null)
            if (!customTabsPackage.isNullOrBlank() && customTabsPackage != packageName) {
                try {
                    CustomTabsIntent.Builder().build().apply {
                        intent.setPackage(customTabsPackage)
                        launchUrl(this@LoginActivity, uri)
                    }
                    browserOpened = true
                    return
                } catch (_: ActivityNotFoundException) {
                    // Fall through to a normal browser if the advertised provider is unavailable.
                } catch (_: SecurityException) {
                    // Fall through to a normal browser if the advertised provider rejects the launch.
                }
            }
            val browserPackage = findExternalBrowserPackage(uri)
                ?: throw ActivityNotFoundException("No external browser found")
            startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                setPackage(browserPackage)
            })
            browserOpened = true
        } catch (_: ActivityNotFoundException) {
            viewModel.browserUnavailable()
        } catch (_: SecurityException) {
            viewModel.browserUnavailable()
        }
    }

    private fun findExternalBrowserPackage(uri: Uri): String? {
        val intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        return packageManager.queryIntentActivities(intent, 0)
            .asSequence()
            .mapNotNull { it.activityInfo?.packageName }
            .firstOrNull { it != packageName }
    }

    private fun cancelLogin() {
        if (finished) return
        if (!viewModel.cancel()) return
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun finishLogin(state: LoginUiState.Complete) {
        if (finished) return
        finished = true
        val accountChanged = state.accountChanged
        if (accountChanged) {
            scheduleAccountScopedStateCleanup()
        }
        if (state.revocationFailures > 0) {
            Toast.makeText(this, R.string.credentials_revoke_failed, Toast.LENGTH_LONG).show()
        }
        TwitchApiHelper.checkedValidation = !tokenPrefs().getString(C.TOKEN, null).isNullOrBlank()
        xtraApp.xtraModule.authSessionMaintainer.onAuthenticationStateChanged()
        setResult(RESULT_OK)
        returnToCaller()
        finish()
    }

    private fun finishLogout(state: LoginUiState.LoggedOut) {
        if (finished) return
        finished = true
        scheduleAccountScopedStateCleanup()
        if (state.revocationFailures > 0) {
            Toast.makeText(this, R.string.credentials_revoke_failed, Toast.LENGTH_LONG).show()
        }
        TwitchApiHelper.checkedValidation = false
        xtraApp.xtraModule.authSessionMaintainer.onAuthenticationStateChanged()
        setResult(RESULT_OK)
        returnToCaller()
        finish()
    }

    private fun scheduleAccountScopedStateCleanup() {
        val context = applicationContext
        val module = xtraApp.xtraModule
        val userId = previousUserId
        val userLogin = previousUserLogin
        xtraApp.applicationScope.launch(Dispatchers.IO) {
            clearAccountScopedState(
                disableScheduler = { LiveNotificationScheduler.disable(context) },
                disableNotifications = {
                    context.prefs().edit { putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) }
                },
                clearNotificationState = { module.notificationsRepository.clearNotificationState() },
                clearAccountMetadata = { module.metadataCache.clearAccount(userId, userLogin) },
            )
        }
    }

    /**
     * A Custom Tab stays in the foreground while device authorization is
     * being polled. Reorder this activity above it when a terminal error is
     * reached so the user can see the actionable state without manually
     * navigating browser history.
     */
    private fun bringLoginToFront() {
        if (isFinishing || isDestroyed || hasWindowFocus()) return
        startActivity(
            Intent(this, LoginActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            ),
        )
    }

    /**
     * Return to whichever in-app screen launched login. CLEAR_TOP removes the
     * Custom Tab and this LoginActivity from the task after a successful
     * authorization, so a browser Connections page cannot strand the user.
     */
    private fun returnToCaller() {
        if (isFinishing || isDestroyed) return
        val caller = callingActivity?.takeIf { it.packageName == packageName }
        val intent = if (caller != null) {
            Intent().setComponent(caller)
        } else {
            Intent(this, MainActivity::class.java)
        }
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    companion object {
        const val EXTRA_REAUTHORIZE = "com.github.andreyasadchy.xtra.REAUTHORIZE"
        const val EXTRA_LOGOUT = "com.github.andreyasadchy.xtra.LOGOUT"
    }
}
