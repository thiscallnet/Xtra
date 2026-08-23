package com.github.andreyasadchy.xtra.ui.common

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.ValueCallback
import android.view.WindowManager
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import androidx.webkit.WebViewClientCompat
import com.github.andreyasadchy.xtra.databinding.DialogIntegrityBinding
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import org.json.JSONObject

class IntegrityDialog : DialogFragment() {

    interface Listener {
        fun onIntegrityTokenLoaded(callback: String?)
    }

    private var _binding: DialogIntegrityBinding? = null
    private val binding get() = _binding!!
    private var listener: Listener? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as? Listener
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogIntegrityBinding.inflate(layoutInflater)
        val context = requireContext()
        val builder = context.getAlertDialogBuilder()
            .setView(binding.root)
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        val token = TwitchApiHelper.getGQLHeaders(context, true)[C.HEADER_TOKEN]?.removePrefix("OAuth ")
        with(binding.webView) {
            cookieManager.setAcceptThirdPartyCookies(this, false)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                settings.safeBrowsingEnabled = true
            }
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClientCompat() {

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    return request.isForMainFrame && !request.url.isTrustedTwitchUrl()
                }

                override fun shouldInterceptRequest(view: WebView, webViewRequest: WebResourceRequest): WebResourceResponse? {
                    val isIntegrityRequest = webViewRequest.url.scheme == "https" &&
                        webViewRequest.url.host.equals("gql.twitch.tv", ignoreCase = true) &&
                        !webViewRequest.requestHeaders.entries.find { it.key.equals("Client-Integrity", true) }?.value.isNullOrBlank()
                    if (isIntegrityRequest) {
                        context.tokenPrefs().edit {
                            putLong(C.INTEGRITY_EXPIRATION, System.currentTimeMillis() + 57600000)
                            putString(C.GQL_HEADERS, JSONObject(
                                webViewRequest.requestHeaders.filterKeys {
                                    it.equals(C.HEADER_TOKEN, true) ||
                                            it.equals(C.HEADER_CLIENT_ID, true) ||
                                            it.equals("Client-Integrity", true) ||
                                            it.equals("X-Device-Id", true)
                                }
                            ).toString())
                        }
                        view.post {
                            if (_binding != null) {
                                listener?.onIntegrityTokenLoaded(arguments?.getString(KEY_CALLBACK))
                                dismiss()
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, webViewRequest)
                }
            }
            val loadLogin = {
                if (_binding != null) loadUrl("https://www.twitch.tv/login")
            }
            // The WebView may retain a previous account's auth-token after the
            // app session is logged out. Reconcile its cookies before loading
            // Twitch, then inject only the token owned by this app session.
            cookieManager.setCookie(
                "https://www.twitch.tv",
                "auth-token=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax",
            ) {
                cookieManager.flush()
                if (token.isNullOrBlank()) {
                    loadLogin()
                } else {
                    cookieManager.setCookie(
                        "https://www.twitch.tv",
                        "auth-token=$token; Path=/; Secure; HttpOnly; SameSite=Lax",
                        ValueCallback {
                            cookieManager.flush()
                            loadLogin()
                        },
                    )
                }
            }
        }
        return builder.create()
    }

    override fun onDismiss(dialog: DialogInterface) {
        (activity as? MainActivity)?.integrityTokenLoaded()
        _binding?.webView?.loadUrl("about:blank")
        CookieManager.getInstance().apply {
            setCookie(
                "https://www.twitch.tv",
                "auth-token=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Lax",
            ) { flush() }
        }
        super.onDismiss(dialog)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onDestroyView() {
        _binding?.webView?.apply {
            stopLoading()
            clearHistory()
            removeAllViews()
            destroy()
        }
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val KEY_CALLBACK = "callback"

        fun newInstance(callback: String?): IntegrityDialog {
            return IntegrityDialog().apply {
                arguments = Bundle().apply {
                    putString(KEY_CALLBACK, callback)
                }
            }
        }
    }
}

private fun android.net.Uri.isTrustedTwitchUrl(): Boolean {
    if (scheme != "https") return false
    val domain = host?.lowercase() ?: return false
    return domain == "twitch.tv" || domain.endsWith(".twitch.tv")
}
