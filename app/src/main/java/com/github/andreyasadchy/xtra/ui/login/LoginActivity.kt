package com.github.andreyasadchy.xtra.ui.login

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.ActivityLoginBinding
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationScheduler
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.ceil

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var viewModel: LoginViewModel
    private lateinit var xtraApp: XtraApp
    private var previousUserId: String? = null
    private var previousUserLogin: String? = null
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        xtraApp = application as XtraApp
        applyTheme()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val reauthorize = intent.getBooleanExtra(EXTRA_REAUTHORIZE, false)
        val logout = intent.getBooleanExtra(EXTRA_LOGOUT, false)
        val compatibilityOnly = intent.getBooleanExtra(EXTRA_COMPATIBILITY_ONLY, false)
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
            LoginViewModel.Factory(application, reauthorize, compatibilityOnly),
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
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect(::render)
                }
                launch {
                    viewModel.browserRequest.collect(::openInBrowser)
                }
            }
        }
        onBackPressedDispatcher.addCallback(this) {
            cancelLogin()
        }
        if (compatibilityOnly && !logout) viewModel.startAuthorization()
    }

    private fun setupActions() {
        binding.continueButton.setOnClickListener { viewModel.startAuthorization() }
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
            LoginUiState.Validating -> {
                showProgress(R.string.login_validating)
            }
            is LoginUiState.Error -> {
                binding.errorText.visibility = View.VISIBLE
                binding.errorText.setText(errorMessage(state.type))
                if (state.recoverable) binding.retryButton.visibility = View.VISIBLE
                if (state.type == LoginError.SETUP_REQUIRED) binding.cancelButton.visibility = View.GONE
            }
            is LoginUiState.Complete -> finishLogin(state)
        }
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
        binding.codeText.text = userCode
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

    private fun openInBrowser(uri: Uri) {
        try {
            val customTabsPackage = CustomTabsClient.getPackageName(this, null)
            if (!customTabsPackage.isNullOrBlank() && customTabsPackage != packageName) {
                try {
                    CustomTabsIntent.Builder().build().apply {
                        intent.setPackage(customTabsPackage)
                        launchUrl(this@LoginActivity, uri)
                    }
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
        viewModel.cancel()
        // The official session may already be committed while the optional GQL
        // compatibility step is waiting. Keep the host activity in sync with that
        // successful login even when the user cancels compatibility authorization.
        setResult(if (viewModel.state.value is LoginUiState.Complete) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    private fun finishLogin(state: LoginUiState.Complete) {
        if (finished) return
        finished = true
        val accountChanged = state.accountChanged
        if (accountChanged) {
            LiveNotificationScheduler.disable(this)
            lifecycleScope.launch(Dispatchers.IO) {
                xtraApp.xtraModule.notificationsRepository.clearNotificationState()
                xtraApp.xtraModule.metadataCache.clearAccount(previousUserId, previousUserLogin)
            }
            prefs().edit { putBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false) }
        }
        if (state.revocationFailures > 0) {
            Toast.makeText(this, R.string.credentials_revoke_failed, Toast.LENGTH_LONG).show()
        }
        if (!state.compatibilityAvailable) {
            Toast.makeText(this, R.string.login_compatibility_unavailable, Toast.LENGTH_LONG).show()
        }
        TwitchApiHelper.checkedValidation = !tokenPrefs().getString(C.TOKEN, null).isNullOrBlank()
        xtraApp.xtraModule.authSessionMaintainer.onAuthenticationStateChanged()
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        const val EXTRA_REAUTHORIZE = "com.github.andreyasadchy.xtra.REAUTHORIZE"
        const val EXTRA_LOGOUT = "com.github.andreyasadchy.xtra.LOGOUT"
        const val EXTRA_COMPATIBILITY_ONLY = "com.github.andreyasadchy.xtra.COMPATIBILITY_ONLY"
    }
}
