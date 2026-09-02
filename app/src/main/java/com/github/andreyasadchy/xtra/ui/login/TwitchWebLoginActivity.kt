package com.github.andreyasadchy.xtra.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.BuildConfig
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
            tvPointerController = TvRemotePointerController(
                binding.root,
                geckoView,
                binding.tvCursor,
                onBeforeClick = ::focusGeckoView,
            ).also { it.initialize() }
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
        attachSession(session)

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
                            attachSession(replacementSession)
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
        if (BuildConfig.DEBUG && event.action == KeyEvent.ACTION_DOWN) {
            Log.d(TAG, "key action=${event.action} keyCode=${event.keyCode} unicode=${event.unicodeChar}")
        }
        return super.dispatchKeyEvent(event)
    }

    private fun attachSession(value: GeckoSession) {
        geckoView.isFocusable = true
        geckoView.isFocusableInTouchMode = true
        geckoView.setSession(value)
        value.setPriorityHint(GeckoSession.PRIORITY_HIGH)
        focusGeckoView()
        if (BuildConfig.DEBUG) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val imeVisible = ViewCompat.getRootWindowInsets(binding.root)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            Log.d(TAG, "gecko focus=${geckoView.hasFocus()} current=${currentFocus?.javaClass?.simpleName} " +
                "textInputView=${value.textInput.view === geckoView} active=${imm.isActive(geckoView)} ime=$imeVisible")
        }
    }

    private fun focusGeckoView() {
        geckoView.requestFocus()
        session.setFocused(true)
    }

    override fun onDestroy() {
        if (::session.isInitialized) session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT)
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
        private const val TAG = "TwitchWebLoginActivity"
        const val EXTRA_REAUTHORIZE = "com.github.andreyasadchy.xtra.REAUTHORIZE"
        const val EXTRA_ACCOUNT_CHANGED = "com.github.andreyasadchy.xtra.ACCOUNT_CHANGED"
        const val EXTRA_LOGOUT = "com.github.andreyasadchy.xtra.LOGOUT"
    }
}
