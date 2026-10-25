package com.github.andreyasadchy.xtra.ui.settings

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.forEach
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.SettingsNavGraphDirections
import com.github.andreyasadchy.xtra.model.ui.SettingsSearchItem
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.PocketTouchGuard
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.color.MaterialColors

class SettingsSearchFragment : Fragment() {
    private var preferenceMetadata: List<SettingsSearchItem>? = null
    private var preferences: List<SettingsSearchItem>? = null
    private val switchDefaults = mutableMapOf<String, Boolean>()
    private val sliderDefaults = mutableMapOf<String, Int>()
    private var indexedDeveloperVisible: Boolean? = null
    private var adapter: SettingsSearchAdapter? = null
    private var savedQuery: String? = null
    private var searchList: RecyclerView? = null
    private var emptyState: TextView? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val list = RecyclerView(context).apply {
            clipToPadding = false
            layoutManager = LinearLayoutManager(context)
        }
        val empty = TextView(context).apply {
            gravity = android.view.Gravity.CENTER
            val horizontalPadding = (32 * resources.displayMetrics.density).toInt()
            val verticalPadding = (16 * resources.displayMetrics.density).toInt()
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            textSize = 16f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        searchList = list
        emptyState = empty
        return FrameLayout(context).apply {
            setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    @SuppressLint("RestrictedApi")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
        (requireActivity() as? SettingsActivity)?.showSearchView(true)
        adapter = SettingsSearchAdapter(this).also {
            it.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                    it.unregisterAdapterDataObserver(this)
                    it.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                            try {
                                if (positionStart == 0) {
                                    searchList?.scrollToPosition(0)
                                }
                            } catch (e: Exception) {

                            }
                        }
                    })
                }
            })
        }
        searchList?.adapter = adapter
        refreshPreferences()
        search(savedQuery.orEmpty())
        requireActivity().findViewById<SearchView>(R.id.searchView)?.let {
            savedQuery?.let { query -> it.setQuery(query, true) }
            it.requestFocus()
            WindowCompat.getInsetsController(requireActivity().window, it).show(WindowInsetsCompat.Type.ime())
        }
    }

    @SuppressLint("RestrictedApi")
    private fun refreshPreferences() {
        val prefs = requireContext().prefs()
        val developerVisible = prefs.getBoolean(C.SETTINGS_DEVELOPER_UNLOCKED, false) &&
            prefs.getBoolean(C.SETTINGS_DEVELOPER_ENABLED, false)
        if (preferenceMetadata == null || indexedDeveloperVisible != developerVisible) {
            switchDefaults.clear()
            sliderDefaults.clear()
            val list = mutableListOf<SettingsSearchItem>()
            val preferenceManager = PreferenceManager(requireContext())
            fun breadcrumb(vararg labels: String): String = labels.joinToString(" / ")
            listOf(
                Triple(R.xml.live_notification_preferences, SettingsNavGraphDirections.actionGlobalLiveNotificationSettingsFragment(), getString(R.string.settings_general_notifications)),
                Triple(R.xml.prediction_live_update_preferences, SettingsNavDirections(R.id.predictionLiveUpdateSettingsFragment), breadcrumb(getString(R.string.settings_general_notifications), getString(R.string.settings_live_activities))),
                Triple(R.xml.drops_live_update_preferences, SettingsNavDirections(R.id.dropsLiveUpdateSettingsFragment), breadcrumb(getString(R.string.settings_general_notifications), getString(R.string.settings_live_activities))),
                Triple(R.xml.playback_preferences, SettingsNavGraphDirections.actionGlobalPlayerSettingsFragment(), getString(R.string.settings_section_playback)),
                Triple(R.xml.live_caption_preferences, SettingsNavDirections(R.id.liveCaptionsFragment), breadcrumb(getString(R.string.settings_section_playback), getString(R.string.live_caption_settings))),
                Triple(R.xml.system_media_notification_preferences, SettingsNavDirections(R.id.systemMediaNotificationSettingsFragment), breadcrumb(getString(R.string.settings_section_playback), getString(R.string.settings_background_playback))),
                Triple(R.xml.account_preferences, SettingsNavDirections(R.id.accountSettingsFragment), getString(R.string.settings_home_account_network)),
                Triple(R.xml.update_search_preferences, SettingsNavGraphDirections.actionGlobalUpdateSettingsFragment(), breadcrumb(getString(R.string.settings_section_app), getString(R.string.settings_general_updates))),
                Triple(R.xml.language_preferences, SettingsNavDirections(R.id.languageSettingsFragment), breadcrumb(getString(R.string.settings_section_app), getString(R.string.settings_language))),
                Triple(R.xml.cache_preferences, SettingsNavDirections(R.id.cacheSettingsFragment), breadcrumb(getString(R.string.settings_section_app), getString(R.string.settings_cached_images))),
                Triple(R.xml.backup_preferences, SettingsNavDirections(R.id.backupSettingsFragment), breadcrumb(getString(R.string.settings_section_app), getString(R.string.settings_backup_restore))),
                Triple(R.xml.about_preferences, SettingsNavDirections(R.id.aboutSettingsFragment), breadcrumb(getString(R.string.settings_section_app), getString(R.string.settings_help_about))),
                Triple(R.xml.theme_preferences, SettingsNavGraphDirections.actionGlobalThemeSettingsFragment(), getString(R.string.settings_section_appearance)),
                Triple(R.xml.display_compatibility_preferences, SettingsNavDirections(R.id.appearanceDisplayCompatibilityFragment), breadcrumb(getString(R.string.settings_section_appearance), getString(R.string.settings_display_compatibility))),
                Triple(R.xml.ui_preferences, SettingsNavGraphDirections.actionGlobalUiSettingsFragment(), getString(R.string.settings_home_browsing)),
                Triple(R.xml.browsing_information_preferences, SettingsNavDirections(R.id.browsingInformationFragment), breadcrumb(getString(R.string.settings_home_browsing), getString(R.string.settings_browsing_streams_videos))),
                Triple(R.xml.browsing_search_preferences, SettingsNavDirections(R.id.browsingSearchFragment), breadcrumb(getString(R.string.settings_home_browsing), getString(R.string.settings_search_history))),
                Triple(R.xml.tabs_preferences, SettingsNavDirections(R.id.browsingTabsFragment), breadcrumb(getString(R.string.settings_home_browsing), getString(R.string.settings_customize_tabs))),
                Triple(R.xml.player_controls_preferences, SettingsNavGraphDirections.actionGlobalPlayerButtonSettingsFragment(), getString(R.string.settings_home_controls)),
                Triple(R.xml.clip_preferences, SettingsNavDirections(R.id.clipSettingsFragment), breadcrumb(getString(R.string.settings_home_controls), getString(R.string.settings_clip_capture))),
                Triple(R.xml.player_seek_preferences, SettingsNavDirections(R.id.playerSeekFragment), breadcrumb(getString(R.string.settings_home_controls), getString(R.string.settings_seek_controls))),
                Triple(R.xml.player_gestures_preferences, SettingsNavDirections(R.id.playerGesturesFragment), breadcrumb(getString(R.string.settings_home_controls), getString(R.string.settings_gestures))),
                Triple(R.xml.player_swipe_controls_preferences, SettingsNavDirections(R.id.playerSwipeControlsFragment), breadcrumb(getString(R.string.settings_home_controls), getString(R.string.settings_gestures), getString(R.string.settings_player_swipe_controls))),
                Triple(R.xml.player_information_preferences, SettingsNavDirections(R.id.playerInformationFragment), breadcrumb(getString(R.string.settings_home_controls), getString(R.string.settings_player_information))),
                Triple(R.xml.chat_preferences, SettingsNavGraphDirections.actionGlobalChatSettingsFragment(), getString(R.string.settings_section_chat)),
                Triple(R.xml.chat_appearance_preferences, SettingsNavDirections(R.id.chatAppearanceFragment), breadcrumb(getString(R.string.settings_section_chat), getString(R.string.settings_chat_appearance_layout))),
                Triple(R.xml.chat_emotes_preferences, SettingsNavDirections(R.id.chatEmotesFragment), breadcrumb(getString(R.string.settings_section_chat), getString(R.string.settings_chat_emotes_badges))),
                Triple(R.xml.chat_features_preferences, SettingsNavDirections(R.id.chatFeaturesFragment), breadcrumb(getString(R.string.settings_section_chat), getString(R.string.settings_chat_features))),
                Triple(R.xml.chat_translation_preferences, SettingsNavDirections(R.id.chatTranslationFragment), breadcrumb(getString(R.string.settings_section_chat), getString(R.string.settings_chat_translation_page))),
                Triple(R.xml.chat_visibility_preferences, SettingsNavDirections(R.id.chatVisibilityFragment), breadcrumb(getString(R.string.settings_section_chat), getString(R.string.settings_chat_messages_interactions))),
                Triple(R.xml.download_preferences, SettingsNavGraphDirections.actionGlobalDownloadSettingsFragment(), getString(R.string.settings_section_downloads)),
                Triple(R.xml.proxy_preferences, SettingsNavDirections(R.id.proxySettingsFragment), breadcrumb(getString(R.string.settings_section_advanced), getString(R.string.settings_network_proxy))),
                Triple(R.xml.debug_preferences, SettingsNavGraphDirections.actionGlobalDebugSettingsFragment(), getString(R.string.settings_section_advanced)),
            ).plus(if (developerVisible) listOf(
                Triple(R.xml.developer_preferences, SettingsNavDirections(R.id.developerSettingsFragment), "Developer options"),
            ) else emptyList()).forEach { item ->
                fun addPreferences(group: PreferenceGroup) {
                    group.forEach {
                        if (!it.isVisible) return@forEach
                        if (it.key == C.POCKET_TOUCH_GUARD && !PocketTouchGuard.isSupported(requireContext())) return@forEach
                        when (it) {
                            is PreferenceGroup -> addPreferences(it)
                            is SwitchPreferenceCompat -> {
                                it.key?.let { key -> switchDefaults[key] = it.isChecked }
                                list.add(SettingsSearchItem(
                                    navDirections = item.second,
                                    location = item.third,
                                    key = it.key,
                                    title = it.title,
                                    summary = it.summary,
                                    value = if (it.isChecked) {
                                        getString(R.string.enabled_setting)
                                    } else {
                                        getString(R.string.disabled_setting)
                                    }
                                ))
                            }
                            is SeekBarPreference -> {
                                it.key?.let { key -> sliderDefaults[key] = it.value }
                                list.add(SettingsSearchItem(
                                    navDirections = item.second,
                                    location = item.third,
                                    key = it.key,
                                    title = it.title,
                                    summary = it.summary,
                                    value = it.value.toString()
                                ))
                            }
                            else -> {
                                list.add(SettingsSearchItem(
                                    navDirections = item.second,
                                    location = item.third,
                                    key = it.key,
                                    title = it.title,
                                    summary = it.summary,
                                ))
                            }
                        }
                    }
                }
                addPreferences(preferenceManager.inflateFromResource(requireContext(), item.first, null))
            }
            preferenceMetadata = list
            indexedDeveloperVisible = developerVisible
        }
        val enabled = getString(R.string.enabled_setting)
        val disabled = getString(R.string.disabled_setting)
        preferences = preferenceMetadata.orEmpty().map { item ->
            val key = item.key
            val value = when {
                key != null && switchDefaults.containsKey(key) ->
                    if (prefs.getBoolean(key, switchDefaults.getValue(key))) enabled else disabled
                key != null && sliderDefaults.containsKey(key) ->
                    prefs.getInt(key, sliderDefaults.getValue(key)).toString()
                else -> item.value
            }
            if (value == item.value) item else item.copy(value = value)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPreferences()
        search(savedQuery.orEmpty())
    }

    fun search(query: String) {
        savedQuery = query
        val term = query.trim()
        val matches = if (term.isNotEmpty()) {
            preferences.orEmpty().filter {
                it.location?.contains(term, true) == true ||
                    it.key?.contains(term, true) == true ||
                    it.title?.contains(term, true) == true ||
                    it.summary?.contains(term, true) == true ||
                    it.value?.contains(term, true) == true
            }
        } else {
            emptyList()
        }
        adapter?.submitList(matches)
        emptyState?.apply {
            text = if (query.isBlank()) {
                getString(R.string.settings_search_empty)
            } else {
                getString(R.string.settings_search_no_results)
            }
            visibility = if (matches.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchList?.adapter = null
        adapter = null
        searchList = null
        emptyState = null
        (requireActivity() as? SettingsActivity)?.showSearchView(false)
    }
}
