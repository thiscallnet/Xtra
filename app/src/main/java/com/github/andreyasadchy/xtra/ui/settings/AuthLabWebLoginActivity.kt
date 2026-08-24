package com.github.andreyasadchy.xtra.ui.settings

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewClientCompat
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C

/** Debug-only Twitch web login used to import an auth-token into Auth Lab memory. */
class AuthLabWebLoginActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var useButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private var detectedToken: String? = null

    private val cookiePoller = object : Runnable {
        override fun run() {
            inspectAuthCookie()
            if (!isFinishing && !isDestroyed) handler.postDelayed(this, COOKIE_POLL_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        title = getString(R.string.auth_lab_web_login_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        status = TextView(this).apply {
            text = "Log in to Twitch. The token will remain in memory only."
            setPadding(0, 0, 0, dp(8))
        }
        root.addView(status)

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            configureSettings()
            webChromeClient = WebChromeClient()
            webViewClient = LoginWebViewClient()
        }
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        useButton = Button(this).apply {
            text = "Use this Twitch web session"
            isEnabled = false
            setOnClickListener { returnDetectedToken() }
        }
        root.addView(useButton)
        root.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        })
        setContentView(root)

        webView.loadUrl(TWITCH_LOGIN_URL)
        handler.post(cookiePoller)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.configureSettings() {
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, false)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        // Android WebView's default UA contains `; wv` and `Version/4.0`. Twitch
        // rejects that UA before rendering the login form. Keep the request in
        // this WebView, but identify it as the Chrome version shipped in the
        // shared AVD so its cookie store remains readable by Xtra.
        settings.userAgentString = CHROME_USER_AGENT
        if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
            val chromeBrand = UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Google Chrome")
                .setMajorVersion(CHROME_MAJOR_VERSION)
                .setFullVersion(CHROME_FULL_VERSION)
                .build()
            val chromiumBrand = UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Chromium")
                .setMajorVersion(CHROME_MAJOR_VERSION)
                .setFullVersion(CHROME_FULL_VERSION)
                .build()
            val greaseBrand = UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Not_A Brand")
                .setMajorVersion("99")
                .setFullVersion("99.0.0.0")
                .build()
            WebSettingsCompat.setUserAgentMetadata(
                settings,
                UserAgentMetadata.Builder()
                    .setBrandVersionList(listOf(greaseBrand, chromiumBrand, chromeBrand))
                    .setFullVersion(CHROME_FULL_VERSION)
                    .setPlatform("Android")
                    .setPlatformVersion("15.0.0")
                    .setMobile(true)
                    .build(),
            )
        }
    }

    private fun inspectAuthCookie() {
        val cookie = listOf(
            CookieManager.getInstance().getCookie("https://www.twitch.tv"),
            CookieManager.getInstance().getCookie("https://passport.twitch.tv"),
            CookieManager.getInstance().getCookie("https://twitch.tv"),
        ).firstNotNullOfOrNull { cookies ->
            cookies?.split(';')
                ?.asSequence()
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("auth-token=") }
                ?.substringAfter('=')
                ?.takeIf { it.isNotBlank() }
        }
        if (cookie == null || cookie == detectedToken) return
        detectedToken = cookie
        status.text = "Twitch web session detected. Press the button below to import it into Auth Lab."
        useButton.isEnabled = true
    }

    private fun returnDetectedToken() {
        val token = detectedToken ?: return
        setResult(
            RESULT_OK,
            intent
                .putExtra(EXTRA_AUTH_TOKEN, token)
                .putExtra(EXTRA_CLIENT_ID, C.DEFAULT_GQL_CLIENT_ID_WEB),
        )
        clearAuthCookie()
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(cookiePoller)
        clearAuthCookie()
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun clearAuthCookie() {
        CookieManager.getInstance().setCookie(
            TWITCH_ROOT_URL,
            "auth-token=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax",
        ) { CookieManager.getInstance().flush() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class LoginWebViewClient : WebViewClientCompat() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            if (request.url.isTrustedTwitchUrl()) logRequest(request)
            return super.shouldInterceptRequest(view, request)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.isForMainFrame && !request.url.isTrustedTwitchUrl()

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            inspectAuthCookie()
        }

    }

    private fun logRequest(request: WebResourceRequest) {
        val headers = request.requestHeaders
        val names = headers.keys
            .map(String::lowercase)
            .sorted()
            .joinToString(",")
        val safeValues = headers.entries
            .filter { (name, _) -> name.lowercase() in SAFE_HEADER_VALUES }
            .sortedBy { (name, _) -> name.lowercase() }
            .joinToString(",") { (name, value) -> "${name.lowercase()}=${value ?: "<null>"}" }
        Log.d(
            LOG_TAG,
            "Twitch request method=${request.method} main=${request.isForMainFrame} " +
                "path=${request.url.path} headers=[$names] safeValues=[$safeValues]",
        )
    }

    companion object {
        const val EXTRA_AUTH_TOKEN = "auth_lab_auth_token"
        const val EXTRA_CLIENT_ID = "auth_lab_client_id"
        private const val TWITCH_LOGIN_URL = "https://www.twitch.tv/login"
        private const val TWITCH_ROOT_URL = "https://www.twitch.tv"
        private const val COOKIE_POLL_INTERVAL_MS = 500L
        private const val LOG_TAG = "AuthLabWebLogin"
        private val SAFE_HEADER_VALUES = setOf(
            "accept",
            "accept-language",
            "content-type",
            "origin",
            "sec-ch-ua",
            "sec-ch-ua-mobile",
            "sec-ch-ua-platform",
            "sec-fetch-dest",
            "sec-fetch-mode",
            "sec-fetch-site",
            "user-agent",
            "x-requested-with",
        )
        private const val CHROME_MAJOR_VERSION = "151"
        private const val CHROME_FULL_VERSION = "151.0.7922.137"
        private const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$CHROME_FULL_VERSION Mobile Safari/537.36"
    }
}

private fun android.net.Uri.isTrustedTwitchUrl(): Boolean {
    if (scheme != "https") return false
    val domain = host?.lowercase() ?: return false
    return domain == "twitch.tv" || domain.endsWith(".twitch.tv")
}
