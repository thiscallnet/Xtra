package com.github.andreyasadchy.xtra.ui.settings

import android.os.Bundle
import android.content.Intent
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.auth.AuthDiagnosticLog
import com.github.andreyasadchy.xtra.repository.auth.AuthLabCredentialSource
import com.github.andreyasadchy.xtra.repository.auth.AuthLabProbeResult
import com.github.andreyasadchy.xtra.repository.auth.AuthLabRepository
import com.github.andreyasadchy.xtra.repository.auth.AuthLabValidationResult
import com.github.andreyasadchy.xtra.repository.auth.AuthDiagnosticEvent
import com.github.andreyasadchy.xtra.repository.auth.AuthSessionStore
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class AuthLabActivity : AppCompatActivity() {
    private lateinit var sourceGroup: RadioGroup
    private lateinit var browserToken: EditText
    private lateinit var browserClientId: EditText
    private lateinit var channelLogin: EditText
    private lateinit var channelId: EditText
    private lateinit var output: TextView
    private lateinit var validateButton: Button
    private lateinit var matrixButton: Button
    private lateinit var repository: AuthLabRepository
    private val webLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val token = result.data?.getStringExtra(AuthLabWebLoginActivity.EXTRA_AUTH_TOKEN)
            ?.takeIf { it.isNotBlank() }
            ?: return@registerForActivityResult
        result.data?.getStringExtra(AuthLabWebLoginActivity.EXTRA_CLIENT_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let(browserClientId::setText)
        browserToken.setText(token)
        sourceGroup.check(AuthLabCredentialSource.WEB.ordinal + 1)
        output.text = "Twitch web session imported in memory. Press Validate selected credential."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.DEBUG) {
            finish()
            return
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        title = getString(R.string.auth_lab_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val app = application as XtraApp
        val module = app.xtraModule
        val context = applicationContext
        repository = AuthLabRepository(
            authRepository = module.authRepository,
            sessionStore = AuthSessionStore(context.prefs(), context.tokenPrefs()),
            networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            json = module.json,
            diagnosticLog = AuthDiagnosticLog(context.prefs()),
        )

        setContentView(buildContent())
        renderCredentialSummary()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun buildContent(): View {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        scroll.addView(root)

        root.addView(text("Run read-only Twitch checks with the same credential paths Xtra uses. Tokens stay in memory and are never written to the event history."))
        root.addView(text("Browser auth-token warning: this credential grants extensive access to the Twitch account. Never share it or include it in a report."))

        root.addView(label("Credential"))
        sourceGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        AuthLabCredentialSource.entries.forEach { source ->
            sourceGroup.addView(RadioButton(this).apply {
                id = source.ordinal + 1
                text = source.displayName()
                isChecked = source == AuthLabCredentialSource.OFFICIAL
            })
        }
        root.addView(sourceGroup)

        browserToken = edit("Browser auth-token, memory only", password = true)
        browserClientId = edit("Browser client ID", C.DEFAULT_GQL_CLIENT_ID_WEB)
        root.addView(label("Browser credential"))
        root.addView(browserToken)
        root.addView(browserClientId)
        root.addView(button("Open Twitch page login") {
            webLoginLauncher.launch(Intent(this, AuthLabWebLoginActivity::class.java))
        })

        val store = AuthSessionStore(applicationContext.prefs(), applicationContext.tokenPrefs())
        val current = store.read()
        channelLogin = edit("Channel login for read-only GQL checks", current?.login.orEmpty())
        channelId = edit("Channel ID for RewardList", current?.userId.orEmpty())
        root.addView(label("Probe inputs"))
        root.addView(channelLogin)
        root.addView(channelId)

        validateButton = button("Validate selected credential") {
            runValidation()
        }
        matrixButton = button("Run read-only matrix") {
            runMatrix()
        }
        root.addView(validateButton)
        root.addView(matrixButton)

        val validateAll = button("Validate all available credentials") {
            runValidationAll()
        }
        root.addView(validateAll)

        root.addView(label("Redacted result"))
        output = text("")
        output.setTextIsSelectable(true)
        output.typeface = android.graphics.Typeface.MONOSPACE
        root.addView(output)

        val copy = button("Copy redacted report") {
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Xtra auth lab", output.text))
            Toast.makeText(this, "Copied redacted report", Toast.LENGTH_SHORT).show()
        }
        root.addView(copy)
        root.addView(button("Clear event history") {
            repository.clearHistory()
            output.text = getString(R.string.auth_lab_history_cleared)
        })
        root.addView(button("Show event history") {
            output.text = repository.readHistory().joinToString("\n") { event -> formatEvent(event) }
                .ifBlank { "No diagnostic events recorded." }
        })
        return scroll
    }

    private fun runValidation() {
        val selected = selectedSource()
        setBusy(true)
        lifecycleScope.launch {
            val result = repository.validate(selected, browserToken.value(), browserClientId.value())
            output.text = formatValidation(result)
            renderCredentialSummary()
            setBusy(false)
        }
    }

    private fun runValidationAll() {
        setBusy(true)
        lifecycleScope.launch {
            val report = buildList {
                AuthLabCredentialSource.entries.forEach { source ->
                    add(formatValidation(repository.validate(source, browserToken.value(), browserClientId.value())))
                }
            }.joinToString("\n\n")
            output.text = report
            renderCredentialSummary()
            setBusy(false)
        }
    }

    private fun runMatrix() {
        val selected = selectedSource()
        setBusy(true)
        lifecycleScope.launch {
            val results = repository.runReadOnlyMatrix(
                source = selected,
                channelLogin = channelLogin.value(),
                channelId = channelId.value(),
                browserToken = browserToken.value(),
                browserClientId = browserClientId.value(),
            )
            output.text = results.joinToString("\n") { formatProbe(it) }
            setBusy(false)
        }
    }

    private fun renderCredentialSummary() {
        val summaries = AuthLabCredentialSource.entries.joinToString("\n") { source ->
            val value = repository.summarize(source, browserToken.valueOrNull(), browserClientId.valueOrNull())
            val identity = listOfNotNull(value.login, value.userId?.let { "user=$it" }).joinToString(" ")
            "${source.displayName()}: ${if (value.available) "available" else "not configured"}" +
                listOfNotNull(value.clientId?.let { "client=$it" }, identity, value.accessFingerprint?.let { "access=$it" }).joinToString(" ").let { suffix ->
                    if (suffix.isBlank()) "" else " ($suffix)"
                }
        }
        if (::output.isInitialized && output.text.isNullOrBlank()) output.text = summaries
    }

    private fun formatValidation(result: AuthLabValidationResult): String = buildString {
        append(result.source.displayName())
        append(" /validate")
        append(" HTTP=")
        append(result.httpStatus ?: "-")
        append(" result=")
        append(result.classification.lowercase())
        result.clientId?.let { append(" client=$it") }
        result.userId?.let { append(" user=$it") }
        result.login?.let { append(" login=$it") }
        append(" scopes=${result.scopes.size}")
        result.expiresIn?.let { append(" expires_in=$it") }
        result.accessFingerprint?.let { append(" access=$it") }
        result.refreshFingerprint?.let { append(" refresh=$it") }
        result.message?.let { append(" message=$it") }
    }

    private fun formatProbe(result: AuthLabProbeResult): String = buildString {
        append(result.source.name)
        append(" / ")
        append(result.operation.label)
        append(" HTTP=")
        append(result.httpStatus ?: "-")
        append(" result=")
        append(result.classification.lowercase())
        result.gqlSuccess?.let { append(" gql=${if (it) "success" else "errors"}") }
        result.message?.let { append(" message=$it") }
    }

    private fun formatEvent(event: AuthDiagnosticEvent): String = buildString {
        append(DateFormat.getDateTimeInstance().format(Date(event.timestampMillis)))
        append(" ")
        append(event.credential)
        append(" / ")
        append(event.operation)
        append(" HTTP=")
        append(event.httpStatus ?: "-")
        append(" result=")
        append(event.classification.lowercase())
        event.accessFingerprint?.let { append(" access=$it") }
        event.refreshFingerprint?.let { append(" refresh=$it") }
        event.message?.let { append(" message=$it") }
    }

    private fun selectedSource(): AuthLabCredentialSource = AuthLabCredentialSource.entries.firstOrNull {
        sourceGroup.checkedRadioButtonId == it.ordinal + 1
    } ?: AuthLabCredentialSource.OFFICIAL

    private fun setBusy(busy: Boolean) {
        validateButton.isEnabled = !busy
        matrixButton.isEnabled = !busy
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun text(value: String) = TextView(this).apply {
        text = value
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun edit(hintText: String, value: String? = null, password: Boolean = false) = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
        value?.let { setText(it) }
        inputType = if (password) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT
        }
        if (password) transformationMethod = PasswordTransformationMethod.getInstance()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(4) }
    }

    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) }
    }

    private fun EditText.value(): String = text?.toString()?.trim().orEmpty()

    private fun EditText.valueOrNull(): String? = value().takeIf { it.isNotBlank() }

    private fun AuthLabCredentialSource.displayName(): String = when (this) {
        AuthLabCredentialSource.OFFICIAL -> "Official Xtra OAuth"
        AuthLabCredentialSource.COMPATIBILITY -> "Xtra compatibility OAuth"
        AuthLabCredentialSource.WEB -> "Twitch web session"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
