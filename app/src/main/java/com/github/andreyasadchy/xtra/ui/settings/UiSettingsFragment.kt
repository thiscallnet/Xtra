package com.github.andreyasadchy.xtra.ui.settings

import android.os.Bundle
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem
import com.github.andreyasadchy.xtra.ui.following.FollowingTabs
import com.github.andreyasadchy.xtra.ui.settings.SettingsViewModel.Companion.SettingsViewModelFactory
import com.github.andreyasadchy.xtra.util.isTelevision
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs

class UiSettingsFragment : MaterialPreferenceFragment() {
    private val viewModel: SettingsViewModel by activityViewModels { SettingsViewModelFactory }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.ui_preferences, rootKey)
        val changeListener = Preference.OnPreferenceChangeListener { _, _ ->
            (requireActivity() as? SettingsActivity)?.setResult()
            true
        }
        findPreference<SwitchPreferenceCompat>(C.UI_ROUND_USER_IMAGE)?.onPreferenceChangeListener = changeListener
        findPreference<SwitchPreferenceCompat>(C.UI_TRUNCATE_VIEW_COUNT)?.onPreferenceChangeListener = changeListener
        findPreference<SwitchPreferenceCompat>(C.UI_UPTIME)?.onPreferenceChangeListener = changeListener
        findPreference<SwitchPreferenceCompat>(C.UI_TAGS)?.onPreferenceChangeListener = changeListener
        findPreference<SwitchPreferenceCompat>(C.UI_BROADCASTERS_COUNT)?.onPreferenceChangeListener = changeListener
        findPreference<SwitchPreferenceCompat>(C.UI_BOOKMARK_TIME_LEFT)?.onPreferenceChangeListener = changeListener
        findPreference<SwitchPreferenceCompat>(C.UI_SCROLL_TOP)?.onPreferenceChangeListener = changeListener
        findPreference<ListPreference>(C.PORTRAIT_COLUMN_COUNT)?.onPreferenceChangeListener = changeListener
        findPreference<ListPreference>(C.LANDSCAPE_COLUMN_COUNT)?.onPreferenceChangeListener = changeListener
        findPreference<ListPreference>(C.COMPACT_STREAMS)?.onPreferenceChangeListener = changeListener
        findPreference<ListPreference>(C.UI_STREAM_SORT)?.onPreferenceChangeListener = changeListener
        findPreference<Preference>("browsing_displayed_information")?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.browsingInformationFragment)
            true
        }
        findPreference<Preference>("browsing_customize_tabs")?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.browsingTabsFragment)
            true
        }
        findPreference<Preference>("browsing_search_history")?.setOnPreferenceClickListener {
            findNavController().navigate(R.id.browsingSearchFragment)
            true
        }
        findPreference<Preference>("ui_navigation_tab_list_dialog")?.setOnPreferenceClickListener { preference ->
            val tabList = resolveNavigationTabList(
                requireContext().prefs().getString(C.UI_NAVIGATION_TAB_LIST, null),
                requireActivity().isTelevision(),
            )
            val tabs = tabList.map {
                val split = it.split(':')
                SettingsDragListItem(
                    key = split[0],
                    text = when (split[0]) {
                        "0" -> getString(R.string.browse)
                        "4" -> getString(R.string.discover)
                        "1" -> getString(R.string.following_overview)
                        "2" -> getString(R.string.following)
                        "3" -> getString(R.string.saved)
                        "5" -> getString(R.string.statistics)
                        "6" -> getString(R.string.drops)
                        else -> getString(R.string.following_overview)
                    },
                    default = split[1] != "0",
                    enabled = split[2] != "0",
                )
            }
            (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_NAVIGATION_TAB_LIST, preference.title)
            true
        }
        findPreference<Preference>("ui_following_tabs_dialog")?.setOnPreferenceClickListener { preference ->
            val tabList = FollowingTabs.resolve(requireContext().prefs().getString(C.UI_FOLLOWING_TABS, null))
            val tabs = tabList.map {
                val split = it.split(':')
                SettingsDragListItem(
                    key = split[0],
                    text = getString(FollowingTabs.titleRes(split[0])),
                    default = split[1] != "0",
                    enabled = split[2] != "0",
                )
            }
            (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_FOLLOWING_TABS, preference.title)
            true
        }
        findPreference<Preference>("ui_saved_tabs_dialog")?.setOnPreferenceClickListener { preference ->
            val tabList = requireContext().prefs().getString(C.UI_SAVED_TABS, null).let { tabPref ->
                val defaultTabs = C.DEFAULT_SAVED_TABS.split(',')
                if (tabPref != null) {
                    val list = tabPref.split(',').filter { item ->
                        defaultTabs.find { it.first() == item.first() } != null
                    }.toMutableList()
                    defaultTabs.forEachIndexed { index, item ->
                        if (list.find { it.first() == item.first() } == null) {
                            list.add(index, item)
                        }
                    }
                    list
                } else defaultTabs
            }
            val tabs = tabList.map {
                val split = it.split(':')
                SettingsDragListItem(
                    key = split[0],
                    text = when (split[0]) {
                        "0" -> getString(R.string.bookmarks)
                        "1" -> getString(R.string.downloads)
                        "2" -> getString(R.string.filters)
                        "3" -> getString(R.string.clips)
                        else -> getString(R.string.downloads)
                    },
                    default = split[1] != "0",
                    enabled = split[2] != "0",
                )
            }
            (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_SAVED_TABS, preference.title)
            true
        }
        findPreference<Preference>("ui_channel_tabs_dialog")?.setOnPreferenceClickListener { preference ->
            val tabList = requireContext().prefs().getString(C.UI_CHANNEL_TABS, null).let { tabPref ->
                val defaultTabs = C.DEFAULT_CHANNEL_TABS.split(',')
                if (tabPref != null) {
                    val list = tabPref.split(',').filter { item ->
                        defaultTabs.find { it.first() == item.first() } != null
                    }.toMutableList()
                    defaultTabs.forEachIndexed { index, item ->
                        if (list.find { it.first() == item.first() } == null) {
                            list.add(index, item)
                        }
                    }
                    list
                } else defaultTabs
            }
            val tabs = tabList.map {
                val split = it.split(':')
                SettingsDragListItem(
                    key = split[0],
                    text = when (split[0]) {
                        "0" -> getString(R.string.suggestions)
                        "1" -> getString(R.string.videos)
                        "2" -> getString(R.string.clips)
                        "3" -> getString(R.string.chat)
                        "4" -> getString(R.string.about)
                        else -> getString(R.string.videos)
                    },
                    default = split[1] != "0",
                    enabled = split[2] != "0",
                )
            }
            (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_CHANNEL_TABS, preference.title)
            true
        }
        findPreference<Preference>("ui_game_tabs_dialog")?.setOnPreferenceClickListener { preference ->
            val tabList = requireContext().prefs().getString(C.UI_GAME_TABS, null).let { tabPref ->
                val defaultTabs = C.DEFAULT_GAME_TABS.split(',')
                if (tabPref != null) {
                    val list = tabPref.split(',').filter { item ->
                        defaultTabs.find { it.first() == item.first() } != null
                    }.toMutableList()
                    defaultTabs.forEachIndexed { index, item ->
                        if (list.find { it.first() == item.first() } == null) {
                            list.add(index, item)
                        }
                    }
                    list
                } else defaultTabs
            }
            val tabs = tabList.map {
                val split = it.split(':')
                SettingsDragListItem(
                    key = split[0],
                    text = when (split[0]) {
                        "0" -> getString(R.string.videos)
                        "1" -> getString(R.string.live)
                        "2" -> getString(R.string.clips)
                        else -> getString(R.string.live)
                    },
                    default = split[1] != "0",
                    enabled = split[2] != "0",
                )
            }
            (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_GAME_TABS, preference.title)
            true
        }
        findPreference<Preference>("ui_search_tabs_dialog")?.setOnPreferenceClickListener { preference ->
            val tabList = requireContext().prefs().getString(C.UI_SEARCH_TABS, null).let { tabPref ->
                val defaultTabs = C.DEFAULT_SEARCH_TABS.split(',')
                if (tabPref != null) {
                    val list = tabPref.split(',').filter { item ->
                        defaultTabs.find { it.first() == item.first() } != null
                    }.toMutableList()
                    defaultTabs.forEachIndexed { index, item ->
                        if (list.find { it.first() == item.first() } == null) {
                            list.add(index, item)
                        }
                    }
                    list
                } else defaultTabs
            }
            val tabs = tabList.map {
                val split = it.split(':')
                SettingsDragListItem(
                    key = split[0],
                    text = when (split[0]) {
                        "0" -> getString(R.string.videos)
                        "1" -> getString(R.string.streams)
                        "2" -> getString(R.string.channels)
                        "3" -> getString(R.string.games)
                        else -> getString(R.string.channels)
                    },
                    default = split[1] != "0",
                    enabled = split[2] != "0",
                )
            }
            (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_SEARCH_TABS, preference.title)
            true
        }
        findPreference<Preference>("delete_recent_searches")?.setOnPreferenceClickListener {
            requireActivity().getAlertDialogBuilder()
                .setMessage(getString(R.string.delete_recent_searches_message))
                .setPositiveButton(getString(R.string.yes)) { _, _ ->
                    viewModel.deleteRecentSearches()
                }
                .setNegativeButton(getString(R.string.no), null)
                .show()
            true
        }
    }

}
