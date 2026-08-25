package com.github.andreyasadchy.xtra.ui.login

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.auth.TwitchWebSessionManager
import com.github.andreyasadchy.xtra.repository.auth.TwitchWebSessionState
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** Displays the process-wide Twitch browser session. Authentication belongs to the manager. */
open class TwitchWebLoginActivity : AppCompatActivity() {
    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession
    private lateinit var sessionManager: TwitchWebSessionManager
    private var finished = false
    private var captureGqlForDiagnostics = false
    private var integrityBootstrapOnly = false
    private var diagnosticPageLoaded = false
    private var integrityBootstrapStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        geckoView = GeckoView(this)
        sessionManager = (application as XtraApp).xtraModule.twitchWebSessionManager
        captureGqlForDiagnostics = BuildConfig.DEBUG &&
            intent.getBooleanExtra(EXTRA_CAPTURE_GQL, false)
        integrityBootstrapOnly = intent.getBooleanExtra(EXTRA_BOOTSTRAP_INTEGRITY, false)
        if (intent.getBooleanExtra(EXTRA_LOGOUT, false)) {
            lifecycleScope.launch {
                val result = sessionManager.logout()
                setResult(if (result) RESULT_OK else RESULT_CANCELED)
                finish()
            }
            return
        }

        setContentView(geckoView)
        if (integrityBootstrapOnly) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            geckoView.alpha = 0f
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        }
        session = sessionManager.openLoginSession(
            reauthorize = intent.getBooleanExtra(EXTRA_REAUTHORIZE, false),
        )
        geckoView.setSession(session)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    sessionManager.state.collect(::render)
                }
                launch {
                    sessionManager.loginSession
                        .filterNotNull()
                        .collect { replacementSession ->
                            session = replacementSession
                            geckoView.setSession(replacementSession)
                        }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        cancel()
        return true
    }

    override fun onBackPressed() {
        cancel()
    }

    override fun onDestroy() {
        if (::geckoView.isInitialized) geckoView.releaseSession()
        // The authenticated process-wide Gecko session is retained so it can refresh
        // short-lived Twitch integrity state. Interrupted login flows are still closed.
        if (!isChangingConfigurations && !finished && ::sessionManager.isInitialized) {
            sessionManager.closeLoginSession()
        }
        super.onDestroy()
    }

    private fun render(state: TwitchWebSessionState) {
        when (state) {
            is TwitchWebSessionState.Authenticated -> {
                if (captureGqlForDiagnostics) {
                    if (!diagnosticPageLoaded) {
                        diagnosticPageLoaded = true
                        session.loadUri(TWITCH_HOME_URL)
                    }
                } else {
                    bootstrapIntegrityContext(state.accountChanged)
                }
            }
            TwitchWebSessionState.AccountMismatch -> {
                Toast.makeText(this, R.string.account_reauthorize_same_account, Toast.LENGTH_LONG).show()
            }
            TwitchWebSessionState.RecoverableError -> {
                Toast.makeText(this, R.string.login_error_network, Toast.LENGTH_LONG).show()
            }
            TwitchWebSessionState.SignedOut,
            TwitchWebSessionState.Opening,
            TwitchWebSessionState.Validating,
            -> Unit
        }
    }

    private fun finishSuccessfully(accountChanged: Boolean) {
        if (finished) return
        finished = true
        sessionManager.retainAuthenticatedLoginSession()
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_ACCOUNT_CHANGED, accountChanged),
        )
        finish()
    }

    private fun bootstrapIntegrityContext(accountChanged: Boolean) {
        if (integrityBootstrapStarted) return
        integrityBootstrapStarted = true
        session.loadUri(TWITCH_HOME_URL)
        lifecycleScope.launch {
            repeat(INTEGRITY_BOOTSTRAP_ATTEMPTS) {
                if (sessionManager.capturedGqlHeadersForCurrentAccount() != null) {
                    finishSuccessfully(accountChanged)
                    return@launch
                }
                delay(INTEGRITY_BOOTSTRAP_POLL_MILLIS)
            }
            finishSuccessfully(accountChanged)
        }
    }

    private fun cancel() {
        if (finished) return
        finished = true
        sessionManager.closeLoginSession()
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_REAUTHORIZE = "com.github.andreyasadchy.xtra.REAUTHORIZE"
        const val EXTRA_CAPTURE_GQL = "com.github.andreyasadchy.xtra.CAPTURE_GQL"
        const val EXTRA_BOOTSTRAP_INTEGRITY = "com.github.andreyasadchy.xtra.BOOTSTRAP_INTEGRITY"
        const val EXTRA_ACCOUNT_CHANGED = "com.github.andreyasadchy.xtra.ACCOUNT_CHANGED"
        const val EXTRA_LOGOUT = "com.github.andreyasadchy.xtra.LOGOUT"
        private const val TWITCH_HOME_URL = "https://www.twitch.tv/"
        private const val INTEGRITY_BOOTSTRAP_ATTEMPTS = 60
        private const val INTEGRITY_BOOTSTRAP_POLL_MILLIS = 250L
    }
}
