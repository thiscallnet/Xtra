package com.github.andreyasadchy.xtra.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ext.SdkExtensions
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.NotificationEvent
import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.model.chat.PredictionBetState
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.model.ui.TwitchDrop
import com.github.andreyasadchy.xtra.repository.streamfeed.StreamFeedSpecs
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationNotifier
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.updater.UpdateVersionDisplay
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class DebugSettingsFragment : MaterialPreferenceFragment() {
    private var notificationFixtureJob: Job? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.debug_preferences, rootKey)
        if (BuildConfig.DEBUG) {
            preferenceScreen.addPreference(Preference(requireContext()).apply {
                key = "debug_corrupt_gecko_gql_identity"
                title = getString(R.string.settings_debug_corrupt_gecko_identity)
                summary = getString(R.string.settings_debug_corrupt_gecko_identity_summary)
                setOnPreferenceClickListener {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val manager = (requireContext().applicationContext as XtraApp)
                            .xtraModule.twitchWebSessionManager
                        val acquired = manager.refreshGeckoGqlIdentity()
                        val corrupted = acquired && manager.debugCorruptGeckoGqlIdentity()
                        Toast.makeText(
                            requireContext(),
                            if (corrupted) R.string.settings_debug_gecko_identity_corrupted
                            else R.string.settings_debug_gecko_identity_unavailable,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    true
                }
            })
            preferenceScreen.addPreference(Preference(requireContext()).apply {
                key = "debug_gql"
                title = getString(R.string.settings_debug_gql)
                setOnPreferenceClickListener {
                    Snackbar.make(
                        requireView(),
                        R.string.settings_debug_only,
                        Snackbar.LENGTH_SHORT,
                    ).show()
                    true
                }
            })
            addNotificationFixturePreferences()
        }
        findPreference<ListPreference>(C.NETWORK_LIBRARY)?.apply {
            val supported = buildList {
                add(C.AUTOMATIC)
                if (Build.VERSION.SDK_INT >= 34 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7
                ) {
                    add(C.HTTP_ENGINE)
                }
                add(C.OKHTTP)
            }
            entries = supported.toTypedArray()
            entryValues = supported.toTypedArray()
            if (value !in supported) value = C.AUTOMATIC
        }
        findPreference<Preference>("developer_options")?.apply {
            isVisible = requireContext().prefs().getBoolean(C.SETTINGS_DEVELOPER_UNLOCKED, false) &&
                requireContext().prefs().getBoolean(C.SETTINGS_DEVELOPER_ENABLED, false)
            setOnPreferenceClickListener {
                findNavController().navigate(R.id.developerSettingsFragment)
                true
            }
        }
        findPreference<Preference>("advanced_proxy")?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.proxySettingsFragment)
            true
        }
        findPreference<Preference>("diagnostics_page")?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.action_global_diagnosticsSettingsFragment)
            true
        }
    }

    private fun addNotificationFixturePreferences() {
        preferenceScreen.addPreference(PreferenceCategory(requireContext()).apply {
            key = "debug_notification_fixtures"
            title = getString(R.string.settings_debug_notification_fixtures)
            summary = getString(R.string.settings_debug_notification_fixtures_summary)
        })
        addNotificationFixturePreference(
            key = "debug_trigger_live_notification",
            titleRes = R.string.settings_debug_trigger_live_notification,
            summaryRes = R.string.settings_debug_trigger_live_notification_summary,
        ) { module, stream -> triggerLiveNotification(module, stream) }
        addNotificationFixturePreference(
            key = "debug_trigger_prediction",
            titleRes = R.string.settings_debug_trigger_prediction,
            summaryRes = R.string.settings_debug_trigger_prediction_summary,
        ) { module, stream -> triggerPrediction(module, stream) }
        addNotificationFixturePreference(
            key = "debug_trigger_drops",
            titleRes = R.string.settings_debug_trigger_drops,
            summaryRes = R.string.settings_debug_trigger_drops_summary,
        ) { module, stream -> triggerDrops(module, stream) }
    }

    private fun addNotificationFixturePreference(
        key: String,
        titleRes: Int,
        summaryRes: Int,
        action: suspend (com.github.andreyasadchy.xtra.XtraModule, Stream) -> FixtureActionResult,
    ) {
        val preference = Preference(requireContext()).apply {
            this.key = key
            title = getString(titleRes)
            summary = getString(summaryRes)
        }
        preference.setOnPreferenceClickListener {
            if (notificationFixtureJob?.isActive == true) return@setOnPreferenceClickListener true
            notificationFixtureJob = viewLifecycleOwner.lifecycleScope.launch {
                preference.isEnabled = false
                val message = runCatching {
                    withContext(Dispatchers.IO) {
                        runNotificationFixture(action)
                    }
                }.getOrElse { error ->
                    getString(
                        R.string.settings_debug_notification_failed_detail,
                        error.message ?: error.javaClass.simpleName,
                    )
                }
                preference.isEnabled = true
                Snackbar.make(requireView(), message, Snackbar.LENGTH_LONG).show()
            }
            true
        }
        preferenceScreen.addPreference(preference)
    }

    private suspend fun runNotificationFixture(
        action: suspend (com.github.andreyasadchy.xtra.XtraModule, Stream) -> FixtureActionResult,
    ): String {
        val context = requireContext().applicationContext
        if (context.tokenPrefs().getString(C.USER_ID, null).isNullOrBlank()) {
            return getString(R.string.settings_debug_notification_sign_in)
        }
        val module = (context as XtraApp).xtraModule
        val stream = randomFollowedLiveStream(context, module)
            ?: return getString(R.string.settings_debug_notification_no_live)
        val result = action(module, stream)
        val displayName = stream.channelName?.takeIf(String::isNotBlank)
            ?: stream.channelLogin.orEmpty()
        return result.message ?: getString(
            if (result.triggered) R.string.settings_debug_notification_triggered
            else R.string.settings_debug_notification_failed,
            result.label,
            displayName,
        )
    }

    private suspend fun randomFollowedLiveStream(
        context: android.content.Context,
        module: com.github.andreyasadchy.xtra.XtraModule,
    ): Stream? {
        val userId = context.tokenPrefs().getString(C.USER_ID, null)
        val page = StreamFeedSpecs.followed(
            context = context,
            userId = userId,
            localChannelFollowsRepository = module.localChannelFollowsRepository,
            graphQLRepository = module.graphQLRepository,
            helixRepository = module.helixRepository,
        ).loader.load(null)
        return page.items.filter {
            !it.channelId.isNullOrBlank() && !it.channelLogin.isNullOrBlank()
        }.randomOrNull()
    }

    private suspend fun triggerLiveNotification(
        module: com.github.andreyasadchy.xtra.XtraModule,
        stream: Stream,
    ): FixtureActionResult {
        val notifier = LiveNotificationNotifier(requireContext().applicationContext)
        val label = getString(R.string.settings_debug_live_notification_label)
        if (!notifier.canPostNotifications()) {
            return FixtureActionResult(
                label = label,
                triggered = false,
                message = getString(R.string.settings_debug_notifications_blocked),
            )
        }
        val event = NotificationEvent.fromStream(stream, System.currentTimeMillis())
            ?: return FixtureActionResult(
                label = label,
                triggered = false,
                message = getString(R.string.settings_debug_notification_invalid_stream),
            )
        module.database.notificationEvents().insert(
            event.copy(eventId = "debug-live-${UUID.randomUUID()}"),
        )
        val delivered = notifier.deliverPending(module.notificationsRepository) > 0
        return FixtureActionResult(label, delivered)
    }

    private fun triggerPrediction(
        module: com.github.andreyasadchy.xtra.XtraModule,
        stream: Stream,
    ): FixtureActionResult {
        val context = requireContext().applicationContext
        val label = getString(R.string.settings_debug_prediction_label)
        if (!context.prefs().getBoolean(C.PREDICTION_TRACKING_ENABLED, true)) {
            return FixtureActionResult(label, false, getString(R.string.settings_debug_notification_disabled, label))
        }
        val now = System.currentTimeMillis()
        val predictionId = "debug-prediction-${UUID.randomUUID()}"
        module.predictionLiveUpdateManager.track(
            prediction = Prediction(
                id = predictionId,
                createdAt = now,
                startedAt = now,
                locksAt = now + 10 * 60_000L,
                predictionWindowSeconds = 600,
                status = "ACTIVE",
                title = "Test prediction for ${stream.channelName ?: stream.channelLogin}",
                outcomes = listOf(
                    Prediction.PredictionOutcome("debug-yes", "Yes", 6_500, 65),
                    Prediction.PredictionOutcome("debug-no", "No", 3_500, 35),
                ),
                winningOutcomeId = null,
                broadcastId = stream.id,
            ),
            channelId = stream.channelId.orEmpty(),
            channelLogin = stream.channelLogin.orEmpty(),
            channelName = stream.channelName ?: stream.channelLogin.orEmpty(),
            betState = PredictionBetState(
                predictionId = predictionId,
                outcomeId = "debug-yes",
                amount = 500,
            ),
            streamId = stream.id,
        )
        return FixtureActionResult(label, true)
    }

    private fun triggerDrops(
        module: com.github.andreyasadchy.xtra.XtraModule,
        stream: Stream,
    ): FixtureActionResult {
        val context = requireContext().applicationContext
        val label = getString(R.string.settings_debug_drops_label)
        if (!context.prefs().getBoolean(C.DROPS_TRACKING_ENABLED, true)) {
            return FixtureActionResult(label, false, getString(R.string.settings_debug_notification_disabled, label))
        }
        val dropId = "debug-drop-${UUID.randomUUID()}"
        module.dropsLiveUpdateManager.track(
            TwitchDrop(
                id = dropId,
                campaignId = "debug-campaign-${stream.gameId ?: stream.channelId}",
                campaignName = "Test campaign",
                gameName = stream.gameName ?: "Live stream",
                name = "Test drop",
                rewardName = "Test drop for ${stream.channelName ?: stream.channelLogin}",
                imageUrl = stream.channelImageURL ?: stream.thumbnailURL,
                dropInstanceId = "debug-instance-$dropId",
                currentMinutesWatched = 18,
                requiredMinutesWatched = 60,
                isClaimed = false,
            ),
        )
        return FixtureActionResult(label, true)
    }

    private data class FixtureActionResult(
        val label: String,
        val triggered: Boolean,
        val message: String? = null,
    )

    private fun diagnosticInformation(): String = buildString {
        appendLine("Xtra ${UpdateVersionDisplay.installed(BuildConfig.VERSION_NAME, BuildConfig.CI_BUILD_NUMBER.toLong())} (version code ${BuildConfig.VERSION_CODE})")
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

}
