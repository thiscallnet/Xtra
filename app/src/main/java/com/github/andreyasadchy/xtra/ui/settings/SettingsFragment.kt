package com.github.andreyasadchy.xtra.ui.settings

import android.Manifest
import android.animation.ValueAnimator
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.forEach
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem
import com.github.andreyasadchy.xtra.repository.auth.AuthHealth
import com.github.andreyasadchy.xtra.ui.account.AccountActivity
import com.github.andreyasadchy.xtra.ui.appearance.AppearanceRepository
import com.github.andreyasadchy.xtra.ui.appearance.DEFAULT_BACKGROUND_VISIBILITY
import com.github.andreyasadchy.xtra.ui.appearance.PlayerBackgroundMode
import com.github.andreyasadchy.xtra.ui.following.overview.FollowingOverviewSections
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationScheduler
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationService
import com.github.andreyasadchy.xtra.ui.main.WatchStreakReminderNotifier
import com.github.andreyasadchy.xtra.repository.WatchStreakReminderStateStore
import com.github.andreyasadchy.xtra.ui.settings.SettingsViewModel.Companion.SettingsViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.SettingsMigration
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.chatBadgeSizeOrDefault
import com.github.andreyasadchy.xtra.util.DEFAULT_CHAT_UI_BATCH_INTERVAL_MS
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.parseChatUiBatchIntervalMs
import com.github.andreyasadchy.xtra.ui.chat.parseChatHighlightColor
import com.github.andreyasadchy.xtra.util.parseChatBadgeSize
import com.github.andreyasadchy.xtra.util.proxyPrefs
import com.github.andreyasadchy.xtra.util.rawPrefs
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsFragment : MaterialPreferenceFragment() {

    private val settingsScreen: String?
        get() = arguments?.getString(ARG_SETTINGS_SCREEN)

    private val viewModel: SettingsViewModel by activityViewModels { SettingsViewModelFactory }
    private var backupResultLauncher: ActivityResultLauncher<Intent>? = null
    private var restoreResultLauncher: ActivityResultLauncher<Intent>? = null
    private var backgroundPhotoLauncher: ActivityResultLauncher<Array<String>>? = null
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private var pendingNotificationPermission: NotificationPermissionRequester? = null

    private enum class NotificationPermissionRequester {
        LIVE,
        WATCH_STREAK,
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingNotificationPermission = savedInstanceState
            ?.getString(KEY_PENDING_NOTIFICATION_PERMISSION)
            ?.let { value -> NotificationPermissionRequester.values().firstOrNull { it.name == value } }
        backupResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                result.data?.data?.let {
                    viewModel.backupSettings(it.toString())
                }
            }
        }
        restoreResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val list = mutableListOf<String>()
                result.data?.clipData?.let { clipData ->
                    for (i in 0 until clipData.itemCount) {
                        val item = clipData.getItemAt(i)
                        item.uri?.let {
                            list.add(it.toString())
                        }
                    }
                } ?: result.data?.data?.let {
                    list.add(it.toString())
                }
                viewModel.restoreSettings(
                    list = list,
                )
            }
        }
        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            when (pendingNotificationPermission) {
                null -> Unit
                NotificationPermissionRequester.LIVE -> if (granted) {
                    findPreference<SwitchPreferenceCompat>("live_notifications_enabled")?.let {
                        it.isChecked = true
                        toggleLiveNotifications(true)
                    }
                } else {
                    findPreference<SwitchPreferenceCompat>("live_notifications_enabled")?.isChecked = false
                    viewModel.reportLiveNotificationPermissionDenied()
                }
                NotificationPermissionRequester.WATCH_STREAK -> {
                    findPreference<SwitchPreferenceCompat>(C.WATCH_STREAK_PROTECTION_ENABLED)?.isChecked = granted
                    requireContext().prefs().edit { putBoolean(C.WATCH_STREAK_PROTECTION_ENABLED, granted) }
                    if (!granted) {
                        WatchStreakReminderStateStore(requireContext()).clear()
                    }
                    LiveNotificationScheduler.refresh(requireContext())
                }
            }
            pendingNotificationPermission = null
            updateLiveNotificationsSummary()
        }
        backgroundPhotoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val selectedUri = uri ?: return@registerForActivityResult
            val persisted = runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    selectedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                true
            }.getOrDefault(false)
            if (!persisted) {
                Toast.makeText(requireContext(), R.string.settings_player_background_persist_failed, Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }
            AppearanceRepository(requireContext()).setPlayerBackgroundUri(selectedUri)
            findPreference<ListPreference>(C.PLAYER_BACKGROUND_MODE)?.value = PlayerBackgroundMode.CUSTOM.preferenceValue
            updateChatAppearancePreferences()
            findPreference<ChatAppearancePreviewPreference>("chat_appearance_preview")?.refreshPreview()
            (requireActivity() as? SettingsActivity)?.setResult()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_PENDING_NOTIFICATION_PERMISSION, pendingNotificationPermission?.name)
        super.onSaveInstanceState(outState)
    }

    private fun toggleLiveNotifications(enabled: Boolean) {
        viewModel.toggleNotifications(
            enabled = enabled,
            networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
            gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true),
            helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext())
        )
    }

    private fun showLiveNotificationFailure(failure: LiveNotificationFailure) {
        val operation = when (failure.stage) {
            LiveNotificationSetupStage.NOTIFICATION_PERMISSION_CHANNEL_VALIDATION ->
                getString(R.string.live_notifications_failure_operation_validation)
            LiveNotificationSetupStage.NOTIFICATION_USER_FOLLOW_SYNC ->
                getString(R.string.live_notifications_failure_operation_sync)
            LiveNotificationSetupStage.INITIAL_LIVE_STREAM_BASELINE_FETCH ->
                getString(R.string.live_notifications_failure_operation_baseline)
            LiveNotificationSetupStage.SCHEDULER_REALTIME_MONITOR_STARTUP ->
                getString(R.string.live_notifications_failure_operation_scheduler)
        }
        val reason = when (failure.reason) {
            LiveNotificationFailureReason.NOTIFICATION_PERMISSION_OR_CHANNEL ->
                getString(R.string.live_notifications_failure_reason_notifications)
            LiveNotificationFailureReason.MISSING_AUTHENTICATION ->
                getString(R.string.live_notifications_failure_reason_authentication)
            LiveNotificationFailureReason.HTTP_401_UNAUTHORIZED ->
                getString(R.string.live_notifications_failure_reason_unauthorized)
            LiveNotificationFailureReason.HTTP_403_FORBIDDEN ->
                getString(R.string.live_notifications_failure_reason_forbidden)
            LiveNotificationFailureReason.HTTP_429_RATE_LIMITED ->
                getString(R.string.live_notifications_failure_reason_rate_limited)
            LiveNotificationFailureReason.TWITCH_GRAPHQL_ERROR ->
                getString(R.string.live_notifications_failure_reason_graphql)
            LiveNotificationFailureReason.TWITCH_HTTP_5XX ->
                getString(R.string.live_notifications_failure_reason_server)
            LiveNotificationFailureReason.DNS_CONNECTIVITY_OR_TIMEOUT ->
                getString(R.string.live_notifications_failure_reason_connectivity)
            LiveNotificationFailureReason.MALFORMED_OR_UNEXPECTED_TWITCH_RESPONSE ->
                getString(R.string.live_notifications_failure_reason_malformed)
            LiveNotificationFailureReason.LOCAL_DATABASE_FAILURE ->
                getString(R.string.live_notifications_failure_reason_database)
            LiveNotificationFailureReason.UNKNOWN_FAILURE ->
                getString(R.string.live_notifications_failure_reason_unknown)
        }
        val action = when (failure.reason) {
            LiveNotificationFailureReason.NOTIFICATION_PERMISSION_OR_CHANNEL ->
                getString(R.string.live_notifications_failure_action_notifications)
            LiveNotificationFailureReason.MISSING_AUTHENTICATION,
            LiveNotificationFailureReason.HTTP_401_UNAUTHORIZED,
            LiveNotificationFailureReason.HTTP_403_FORBIDDEN,
            -> getString(R.string.live_notifications_failure_action_sign_in)
            LiveNotificationFailureReason.HTTP_429_RATE_LIMITED ->
                getString(R.string.live_notifications_failure_action_rate_limited)
            LiveNotificationFailureReason.TWITCH_GRAPHQL_ERROR ->
                getString(R.string.live_notifications_failure_action_retry)
            LiveNotificationFailureReason.TWITCH_HTTP_5XX ->
                getString(R.string.live_notifications_failure_action_server)
            LiveNotificationFailureReason.DNS_CONNECTIVITY_OR_TIMEOUT ->
                getString(R.string.live_notifications_failure_action_connection)
            LiveNotificationFailureReason.MALFORMED_OR_UNEXPECTED_TWITCH_RESPONSE,
            LiveNotificationFailureReason.LOCAL_DATABASE_FAILURE,
            LiveNotificationFailureReason.UNKNOWN_FAILURE,
            -> getString(R.string.live_notifications_failure_action_retry)
        }
        val technicalDetails = buildString {
            failure.operation?.let {
                append("Operation: ").append(it)
            }
            failure.exceptionClass?.let {
                if (isNotEmpty()) append("; ")
                append(it)
            }
            failure.httpStatus?.let {
                if (isNotEmpty()) append("; ")
                append("HTTP ").append(it)
            }
            failure.technicalMessage?.let {
                if (isNotEmpty()) append(": ")
                if (failure.reason == LiveNotificationFailureReason.TWITCH_GRAPHQL_ERROR) {
                    append(getString(R.string.live_notifications_failure_twitch_returned, it))
                } else {
                    append(it)
                }
            }
        }.ifBlank { getString(R.string.live_notifications_failure_no_details) }
        val message = buildString {
            append(
                getString(
                    R.string.live_notifications_failure_message,
                    operation,
                    reason,
                    action,
                    technicalDetails,
                )
            )
            if (failure.isTwitchRelated) {
                append("\n\n")
                append(getString(R.string.live_notifications_failure_troubleshooting))
            }
        }
        val builder = requireActivity().getAlertDialogBuilder()
            .setTitle(R.string.live_notifications_enable_failed_title)
            .setMessage(message)
            .setNeutralButton(R.string.live_notifications_copy_details) { _, _ ->
                copyLiveNotificationFailureDetails(failure)
            }
        when {
            failure.reason == LiveNotificationFailureReason.NOTIFICATION_PERMISSION_OR_CHANNEL -> {
                builder.setPositiveButton(R.string.live_notifications_open_settings) { _, _ ->
                    openNotificationSettings()
                }
            }
            failure.isAuthenticationFailure -> {
                builder.setPositiveButton(R.string.live_notifications_sign_in_again) { _, _ ->
                    (requireActivity() as SettingsActivity).openAccountAction()
                }
            }
            failure.canRetry -> {
                builder.setPositiveButton(R.string.retry) { _, _ ->
                    toggleLiveNotifications(true)
                }
            }
            else -> builder.setPositiveButton(android.R.string.ok, null)
        }
        if (failure.reason != LiveNotificationFailureReason.UNKNOWN_FAILURE || failure.canRetry) {
            builder.setNegativeButton(android.R.string.cancel, null)
        }
        builder.show()
    }

    private fun copyLiveNotificationFailureDetails(failure: LiveNotificationFailure) {
        val clipboard = requireContext().getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(
            android.content.ClipData.newPlainText(
                "Live notification failure",
                buildString {
                    appendLine("Live notification enable failure")
                    appendLine("Stage: ${failure.stage}")
                    appendLine("Reason: ${failure.reason}")
                    failure.operation?.let { appendLine("Operation: $it") }
                    failure.httpStatus?.let { appendLine("HTTP status: $it") }
                    failure.rateLimitResetEpochSeconds?.let { appendLine("Rate-limit reset: $it") }
                    failure.rateLimitLimit?.let { appendLine("Rate-limit limit: $it") }
                    failure.rateLimitRemaining?.let { appendLine("Rate-limit remaining: $it") }
                    failure.exceptionClass?.let { appendLine("Exception: $it") }
                    failure.technicalMessage?.let { appendLine("Message: $it") }
                },
            ),
        )
        Toast.makeText(requireContext(), R.string.settings_diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    private fun styleLiveNotificationModeEntry(entry: CharSequence, selected: Boolean): CharSequence {
        val text = entry.toString()
        val titleEnd = text.indexOf('\n').takeUnless { it == -1 } ?: text.length
        return SpannableString(text).apply {
            setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                titleEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (selected) {
                setSpan(
                    ForegroundColorSpan(
                        MaterialColors.getColor(
                            requireContext(),
                            androidx.appcompat.R.attr.colorPrimary,
                            requireContext().getColor(R.color.accent),
                        )
                    ),
                    0,
                    titleEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
    }

    private fun styleLiveNotificationModeSummary(mode: String): CharSequence? {
        val title = when (mode) {
            C.LIVE_NOTIFICATIONS_MODE_BATTERY -> getString(R.string.live_notifications_mode_battery)
            C.LIVE_NOTIFICATIONS_MODE_FAST -> getString(R.string.live_notifications_mode_fast)
            C.LIVE_NOTIFICATIONS_MODE_PERSISTENT -> getString(R.string.live_notifications_mode_persistent)
            else -> return null
        }
        val details = when (mode) {
            C.LIVE_NOTIFICATIONS_MODE_BATTERY -> getString(R.string.live_notifications_battery_summary)
            C.LIVE_NOTIFICATIONS_MODE_FAST -> getString(R.string.live_notifications_fast_summary)
            C.LIVE_NOTIFICATIONS_MODE_PERSISTENT -> getString(R.string.live_notifications_persistent_summary)
            else -> return null
        }
        return SpannableString("$title\n$details").apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(
                ForegroundColorSpan(
                    MaterialColors.getColor(
                        requireContext(),
                        androidx.appcompat.R.attr.colorPrimary,
                        requireContext().getColor(R.color.accent),
                    )
                ),
                0,
                title.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun updateLiveNotificationsSummary(selectedMode: String? = null) {
        val preference = findPreference<SwitchPreferenceCompat>("live_notifications_enabled") ?: return
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(requireActivity(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        preference.summary = when {
            !permissionGranted -> getString(R.string.live_notifications_permission_required)
            !LiveNotificationScheduler.canPostNotifications(requireContext()) -> getString(R.string.live_notifications_blocked)
            else -> getString(R.string.live_notifications_summary)
        }
        findPreference<ListPreference>(C.LIVE_NOTIFICATIONS_MODE)?.let { modePreference ->
            val mode = selectedMode ?: modePreference.value ?: C.LIVE_NOTIFICATIONS_MODE_BATTERY
            val entries = modePreference.entries
            val entryValues = modePreference.entryValues
            if (entries != null && entryValues != null) {
                val styledEntries = entries.mapIndexed { index, entry ->
                    styleLiveNotificationModeEntry(
                        entry = entry,
                        selected = entryValues.getOrNull(index)?.toString() == mode,
                    )
                }.toTypedArray()
                modePreference.entries = styledEntries
                modePreference.summary = styleLiveNotificationModeSummary(mode)
            }
        }
        updateLiveNotificationsBatteryOptimization()
        updateLiveNotificationServiceNotification()
        updateLiveNotificationTroubleshooting()
        updateWatchStreakProtectionSummary()
    }

    private fun updateWatchStreakProtectionSummary() {
        val preference = findPreference<SwitchPreferenceCompat>(C.WATCH_STREAK_PROTECTION_ENABLED) ?: return
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(requireActivity(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        preference.summary = when {
            !permissionGranted -> getString(R.string.watch_streak_protection_permission_required)
            !LiveNotificationScheduler.canPostWatchStreakNotifications(requireContext()) -> getString(R.string.watch_streak_protection_blocked)
            else -> getString(R.string.watch_streak_protection_summary)
        }
        findPreference<EditTextPreference>(C.WATCH_STREAK_MINIMUM)?.let { threshold ->
            val value = threshold.text?.toIntOrNull()?.takeIf { it >= 1 }
                ?: runCatching { requireContext().prefs().getInt(C.WATCH_STREAK_MINIMUM, 1) }
                    .getOrDefault(1)
                    .coerceAtLeast(1)
            threshold.summary = getString(R.string.watch_streak_minimum_summary, value)
        }
    }

    private fun updateLiveNotificationTroubleshooting() {
        val preference = findPreference<Preference>("live_notifications_troubleshooting") ?: return
        val prefs = requireContext().prefs()
        val stage = prefs.getString(C.LIVE_NOTIFICATION_ENABLE_FAILURE_STAGE, null)
            ?.let { runCatching { LiveNotificationSetupStage.valueOf(it) }.getOrNull() }
        val reason = prefs.getString(C.LIVE_NOTIFICATION_ENABLE_FAILURE_REASON, null)
            ?.let { runCatching { LiveNotificationFailureReason.valueOf(it) }.getOrNull() }
        preference.isVisible = shouldShowLiveNotificationTroubleshooting(
            stage = stage,
            reason = reason,
            failureAt = prefs.getLong(C.LIVE_NOTIFICATION_LAST_SETUP_ERROR_AT, 0L),
            successAt = prefs.getLong(C.LIVE_NOTIFICATION_LAST_SETUP_SUCCESS, 0L),
            exceptionClass = prefs.getString(C.LIVE_NOTIFICATION_ENABLE_FAILURE_EXCEPTION, null),
            technicalMessage = prefs.getString(C.LIVE_NOTIFICATION_ENABLE_FAILURE_MESSAGE, null),
            enabled = prefs.getBoolean(C.LIVE_NOTIFICATIONS_ENABLED, false),
        )
    }

    private fun updateLiveNotificationsBatteryOptimization() {
        val preference = findPreference<Preference>("live_notifications_battery_optimization") ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            preference.isVisible = false
            return
        }
        val persistent = LiveNotificationScheduler.mode(requireContext()) == C.LIVE_NOTIFICATIONS_MODE_PERSISTENT
        preference.isVisible = persistent
        val powerManager = requireContext().getSystemService(PowerManager::class.java)
        val unrestricted = powerManager?.isIgnoringBatteryOptimizations(requireContext().packageName) == true
        preference.summary = getString(
            if (unrestricted) {
                R.string.live_notifications_battery_optimization_unrestricted
            } else {
                R.string.live_notifications_battery_optimization_optimized
            }
        )
        preference.setOnPreferenceClickListener {
            openBatteryOptimizationSettings()
            true
        }
    }

    private fun openBatteryOptimizationSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        val context = requireContext()
        val packageUri = "package:${context.packageName}".toUri()
        val powerManager = context.getSystemService(PowerManager::class.java)
        val unrestricted = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
        val settingsIntent = if (unrestricted) {
            // Android treats the request action as a no-op when the app is already
            // exempt. Open the app's settings in that case so the tap always has
            // a visible result and OEM-specific battery controls remain reachable.
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = packageUri
            }
        } else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = packageUri
            }
        }
        runCatching {
            startActivity(settingsIntent)
        }.getOrElse {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }

    private fun updateLiveNotificationServiceNotification() {
        val preference = findPreference<Preference>("live_notifications_service_notification") ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            preference.isVisible = false
            return
        }
        val persistent = LiveNotificationScheduler.mode(requireContext()) == C.LIVE_NOTIFICATIONS_MODE_PERSISTENT
        preference.isVisible = persistent
        if (!persistent) {
            return
        }
        LiveNotificationService.ensureNotificationChannel(requireContext())
        val channel = requireContext().getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(LiveNotificationService.SERVICE_CHANNEL_ID)
        preference.summary = getString(
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) {
                R.string.live_notifications_service_notification_hidden
            } else {
                R.string.live_notifications_service_notification_visible
            }
        )
        preference.setOnPreferenceClickListener {
            if (!LiveNotificationService.openNotificationChannelSettings(requireContext())) {
                openNotificationSettings()
            }
            true
        }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            })
        } catch (_: ActivityNotFoundException) {
            // The summary still explains the blocked state when no system screen is available.
        }
    }

    private fun openChannelNotificationSettings(channelResId: Int) {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            putExtra(Settings.EXTRA_CHANNEL_ID, getString(channelResId))
        }
        runCatching { startActivity(intent) }.onFailure { openNotificationSettings() }
    }

    private fun openPromotionSettings(fallback: () -> Unit) {
        if (Build.VERSION.SDK_INT < 36) {
            fallback()
            return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            })
        }.onFailure { fallback() }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        if (settingsScreen == SCREEN_LIVE_NOTIFICATIONS) {
            LiveNotificationScheduler.migrateMode(requireContext())
        }
        if (settingsScreen == SCREEN_PROXY) {
            // The data store must be installed before inflation. Preference only
            // reads its initial value while it is being attached.
            preferenceManager.preferenceDataStore = createProxyPreferenceDataStore()
        }
        setPreferencesFromResource(
            when (settingsScreen) {
                SCREEN_LIVE_NOTIFICATIONS -> R.xml.live_notification_preferences
                SCREEN_SYSTEM_MEDIA -> R.xml.system_media_notification_preferences
                SCREEN_PREDICTION_LIVE_UPDATES -> R.xml.prediction_live_update_preferences
                SCREEN_DROPS_LIVE_UPDATES -> R.xml.drops_live_update_preferences
                SCREEN_APP -> R.xml.app_preferences
                SCREEN_ACCOUNT -> R.xml.account_preferences
                SCREEN_LANGUAGE -> R.xml.language_preferences
                SCREEN_BACKUP -> R.xml.backup_preferences
                SCREEN_ABOUT -> R.xml.about_preferences
                SCREEN_DISPLAY_COMPATIBILITY -> R.xml.display_compatibility_preferences
                SCREEN_BROWSING_INFORMATION -> R.xml.browsing_information_preferences
                SCREEN_BROWSING_SEARCH -> R.xml.browsing_search_preferences
                SCREEN_TABS -> R.xml.tabs_preferences
                SCREEN_CLIP -> R.xml.clip_preferences
                SCREEN_PLAYER_SEEK -> R.xml.player_seek_preferences
                SCREEN_PLAYER_GESTURES -> R.xml.player_gestures_preferences
                SCREEN_PLAYER_SWIPE_CONTROLS -> R.xml.player_swipe_controls_preferences
                SCREEN_PLAYER_INFORMATION -> R.xml.player_information_preferences
                SCREEN_CHAT_APPEARANCE -> R.xml.chat_appearance_preferences
                SCREEN_CHAT_USERNAME -> R.xml.chat_username_preferences
                SCREEN_CHAT_EMOTES -> R.xml.chat_emotes_preferences
                SCREEN_CHAT_7TV -> R.xml.chat_7tv_preferences
                SCREEN_CHAT_FEATURES -> R.xml.chat_features_preferences
                SCREEN_CHAT_HISTORY -> R.xml.chat_history_preferences
                SCREEN_CHAT_TRANSLATION -> R.xml.chat_translation_preferences
                SCREEN_CHAT_VISIBILITY -> R.xml.chat_visibility_preferences
                SCREEN_DOWNLOAD_LIVE -> R.xml.download_live_preferences
                SCREEN_PROXY -> R.xml.proxy_preferences
                SCREEN_DEVELOPER -> R.xml.developer_preferences
                else -> R.xml.general_preferences
            },
            rootKey,
        )
        findPreference<ListPreference>(C.UI_LANGUAGE)?.apply {
            val lang = AppCompatDelegate.getApplicationLocales()
            if (lang.isEmpty) {
                setValueIndex(findIndexOfValue("auto"))
            } else {
                try {
                    setValueIndex(findIndexOfValue(lang.toLanguageTags()))
                } catch (e: Exception) {
                    try {
                        setValueIndex(findIndexOfValue(
                            lang.toLanguageTags().substringBefore("-").let {
                                when (it) {
                                    "id" -> "in"
                                    "pt" -> "pt-BR"
                                    "zh" -> "zh-TW"
                                    else -> it
                                }
                            }
                        ))
                    } catch (e: Exception) {
                        setValueIndex(findIndexOfValue("en"))
                    }
                }
            }
            setOnPreferenceChangeListener { _, value ->
                AppCompatDelegate.setApplicationLocales(
                    LocaleListCompat.forLanguageTags(
                        if (value.toString() == "auto") {
                            null
                        } else {
                            value.toString()
                        }
                    )
                )
                true
            }
        }
        findPreference<SwitchPreferenceCompat>(C.UI_DRAW_BEHIND_CUTOUTS)?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setOnPreferenceChangeListener { _, _ ->
                    (requireActivity() as? SettingsActivity)?.changed = true
                    requireActivity().recreate()
                    true
                }
            } else {
                isVisible = false
            }
        }
        findPreference<SwitchPreferenceCompat>("live_notifications_enabled")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (enabled) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ActivityCompat.checkSelfPermission(requireActivity(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingNotificationPermission = NotificationPermissionRequester.LIVE
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    false
                } else if (!LiveNotificationScheduler.canPostNotifications(requireContext())) {
                    toggleLiveNotifications(true)
                    false
                } else {
                    toggleLiveNotifications(true)
                    true
                }
            } else {
                toggleLiveNotifications(enabled)
                true
            }
        }
        findPreference<ListPreference>(C.LIVE_NOTIFICATIONS_MODE)?.setOnPreferenceChangeListener { _, newValue ->
            requireContext().prefs().edit {
                putString(C.LIVE_NOTIFICATIONS_MODE, newValue.toString())
            }
            if (findPreference<SwitchPreferenceCompat>(C.LIVE_NOTIFICATIONS_ENABLED)?.isChecked == true) {
                LiveNotificationScheduler.refresh(requireContext())
            }
            updateLiveNotificationsSummary(selectedMode = newValue.toString())
            true
        }
        updateLiveNotificationsSummary()
        findPreference<Preference>("backup_settings")?.setOnPreferenceClickListener {
            backupResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))
            true
        }
        findPreference<Preference>("restore_settings")?.setOnPreferenceClickListener {
            restoreResultLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            })
            true
        }
        findPreference<Preference>("about_version")?.summary = getString(
            R.string.app_version_summary,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        findPreference<Preference>("about_build")?.summary = getString(
            R.string.app_build_summary,
            BuildConfig.BUILD_TYPE,
        )
        findPreference<Preference>("about_package")?.summary = BuildConfig.APPLICATION_ID
        if (settingsScreen == SCREEN_PLAYER_GESTURES) {
            findPreference<Preference>("player_swipe_controls")?.setOnPreferenceClickListener {
                findNavController().navigate(R.id.playerSwipeControlsFragment)
                true
            }
        }
        if (settingsScreen == SCREEN_PLAYER_SWIPE_CONTROLS) {
            configurePlayerSwipeControlsPreferences()
        }
        if (settingsScreen == SCREEN_ACCOUNT) configureAccountPreferences()
        configureRedesignedPreferences()
    }

    private fun configureAccountPreferences() {
        val activity = requireActivity() as SettingsActivity
        findPreference<Preference>("account_action")?.apply {
            val authHealth = activity.accountAuthHealth()
            val isConnected = isSettingsAccountConnected(authHealth)
            val needsReauthentication = authHealth == AuthHealth.REAUTH_REQUIRED
            title = getString(
                when {
                    needsReauthentication -> R.string.auth_health_reconnect
                    isConnected -> R.string.settings_account_manage
                    else -> R.string.log_in
                },
            )
            summary = getString(
                when {
                    needsReauthentication -> R.string.auth_health_reauth_message
                    isConnected -> R.string.settings_account_manage_summary
                    else -> R.string.settings_account_signed_out_summary
                },
            )
            setOnPreferenceClickListener {
                if (activity.isAccountConnected()) {
                    activity.accountResultLauncher?.launch(Intent(requireContext(), AccountActivity::class.java))
                } else {
                    activity.openAccountAction()
                }
                true
            }
        }
    }

    private fun configurePlayerSwipeControlsPreferences() {
        val preferences = requireContext().prefs()
        val master = findPreference<SwitchPreferenceCompat>(C.PLAYER_SWIPE_CONTROLS_ENABLED)
        val left = findPreference<ListPreference>(C.PLAYER_SWIPE_LEFT_GESTURE)
        val right = findPreference<ListPreference>(C.PLAYER_SWIPE_RIGHT_GESTURE)
        val top = findPreference<ListPreference>(C.PLAYER_SWIPE_TOP_GESTURE)
        val preview = findPreference<PlayerSwipeControlsPreviewPreference>("player_swipe_zones_preview")
        val edgeWidth = findPreference<SeekBarPreference>(C.PLAYER_SWIPE_EDGE_WIDTH_PERCENT)
        val speedHeight = findPreference<SeekBarPreference>(C.PLAYER_SWIPE_SPEED_ZONE_HEIGHT_PERCENT)
        val brightnessSensitivity = findPreference<SeekBarPreference>(C.PLAYER_SWIPE_BRIGHTNESS_SENSITIVITY)
        val volumeSensitivity = findPreference<SeekBarPreference>(C.PLAYER_SWIPE_VOLUME_SENSITIVITY)
        val speedSensitivity = findPreference<SeekBarPreference>(C.PLAYER_SWIPE_SPEED_SENSITIVITY)
        val speedStep = findPreference<ListPreference>(C.PLAYER_SWIPE_SPEED_STEP)
        val ignoreWhenLocked = findPreference<SwitchPreferenceCompat>(C.PLAYER_SWIPE_IGNORE_WHEN_LOCKED)

        fun refresh() {
            val leftGesture = preferences.getString(C.PLAYER_SWIPE_LEFT_GESTURE, "brightness")
            val rightGesture = preferences.getString(C.PLAYER_SWIPE_RIGHT_GESTURE, "volume")
            val topGesture = preferences.getString(C.PLAYER_SWIPE_TOP_GESTURE, "off")
            val edgeGesturesEnabled = leftGesture != "off" || rightGesture != "off"
            val speedEnabled = topGesture == "speed"

            edgeWidth?.isEnabled = edgeGesturesEnabled
            brightnessSensitivity?.isEnabled =
                (leftGesture == "brightness" || rightGesture == "brightness")
            volumeSensitivity?.isEnabled =
                (leftGesture == "volume" || rightGesture == "volume")
            speedHeight?.isEnabled = speedEnabled
            speedSensitivity?.isEnabled = speedEnabled
            speedStep?.isEnabled = speedEnabled
            ignoreWhenLocked?.isEnabled = true
            preview?.refreshPreview()
        }

        listOf(master, left, right, top, edgeWidth, speedHeight, brightnessSensitivity,
            volumeSensitivity, speedSensitivity, speedStep, ignoreWhenLocked).forEach { preference ->
            preference?.setOnPreferenceChangeListener { _, _ ->
                listView.post(::refresh)
                true
            }
        }
        refresh()
    }

    private fun createProxyPreferenceDataStore(): PreferenceDataStore {
        val normalPreferences = requireContext().rawPrefs()
        val proxyPreferences = requireContext().proxyPrefs()
        val proxyKeys = setOf(C.PROXY_HOST, C.PROXY_PORT, C.PROXY_USER, C.PROXY_PASSWORD)
        fun preferencesFor(key: String) = if (key in proxyKeys) proxyPreferences else normalPreferences

        return object : PreferenceDataStore() {
            override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
                preferencesFor(key).getBoolean(key, defaultValue)

            override fun getFloat(key: String, defaultValue: Float): Float =
                preferencesFor(key).getFloat(key, defaultValue)

            override fun getInt(key: String, defaultValue: Int): Int =
                preferencesFor(key).getInt(key, defaultValue)

            override fun getLong(key: String, defaultValue: Long): Long =
                preferencesFor(key).getLong(key, defaultValue)

            override fun getString(key: String, defaultValue: String?): String? =
                preferencesFor(key).getString(key, defaultValue)

            override fun getStringSet(key: String, defaultValue: Set<String>?): Set<String>? =
                preferencesFor(key).getStringSet(key, defaultValue?.toMutableSet())

            override fun putBoolean(key: String, value: Boolean) {
                preferencesFor(key).edit { putBoolean(key, value) }
            }

            override fun putFloat(key: String, value: Float) {
                preferencesFor(key).edit { putFloat(key, value) }
            }

            override fun putInt(key: String, value: Int) {
                preferencesFor(key).edit { putInt(key, value) }
            }

            override fun putLong(key: String, value: Long) {
                preferencesFor(key).edit { putLong(key, value) }
            }

            override fun putString(key: String, value: String?) {
                preferencesFor(key).edit { putString(key, value) }
            }

            override fun putStringSet(key: String, value: Set<String>?) {
                preferencesFor(key).edit { putStringSet(key, value?.toMutableSet()) }
            }
        }
    }

    private fun configureRedesignedPreferences() {
        val activity = requireActivity() as SettingsActivity
        val destinations = mapOf(
            "app_language_page" to R.id.languageSettingsFragment,
            "app_updates_page" to R.id.updateSettingsFragment,
            "app_backup_page" to R.id.backupSettingsFragment,
            "app_help_about_page" to R.id.aboutSettingsFragment,
            "appearance_display_compatibility" to R.id.appearanceDisplayCompatibilityFragment,
            "browsing_displayed_information" to R.id.browsingInformationFragment,
            "browsing_customize_tabs" to R.id.browsingTabsFragment,
            "browsing_search_history" to R.id.browsingSearchFragment,
            "player_seek_controls" to R.id.playerSeekFragment,
            "player_gestures" to R.id.playerGesturesFragment,
            "player_information" to R.id.playerInformationFragment,
            "chat_appearance_page" to R.id.chatAppearanceFragment,
            "chat_username_page" to R.id.chatUsernameFragment,
            "chat_emotes_page" to R.id.chatEmotesFragment,
            "chat_features_page" to R.id.chatFeaturesFragment,
            "chat_history_page" to R.id.chatHistoryFragment,
            "chat_translation_page" to R.id.chatTranslationFragment,
            "chat_visibility_page" to R.id.chatVisibilityFragment,
            "download_live_page" to R.id.downloadLiveFragment,
            "advanced_proxy" to R.id.proxySettingsFragment,
            "developer_options" to R.id.developerSettingsFragment,
        )
        destinations.forEach { (key, destination) ->
            findPreference<Preference>(key)?.setOnPreferenceClickListener {
                findNavController().navigate(destination)
                true
            }
        }
        findPreference<Preference>("about_discord")?.setOnPreferenceClickListener {
            activity.openDiscord()
            true
        }
        listOf(
            C.UI_NAVIGATION_TAB_LIST,
            C.UI_FOLLOWING_TABS,
            C.UI_FOLLOWING_OVERVIEW_SECTIONS,
            C.UI_SAVED_TABS,
            C.UI_CHANNEL_TABS,
            C.UI_GAME_TABS,
            C.UI_SEARCH_TABS,
        ).forEach { key ->
            findPreference<Preference>("${key}_dialog")?.setOnPreferenceClickListener {
                if (key == C.UI_FOLLOWING_OVERVIEW_SECTIONS) {
                    val sections = FollowingOverviewSections.resolve(requireContext().prefs().getString(key, null)).map { entry ->
                        val sectionKey = entry.substringBefore(':')
                        SettingsDragListItem(
                            key = sectionKey,
                            text = getString(FollowingOverviewSections.titleRes(sectionKey)),
                            default = false,
                            enabled = entry.substringAfterLast(':') != "0",
                        )
                    }.toMutableList()
                    activity.showDragListDialog(sections, key, it.title, showDefaultSelector = false)
                } else {
                    activity.showTabDialog(key, it.title)
                }
                true
            }
        }
        findPreference<Preference>("reset_settings")?.setOnPreferenceClickListener {
            requireActivity().getAlertDialogBuilder()
                .setTitle(R.string.settings_reset_action)
                .setMessage(R.string.settings_reset_summary)
                .setPositiveButton(R.string.yes) { _, _ ->
                    val context = requireContext()
                    LiveNotificationScheduler.disable(context)
                    viewModel.resetNotificationState()
                    SettingsMigration.resetUserPreferences(context)
                    (context.applicationContext as? XtraApp)?.xtraModule?.updateRepository?.reset()
                    context.tokenPrefs().edit {
                        remove(C.UPDATE_LAST_CHECKED)
                        remove(C.UPDATE_LAST_ATTEMPTED)
                        remove(C.UPDATE_RATE_LIMITED_UNTIL)
                        remove(C.UPDATE_IGNORED_VERSION)
                    }
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                    (requireActivity() as SettingsActivity).setResult()
                    Toast.makeText(requireContext(), R.string.settings_reset_action, Toast.LENGTH_SHORT).show()
                    requireActivity().recreate()
                }
                .setNegativeButton(R.string.no, null)
                .show()
            true
        }
        findPreference<Preference>("about_version")?.apply {
            summary = getString(R.string.app_version_summary, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            var taps = 0
            setOnPreferenceClickListener {
                taps++
                val remaining = 7 - taps
                if (remaining > 0) {
                    Toast.makeText(requireContext(), getString(R.string.settings_developer_unlocking, remaining), Toast.LENGTH_SHORT).show()
                } else {
                    requireContext().prefs().edit {
                        putBoolean(C.SETTINGS_DEVELOPER_UNLOCKED, true)
                        putBoolean(C.SETTINGS_DEVELOPER_ENABLED, true)
                    }
                    Toast.makeText(requireContext(), R.string.settings_developer_unlocked, Toast.LENGTH_SHORT).show()
                }
                true
            }
        }
        findPreference<Preference>("about_build")?.summary = BuildConfig.BUILD_TYPE
        findPreference<Preference>("about_package")?.summary = BuildConfig.APPLICATION_ID
        findPreference<Preference>("about_github")?.setOnPreferenceClickListener { openExternal("https://github.com/thiscallnet/Xtra"); true }
        findPreference<Preference>("about_licenses")?.setOnPreferenceClickListener { openExternal("https://github.com/thiscallnet/Xtra/blob/master/LICENSE"); true }
        findPreference<Preference>("about_issue")?.setOnPreferenceClickListener { openExternal("https://github.com/thiscallnet/Xtra/issues"); true }
        findPreference<Preference>("developer_options")?.isVisible = requireContext().prefs().getBoolean(C.SETTINGS_DEVELOPER_UNLOCKED, false) &&
            requireContext().prefs().getBoolean(C.SETTINGS_DEVELOPER_ENABLED, false)
        findPreference<SwitchPreferenceCompat>(C.SETTINGS_DEVELOPER_ENABLED)?.setOnPreferenceChangeListener { _, value ->
            val enabled = value as Boolean
            requireContext().prefs().edit { putBoolean(C.SETTINGS_DEVELOPER_ENABLED, enabled) }
            if (!enabled) {
                (requireActivity() as SettingsActivity).setResult()
                requireActivity().recreate()
            }
            true
        }
        findPreference<SwitchPreferenceCompat>(C.SETTINGS_CHAT_ENABLED)?.setOnPreferenceChangeListener { _, value ->
            requireContext().prefs().edit {
                putBoolean(C.SETTINGS_CHAT_ENABLED, value as Boolean)
                putBoolean(C.CHAT_DISABLE, !(value as Boolean))
            }
            true
        }
        findPreference<SwitchPreferenceCompat>(C.SETTINGS_BACKGROUND_PLAYBACK)?.setOnPreferenceChangeListener { _, value ->
            val enabled = value as Boolean
            requireContext().prefs().edit { putBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, enabled) }
            true
        }
        findPreference<SwitchPreferenceCompat>(C.SETTINGS_DEVICE_COLORS)?.setOnPreferenceChangeListener { _, _ ->
            (requireActivity() as SettingsActivity).changed = true
            requireActivity().recreate()
            true
        }
        findPreference<ListPreference>(C.SETTINGS_THEME_MODE)?.setOnPreferenceChangeListener { _, _ ->
            (requireActivity() as SettingsActivity).changed = true
            requireActivity().recreate()
            true
        }
        findPreference<ListPreference>(C.SETTINGS_DENSITY)?.setOnPreferenceChangeListener { _, _ ->
            (requireActivity() as SettingsActivity).changed = true
            requireActivity().recreate()
            true
        }
        findPreference<ListPreference>(C.SETTINGS_PROFILE_PICTURE_STYLE)?.setOnPreferenceChangeListener { _, value ->
            requireContext().prefs().edit { putBoolean(C.UI_ROUND_USER_IMAGE, value == "round") }
            true
        }
        findPreference<ListPreference>(C.CHAT_TIMESTAMP_FORMAT)?.setOnPreferenceChangeListener { _, value ->
            requireContext().rawPrefs().edit {
                putString(C.CHAT_TIMESTAMP_FORMAT, value.toString())
                putInt(C.SETTINGS_TIMESTAMP_FORMAT_VERSION, 1)
            }
            true
        }
        findPreference<SwitchPreferenceCompat>(C.CHAT_TIMESTAMPS)?.setOnPreferenceChangeListener { _, value ->
            requireContext().prefs().edit { putBoolean(C.CHAT_TIMESTAMPS, value as Boolean) }
            true
        }
        findPreference<SwitchPreferenceCompat>(C.SETTINGS_HTTP_PROXY_ENABLED)?.setOnPreferenceChangeListener { _, value ->
            requireContext().prefs().edit { putBoolean(C.SETTINGS_HTTP_PROXY_ENABLED, value as Boolean) }
            true
        }
        if (settingsScreen == SCREEN_CHAT_TRANSLATION) configureTranslationPreferences()
        if (settingsScreen == SCREEN_PLAYER_SEEK) configureSeekPreferences()
        if (settingsScreen == SCREEN_CHAT_APPEARANCE) {
            configureChatAppearancePreferences()
            configureChatSizePreferences()
        }
        if (settingsScreen == SCREEN_CHAT_FEATURES) configureChatHighlightPreferences()
        if (settingsScreen == SCREEN_CHAT_VISIBILITY) configureChatVisibilityPreferences()
        if (settingsScreen == SCREEN_DOWNLOAD_LIVE) configureLiveDownloadPreferences()
        if (settingsScreen == SCREEN_PREDICTION_LIVE_UPDATES || settingsScreen == SCREEN_DROPS_LIVE_UPDATES) {
            configureLiveUpdateSettings()
        }
        findPreference<Preference>("prediction_live_updates_page")?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_global_predictionLiveUpdateSettingsFragment)
            true
        }
        findPreference<Preference>("drops_live_updates_page")?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_global_dropsLiveUpdateSettingsFragment)
            true
        }
    }

    private fun configureLiveUpdateSettings() {
        val supported = Build.VERSION.SDK_INT >= 36
        val promotionAllowed = supported &&
            NotificationManagerCompat.from(requireContext()).canPostPromotedNotifications()
        findPreference<SwitchPreferenceCompat>(
            if (settingsScreen == SCREEN_PREDICTION_LIVE_UPDATES) C.PREDICTION_TRACKING_ENABLED else C.DROPS_TRACKING_ENABLED,
        )?.apply {
            isEnabled = true
            setOnPreferenceChangeListener { _, value ->
                if (value as Boolean && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    openNotificationSettings()
                    return@setOnPreferenceChangeListener false
                }
                if (!value) {
                    if (settingsScreen == SCREEN_PREDICTION_LIVE_UPDATES) {
                        (requireContext().applicationContext as XtraApp).xtraModule.predictionLiveUpdateManager.untrack()
                    } else {
                        (requireContext().applicationContext as XtraApp).xtraModule.dropsLiveUpdateManager.untrack()
                    }
                }
                true
            }
        }
        findPreference<Preference>("live_update_status")?.apply {
            summary = getString(
                when {
                    !supported -> R.string.live_update_status_android
                    !promotionAllowed -> R.string.live_update_status_disabled
                    else -> R.string.live_update_status_available
                },
            )
            setOnPreferenceClickListener {
                openPromotionSettings(::openNotificationSettings)
                true
            }
        }
        findPreference<Preference>(C.LIVE_UPDATE_TRACKING_NOTIFICATION_SETTINGS)?.setOnPreferenceClickListener {
            openChannelNotificationSettings(
                if (settingsScreen == SCREEN_PREDICTION_LIVE_UPDATES) R.string.notification_prediction_tracking_channel_id
                else R.string.notification_drops_tracking_channel_id,
            )
            true
        }
        findPreference<Preference>(C.LIVE_UPDATE_RESULT_NOTIFICATION_SETTINGS)?.setOnPreferenceClickListener {
            openChannelNotificationSettings(
                if (settingsScreen == SCREEN_PREDICTION_LIVE_UPDATES) R.string.notification_prediction_results_channel_id
                else R.string.notification_drops_results_channel_id,
            )
            true
        }
    }

    private fun configureChatVisibilityPreferences() {
        val preview = findPreference<ChatModerationDisplayPreviewPreference>("chat_moderation_display_preview")
        findPreference<ListPreference>(C.CHAT_MODERATION_DISPLAY)?.setOnPreferenceChangeListener { _, _ ->
            Handler(Looper.getMainLooper()).post { preview?.refreshPreview() }
            (requireActivity() as? SettingsActivity)?.setResult()
            true
        }
    }

    private fun configureChatAppearancePreferences() {
        val preview = findPreference<ChatAppearancePreviewPreference>("chat_appearance_preview")
        val backgroundMode = findPreference<ListPreference>(C.PLAYER_BACKGROUND_MODE)
        val backgroundVisibility = findPreference<SeekBarPreference>(C.PLAYER_BACKGROUND_VISIBILITY)
        val backgroundChoose = findPreference<Preference>("player_background_choose")
        val repository = AppearanceRepository(requireContext())

        backgroundMode?.setOnPreferenceChangeListener { _, _ ->
            (requireActivity() as? SettingsActivity)?.setResult()
            Handler(Looper.getMainLooper()).post {
                updateChatAppearancePreferences()
                preview?.refreshPreview()
            }
            true
        }
        backgroundVisibility?.setOnPreferenceChangeListener { _, value ->
            repository.setPlayerBackgroundVisibility(value as Int)
            (requireActivity() as? SettingsActivity)?.setResult()
            Handler(Looper.getMainLooper()).post { preview?.refreshPreview() }
            true
        }
        backgroundChoose?.setOnPreferenceClickListener {
            backgroundPhotoLauncher?.launch(arrayOf("image/*"))
            true
        }
        findPreference<Preference>("player_background_reset")?.setOnPreferenceClickListener {
            repository.resetPlayerBackground()
            backgroundMode?.value = PlayerBackgroundMode.INHERIT_APP.preferenceValue
            backgroundVisibility?.value = DEFAULT_BACKGROUND_VISIBILITY
            updateChatAppearancePreferences()
            preview?.refreshPreview()
            (requireActivity() as? SettingsActivity)?.setResult()
            true
        }

        findPreference<Preference>("chat_restore_text_defaults")?.setOnPreferenceClickListener {
            requireContext().prefs().edit {
                remove(C.PLAYER_MESSAGE_TEXT_COLOR)
                remove(C.PLAYER_METADATA_TEXT_COLOR)
            }
            findPreference<EditTextPreference>(C.PLAYER_MESSAGE_TEXT_COLOR)?.text = null
            findPreference<EditTextPreference>(C.PLAYER_METADATA_TEXT_COLOR)?.text = null
            updateChatAppearancePreferences()
            preview?.refreshPreview()
            (requireActivity() as? SettingsActivity)?.setResult()
            true
        }
        updateChatAppearancePreferences()
    }

    private fun updateChatAppearancePreferences() {
        val preferences = requireContext().prefs()
        val repository = AppearanceRepository(requireContext())
        val playerBackground = repository.playerBackground()
        findPreference<Preference>("player_background_choose")?.apply {
            summary = when {
                playerBackground.uri == null -> getString(R.string.settings_player_background_choose_summary)
                repository.isUriReadable(playerBackground.uri) -> getString(R.string.settings_chat_background_change_summary)
                else -> getString(R.string.settings_chat_background_unavailable)
            }
            isVisible = repository.playerBackgroundMode() == PlayerBackgroundMode.CUSTOM
        }
        findPreference<SeekBarPreference>(C.PLAYER_BACKGROUND_VISIBILITY)?.isVisible =
            repository.playerBackgroundMode() == PlayerBackgroundMode.CUSTOM
        findPreference<EditTextPreference>(C.PLAYER_MESSAGE_TEXT_COLOR)?.summary = preferences
            .getString(C.PLAYER_MESSAGE_TEXT_COLOR, null)
            ?: getString(R.string.settings_chat_text_default_summary)
        findPreference<EditTextPreference>(C.PLAYER_METADATA_TEXT_COLOR)?.summary = preferences
            .getString(C.PLAYER_METADATA_TEXT_COLOR, null)
            ?: getString(R.string.settings_chat_text_default_summary)
    }

    private fun configureChatHighlightPreferences() {
        val changeListener = Preference.OnPreferenceChangeListener { _, _ ->
            (requireActivity() as? SettingsActivity)?.setResult()
            true
        }
        findPreference<SwitchPreferenceCompat>(C.CHAT_HIGHLIGHT_REPLIES)?.onPreferenceChangeListener = changeListener
        findPreference<SwitchPreferenceCompat>(C.CHAT_HIGHLIGHT_MENTIONS)?.onPreferenceChangeListener = changeListener
        findPreference<SwitchPreferenceCompat>(C.CHAT_HIGHLIGHT_MENTIONS_WITHOUT_AT)?.onPreferenceChangeListener = changeListener
        findPreference<EditTextPreference>(C.CHAT_HIGHLIGHT_COLOR)?.apply {
            setOnBindEditTextListener { editText ->
                editText.inputType = InputType.TYPE_CLASS_TEXT
                editText.hint = getString(R.string.chat_highlight_color_hint)
            }
            setOnPreferenceChangeListener { _, value ->
                val valid = parseChatHighlightColor(value.toString()) != null
                if (!valid) {
                    Toast.makeText(requireContext(), R.string.chat_highlight_color_invalid, Toast.LENGTH_SHORT).show()
                } else {
                    (requireActivity() as? SettingsActivity)?.setResult()
                }
                valid
            }
        }
    }

    private fun configureChatSizePreferences() {
        appendCustomListValue(findPreference(C.CHAT_TEXT_SIZE), "sp")
        appendCustomListValue(findPreference(C.CHAT_EMOTE_SIZE), "dp")
        findPreference<EditTextPreference>(C.CHAT_UI_BATCH_INTERVAL_MS)?.apply {
            if (text == null) {
                text = DEFAULT_CHAT_UI_BATCH_INTERVAL_MS.toString()
            }
            fun updateSummary(value: String?) {
                val interval = parseChatUiBatchIntervalMs(value)
                summary = if (interval == 0L) {
                    getString(R.string.settings_chat_batch_interval_off)
                } else {
                    getString(R.string.settings_chat_batch_interval_summary, interval)
                }
            }
            updateSummary(text)
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
                it.selectAll()
            }
            setOnPreferenceChangeListener { _, value ->
                val valid = value.toString().trim().toLongOrNull()?.let { it >= 0L } == true
                if (valid) {
                    updateSummary(value.toString())
                    (requireActivity() as? SettingsActivity)?.setResult()
                }
                valid
            }
        }
        val chatAppearanceChangeListener = Preference.OnPreferenceChangeListener { _, _ ->
            (requireActivity() as? SettingsActivity)?.setResult()
            true
        }
        findPreference<EditTextPreference>(C.CHAT_BADGE_SIZE)?.apply {
            if (parseChatBadgeSize(text) == null) {
                text = chatBadgeSizeOrDefault(text).toString()
            }
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            setOnPreferenceChangeListener { _, value ->
                if (parseChatBadgeSize(value.toString()) == null) {
                    false
                } else {
                    (requireActivity() as? SettingsActivity)?.setResult()
                    true
                }
            }
        }
        findPreference<SwitchPreferenceCompat>(C.WATCH_STREAK_PROTECTION_ENABLED)?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(requireActivity(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                pendingNotificationPermission = NotificationPermissionRequester.WATCH_STREAK
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                false
            } else if (enabled && !LiveNotificationScheduler.canPostWatchStreakNotifications(requireContext())) {
                false
            } else {
                requireContext().prefs().edit { putBoolean(C.WATCH_STREAK_PROTECTION_ENABLED, enabled) }
                if (!enabled) {
                    WatchStreakReminderStateStore(requireContext()).clear()
                    WatchStreakReminderNotifier(requireContext()).cancelWatchStreakNotifications()
                }
                LiveNotificationScheduler.refresh(requireContext())
                updateWatchStreakProtectionSummary()
                true
            }
        }
        findPreference<EditTextPreference>(C.WATCH_STREAK_MINIMUM)?.apply {
            val initial = text?.toIntOrNull()?.takeIf { it >= 1 } ?: 1
            if (text != initial.toString()) text = initial.toString()
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_NUMBER
                it.selectAll()
            }
            setOnPreferenceChangeListener { _, value ->
                val valid = value.toString().trim().toIntOrNull()?.let { it >= 1 } == true
                if (valid) {
                    updateWatchStreakProtectionSummary()
                    (requireActivity() as? SettingsActivity)?.setResult()
                }
                valid
            }
        }
        findPreference<SwitchPreferenceCompat>(C.CHAT_SHOW_BADGES)?.onPreferenceChangeListener = chatAppearanceChangeListener
        findPreference<SeekBarPreference>(C.CHAT_WIDTH_PERCENT)?.apply {
            if (SettingsMigration.synchronizeLandscapeChatWidth(requireContext(), value)) {
                (requireActivity() as? SettingsActivity)?.setResult()
            }
            setOnPreferenceChangeListener { _, newValue ->
                SettingsMigration.synchronizeLandscapeChatWidth(requireContext(), newValue as Int)

                (requireActivity() as? SettingsActivity)?.setResult()

                true
            }
        }
    }

    private fun configureLiveDownloadPreferences() {
        val start = findPreference<ListPreference>(C.DOWNLOAD_STREAM_START_WAIT)
        val end = findPreference<ListPreference>(C.DOWNLOAD_STREAM_END_WAIT)
        appendCustomListValue(start, "minutes")
        appendCustomListValue(end, "minutes")
        updateLiveDownloadSummary(start, "Minutes to wait for a queued live download to start.")
        updateLiveDownloadSummary(end, "Keep waiting briefly after a stream ends so a quick restart can continue the capture.")
        start?.setOnPreferenceChangeListener { preference, newValue ->
            updateLiveDownloadSummary(preference as ListPreference, "Minutes to wait for a queued live download to start.", newValue.toString())
            true
        }
        end?.setOnPreferenceChangeListener { preference, newValue ->
            updateLiveDownloadSummary(preference as ListPreference, "Keep waiting briefly after a stream ends so a quick restart can continue the capture.", newValue.toString())
            true
        }
    }

    private fun updateLiveDownloadSummary(preference: ListPreference?, explanation: String, value: String? = preference?.value) {
        preference ?: return
        val index = preference.findIndexOfValue(value)
        val selected = preference.entries.getOrNull(index)?.toString()
            ?: value?.let { "$it minutes (custom)" }
        preference.summary = listOfNotNull(selected, explanation).joinToString("\n")
    }

    private fun appendCustomListValue(preference: ListPreference?, unit: String) {
        preference ?: return
        val current = preference.value ?: return
        if (current in preference.entryValues) return
        preference.entries = preference.entries.orEmpty().toMutableList().apply {
            add("$current $unit (custom)")
        }.toTypedArray()
        preference.entryValues = preference.entryValues.orEmpty().toMutableList().apply {
            add(current)
        }.toTypedArray()
        preference.summary = "$current $unit (custom)"
    }

    private fun configureSeekPreferences() {
        listOf("playerRewindV2", "playerForwardV2").forEach { key ->
            findPreference<ListPreference>(key)?.apply {
                val seekPreference = this
                if (value !in entryValues) summary = "${value} sec (custom)"
                setOnPreferenceChangeListener { preference, newValue ->
                    if (newValue == "custom") {
                        val input = android.widget.EditText(requireContext()).apply {
                            inputType = InputType.TYPE_CLASS_NUMBER
                            hint = "1–600 seconds"
                        }
                        val dialog = requireActivity().getAlertDialogBuilder()
                            .setTitle(title)
                            .setView(input)
                            .setPositiveButton(android.R.string.ok, null)
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            val seconds = input.text.toString().toIntOrNull()
                            if (seconds == null || seconds !in 1..600) {
                                input.error = "Enter a value from 1 to 600 seconds."
                            } else {
                                seekPreference.value = seconds.toString()
                                seekPreference.summary = "${seconds} sec"
                                dialog.dismiss()
                            }
                        }
                        false
                    } else {
                        preference.summary = "${newValue} sec"
                        true
                    }
                }
            }
        }
    }

    private fun configureTranslationPreferences() {
        if (Build.SUPPORTED_64_BIT_ABIS.firstOrNull() != "arm64-v8a") {
            findPreference<SwitchPreferenceCompat>("chat_translate")?.isVisible = false
            findPreference<Preference>("downloaded_languages")?.isVisible = false
            findPreference<ListPreference>("chat_translate_target")?.isVisible = false
            return
        }
        val languages = TranslateLanguage.getAllLanguages()
        val names = languages.map { Locale.forLanguageTag(it).displayLanguage }.toTypedArray()
        findPreference<Preference>("downloaded_languages")?.setOnPreferenceClickListener {
            val modelManager = RemoteModelManager.getInstance()
            modelManager.getDownloadedModels(TranslateRemoteModel::class.java)
                .addOnSuccessListener { models ->
                    val downloaded = models.map { it.language }
                    val checked = languages.map { downloaded.contains(it) }.toBooleanArray()
                    val selectedItems = downloaded.toMutableList()
                    requireActivity().getAlertDialogBuilder()
                        .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                            languages.getOrNull(which)?.let { language ->
                                if (isChecked) {
                                    if (!selectedItems.contains(language)) selectedItems.add(language)
                                } else {
                                    selectedItems.remove(language)
                                }
                            }
                        }
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            downloaded.filter { !selectedItems.contains(it) }.forEach {
                                modelManager.deleteDownloadedModel(TranslateRemoteModel.Builder(it).build())
                            }
                            selectedItems.filter { !downloaded.contains(it) }.forEach {
                                modelManager.download(TranslateRemoteModel.Builder(it).build(), DownloadConditions.Builder().build())
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            true
        }
        findPreference<ListPreference>("chat_translate_target")?.apply {
            entries = names
            entryValues = languages.toTypedArray()
        }
    }

    private fun openExternal(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    private fun diagnosticInformation(): String = buildString {
        appendLine("Xtra ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Build: ${BuildConfig.BUILD_TYPE}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Playback backend: Media3 ExoPlayer")
        appendLine("Network engine: ${requireContext().prefs().getString(C.NETWORK_LIBRARY, "Automatic")}")
        appendLine("PiP: ${requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)}")
        appendLine("Notifications: ${Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED}")
        appendLine("ML Kit translation: ${Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a"}")
        append(liveNotificationDiagnostics(requireContext()))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.settingsOperationResult.collect { result ->
                        when (result) {
                            SettingsViewModel.SettingsOperationResult.BackupCompleted -> Snackbar.make(
                                view,
                                R.string.settings_backup_complete,
                                Snackbar.LENGTH_LONG,
                            ).show()
                            SettingsViewModel.SettingsOperationResult.RestoreStaged -> Unit
                            is SettingsViewModel.SettingsOperationResult.Failed -> Snackbar.make(
                                view,
                                getString(R.string.settings_operation_failed, result.reason),
                                Snackbar.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
                viewModel.liveNotificationResult.collectLatest { result ->
                    findPreference<SwitchPreferenceCompat>("live_notifications_enabled")?.isChecked = result.enabled
                    updateLiveNotificationsSummary()
                    result.failure?.let(::showLiveNotificationFailure)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (settingsScreen == SCREEN_TABS) {
            (requireActivity() as? SettingsActivity)?.consumeSettingsHighlightPreference()?.let(::highlightPreference)
        }
        if (settingsScreen == SCREEN_PROXY) {
            requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        val preference = findPreference<SwitchPreferenceCompat>("live_notifications_enabled")
        if (preference?.isChecked == true && !LiveNotificationScheduler.canPostNotifications(requireContext())) {
            preference.isChecked = false
            toggleLiveNotifications(false)
        }
        val watchStreakPreference = findPreference<SwitchPreferenceCompat>(C.WATCH_STREAK_PROTECTION_ENABLED)
        if (watchStreakPreference?.isChecked == true && !LiveNotificationScheduler.canPostWatchStreakNotifications(requireContext())) {
            watchStreakPreference.isChecked = false
            requireContext().prefs().edit { putBoolean(C.WATCH_STREAK_PROTECTION_ENABLED, false) }
            WatchStreakReminderStateStore(requireContext()).clear()
            LiveNotificationScheduler.refresh(requireContext())
        }
        updateLiveNotificationsSummary()
    }

    private fun highlightPreference(key: String) {
        val preference = findPreference<Preference>(key) ?: return
        val recyclerView = listView as? RecyclerView ?: return
        val adapter = recyclerView.adapter as? PreferenceGroupAdapter ?: return
        val position = adapter.getPreferenceAdapterPosition(preference)
        if (position == RecyclerView.NO_POSITION) return
        recyclerView.scrollToPosition(position)
        recyclerView.postDelayed({
            val itemView = recyclerView.findViewHolderForAdapterPosition(position)?.itemView ?: return@postDelayed
            if (!ValueAnimator.areAnimatorsEnabled()) return@postDelayed
            itemView.animate().cancel()
            itemView.scaleX = 1f
            itemView.scaleY = 1f
            itemView.isPressed = true
            itemView.postDelayed({ itemView.isPressed = false }, 180L)
            itemView.animate()
                .scaleX(1.025f)
                .scaleY(1.025f)
                .setDuration(120L)
                .withEndAction {
                    itemView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(220L)
                        .start()
                }
                .start()
        }, 100L)
    }

    override fun onPause() {
        if (settingsScreen == SCREEN_PROXY) {
            requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        super.onPause()
    }

    private companion object {
        const val ARG_SETTINGS_SCREEN = "settings_screen"
        const val SCREEN_APP = "app"
        const val SCREEN_LIVE_NOTIFICATIONS = "live_notifications"
        const val SCREEN_ACCOUNT = "account"
        const val SCREEN_LANGUAGE = "language"
        const val SCREEN_BACKUP = "backup"
        const val SCREEN_ABOUT = "about"
        const val SCREEN_DISPLAY_COMPATIBILITY = "display_compatibility"
        const val SCREEN_BROWSING_INFORMATION = "browsing_information"
        const val SCREEN_BROWSING_SEARCH = "browsing_search"
        const val SCREEN_TABS = "tabs"
        const val SCREEN_CLIP = "clip"
        const val SCREEN_PLAYER_SEEK = "player_seek"
        const val SCREEN_PLAYER_GESTURES = "player_gestures"
        const val SCREEN_PLAYER_SWIPE_CONTROLS = "player_swipe_controls"
        const val SCREEN_PLAYER_INFORMATION = "player_information"
        const val SCREEN_CHAT_APPEARANCE = "chat_appearance"
        const val SCREEN_CHAT_USERNAME = "chat_username"
        const val SCREEN_CHAT_EMOTES = "chat_emotes"
        const val SCREEN_CHAT_7TV = "chat_7tv"
        const val SCREEN_CHAT_FEATURES = "chat_features"
        const val SCREEN_CHAT_HISTORY = "chat_history"
        const val SCREEN_CHAT_TRANSLATION = "chat_translation"
        const val SCREEN_CHAT_VISIBILITY = "chat_visibility"
        const val SCREEN_DOWNLOAD_LIVE = "download_live"
        const val SCREEN_PROXY = "proxy"
        const val SCREEN_DEVELOPER = "developer"
        const val SCREEN_SYSTEM_MEDIA = "system_media"
        const val SCREEN_PREDICTION_LIVE_UPDATES = "prediction_live_updates"
        const val SCREEN_DROPS_LIVE_UPDATES = "drops_live_updates"
        const val KEY_PENDING_NOTIFICATION_PERMISSION = "pending_notification_permission"
    }
}
