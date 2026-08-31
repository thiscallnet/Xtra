package com.github.andreyasadchy.xtra.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.auth.TwitchWebSessionManager
import com.github.andreyasadchy.xtra.repository.auth.TwitchWebSessionState
import com.github.andreyasadchy.xtra.databinding.ActivityTwitchWebLoginBinding
import com.github.andreyasadchy.xtra.util.isTelevision
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView

/** Displays the process-wide Twitch browser session. Authentication belongs to the manager. */
class TwitchWebLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTwitchWebLoginBinding
    private lateinit var geckoView: GeckoView
    private lateinit var session: GeckoSession
    private lateinit var sessionManager: TwitchWebSessionManager
    private var finished = false
    private var tvPointerController: TvRemotePointerController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        binding = ActivityTwitchWebLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        geckoView = binding.geckoView
        sessionManager = (application as XtraApp).xtraModule.twitchWebSessionManager
        if (intent.getBooleanExtra(EXTRA_LOGOUT, false)) {
            lifecycleScope.launch {
                val result = sessionManager.logout()
                setResult(if (result) RESULT_OK else RESULT_CANCELED)
                finish()
            }
            return
        }

        if (isTelevision()) {
            binding.tvCursor.isVisible = true
            binding.tvRemoteHint.isVisible = true
            tvPointerController = TvRemotePointerController(binding.root, geckoView, binding.tvCursor).also { it.initialize() }
            binding.root.postDelayed({ binding.tvRemoteHint.isVisible = false }, 5_000L)
            ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
                val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
                binding.tvCursor.isVisible = !imeVisible
                binding.tvRemoteHint.isVisible = !imeVisible && !finished
                insets
            }
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isTelevision()) {
            val imeVisible = ViewCompat.getRootWindowInsets(binding.root)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            if (!imeVisible && tvPointerController?.handleKeyEvent(event) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (::geckoView.isInitialized) geckoView.releaseSession()
        // Keep the process-wide Gecko profile, but never leave a finished Activity's
        // Twitch page running invisibly. Configuration changes keep the session alive so
        // an in-progress login/2FA flow is not interrupted.
        if (!isChangingConfigurations && ::sessionManager.isInitialized) {
            sessionManager.closeLoginSession()
        }
        super.onDestroy()
    }

    private fun render(state: TwitchWebSessionState) {
        when (state) {
            is TwitchWebSessionState.Authenticated -> finishSuccessfully(state.accountChanged)
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
        lifecycleScope.launch {
            // Any identity naturally observed during the login page is already
            // persisted by the session manager. Do not delay login completion
            // for a best-effort background integrity capture.
            sessionManager.closeLoginSession()
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_ACCOUNT_CHANGED, accountChanged),
            )
            finish()
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
        const val EXTRA_ACCOUNT_CHANGED = "com.github.andreyasadchy.xtra.ACCOUNT_CHANGED"
        const val EXTRA_LOGOUT = "com.github.andreyasadchy.xtra.LOGOUT"
    }
}
