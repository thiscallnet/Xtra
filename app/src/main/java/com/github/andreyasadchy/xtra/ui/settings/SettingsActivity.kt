package com.github.andreyasadchy.xtra.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.ext.SdkExtensions
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.text.SpannableString
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.app.ActivityCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import androidx.navigation.NavDirections
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.forEach
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.SettingsNavGraphDirections
import com.github.andreyasadchy.xtra.databinding.ActivitySettingsBinding
import com.github.andreyasadchy.xtra.databinding.DialogUpdateDownloadBinding
import com.github.andreyasadchy.xtra.databinding.FragmentSettingsHomeBinding
import com.github.andreyasadchy.xtra.databinding.ItemSettingsRowBinding
import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem
import com.github.andreyasadchy.xtra.model.ui.SettingsSearchItem
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationScheduler
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationService
import com.github.andreyasadchy.xtra.ui.settings.SettingsViewModel.Companion.SettingsViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.SettingsMigration
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.UpdateInfo
import com.github.andreyasadchy.xtra.util.UpdateState
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.rawPrefs
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.color.MaterialColors
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.chromium.net.CronetProvider
import java.text.DateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

internal fun serializeSpeedOptions(items: List<SettingsDragListItem>): String =
    items.joinToString(",") { "${it.key}:${if (it.enabled) "1" else "0"}" }

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var changed = false
    private var accountActionIsLogout = false
    private var loginResultLauncher: ActivityResultLauncher<Intent>? = null
    var searchItem: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsMigration.migrate(this)
        if (savedInstanceState?.getBoolean(KEY_CHANGED) == true) {
            setResult()
        }
        applyTheme()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        loginResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val wasLogout = accountActionIsLogout
            accountActionIsLogout = false
            if (wasLogout || result.resultCode == RESULT_OK) {
                setResult(RESULT_OK)
                finish()
            }
        }
        val ignoreCutouts = prefs().getBoolean(C.UI_DRAW_BEHIND_CUTOUTS, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
            }
            val cutoutInsets = if (ignoreCutouts) {
                windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            } else {
                insets
            }
            binding.appBar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = cutoutInsets.left
                rightMargin = cutoutInsets.right
            }
            binding.navHostFragment.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = cutoutInsets.left
                rightMargin = cutoutInsets.right
            }
            windowInsets
        }
        val navController = (supportFragmentManager.findFragmentById(R.id.navHostFragment) as NavHostFragment).navController
        val appBarConfiguration = AppBarConfiguration(setOf(), fallbackOnNavigateUpListener = {
            onBackPressedDispatcher.onBackPressed()
            true
        })
        binding.toolbar.setupWithNavController(navController, appBarConfiguration)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.search -> {
                    navController.navigate(SettingsNavGraphDirections.actionGlobalSettingsSearchFragment())
                    true
                }
                else -> false
            }
        }
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            private var job: Job? = null

            override fun onQueryTextSubmit(query: String): Boolean {
                (supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0) as? SettingsSearchFragment)?.search(query)
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                job?.cancel()
                if (newText.isNotEmpty()) {
                    job = lifecycleScope.launch {
                        delay(750.milliseconds)
                        withResumed {
                            (supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0) as? SettingsSearchFragment)?.search(newText)
                        }
                    }
                } else {
                    (supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0) as? SettingsSearchFragment)?.search(newText)
                }
                return false
            }
        })
    }

    fun isAccountConnected(): Boolean {
        return !TwitchApiHelper.getGQLHeaders(this, true)[C.HEADER_TOKEN].isNullOrBlank() ||
                !TwitchApiHelper.getHelixHeaders(this)[C.HEADER_TOKEN].isNullOrBlank()
    }

    fun openAccountAction() {
        accountActionIsLogout = isAccountConnected()
        loginResultLauncher?.launch(Intent(this, LoginActivity::class.java))
    }

    fun showDragListDialog(list: List<SettingsDragListItem>, prefKey: String, title: CharSequence?) {
        val listAdapter = SettingsDragListAdapter()
        val itemTouchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    Collections.swap(list, viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    listAdapter.notifyItemMoved(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

                override fun isLongPressDragEnabled(): Boolean {
                    return false
                }
            }
        )
        listAdapter.itemTouchHelper = itemTouchHelper
        val recyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@SettingsActivity)
            adapter = listAdapter
            val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10F, resources.displayMetrics).toInt()
            setPadding(0, padding, 0, 0)
        }
        listAdapter.setDefault = { item ->
            list.find { it.default }?.let { previous ->
                previous.default = false
                recyclerView.findViewHolderForAdapterPosition(
                    list.indexOf(previous)
                )?.itemView?.findViewById<ImageButton>(R.id.setAsDefault)?.let {
                    it.setImageResource(R.drawable.outline_home_black_24)
                    it.isClickable = true
                }
            }
            item.default = true
        }
        itemTouchHelper.attachToRecyclerView(recyclerView)
        listAdapter.submitList(list)
        getAlertDialogBuilder()
            .setTitle(title)
            .setView(recyclerView)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                prefs().edit {
                    putString(prefKey, listAdapter.currentList.joinToString(",") {
                        "${it.key}:${if (it.default) "1" else "0"}:${if (it.enabled) "1" else "0"}"
                    })
                    setResult()
                }
            }
            .setNegativeButton(getString(android.R.string.cancel), null)
            .show()
    }

    private fun showSearchView(showSearch: Boolean) {
        with(binding) {
            if (showSearch) {
                toolbar.menu.findItem(R.id.search).isVisible = false
                searchView.visibility = View.VISIBLE
            } else {
                toolbar.menu.findItem(R.id.search).isVisible = true
                searchView.setQuery(null, false)
                searchView.visibility = View.GONE
            }
        }
    }

    fun showTabDialog(prefKey: String, title: CharSequence?) {
        val defaults = when (prefKey) {
            C.UI_NAVIGATION_TAB_LIST -> C.DEFAULT_NAVIGATION_TAB_LIST
            C.UI_FOLLOWING_TABS -> C.DEFAULT_FOLLOWING_TABS
            C.UI_SAVED_TABS -> C.DEFAULT_SAVED_TABS
            C.UI_CHANNEL_TABS -> C.DEFAULT_CHANNEL_TABS
            C.UI_GAME_TABS -> C.DEFAULT_GAME_TABS
            else -> C.DEFAULT_SEARCH_TABS
        }
        val labels = when (prefKey) {
            C.UI_NAVIGATION_TAB_LIST -> mapOf("0" to getString(R.string.games), "1" to getString(R.string.popular), "2" to getString(R.string.following), "3" to getString(R.string.saved))
            C.UI_FOLLOWING_TABS -> mapOf("0" to getString(R.string.games), "1" to getString(R.string.live), "2" to getString(R.string.videos), "3" to getString(R.string.channels))
            C.UI_SAVED_TABS -> mapOf("0" to getString(R.string.bookmarks), "1" to getString(R.string.downloads), "2" to getString(R.string.filters))
            C.UI_CHANNEL_TABS -> mapOf("0" to getString(R.string.suggestions), "1" to getString(R.string.videos), "2" to getString(R.string.clips), "3" to getString(R.string.chat), "4" to getString(R.string.about))
            C.UI_GAME_TABS -> mapOf("0" to getString(R.string.videos), "1" to getString(R.string.live), "2" to getString(R.string.clips))
            else -> mapOf("0" to getString(R.string.videos), "1" to getString(R.string.streams), "2" to getString(R.string.channels), "3" to getString(R.string.games))
        }
        val stored = prefs().getString(prefKey, null)
        val values = (stored ?: defaults).split(',').mapNotNull { value ->
            val parts = value.split(':')
            if (parts.size == 3 && labels.containsKey(parts[0])) {
                SettingsDragListItem(parts[0], labels.getValue(parts[0]), parts[1] != "0", parts[2] != "0")
            } else null
        }.toMutableList()
        labels.keys.filter { key -> values.none { it.key == key } }.forEach { key ->
            val default = defaults.split(',').firstOrNull { it.startsWith("$key:") }?.split(':')
            values += SettingsDragListItem(key, labels.getValue(key), default?.getOrNull(1) == "1", default?.getOrNull(2) != "0")
        }
        showDragListDialog(values, prefKey, title)
    }

    internal fun getSelectedSearchItem(): String? {
        return searchItem?.also {
            searchItem = null
        }
    }

    private fun setResult() {
        if (!changed) {
            changed = true
            setResult(RESULT_OK)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_CHANGED, changed)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val KEY_CHANGED = "changed"
    }

    class SettingsHomeFragment : Fragment() {

        private var _binding: FragmentSettingsHomeBinding? = null
        private val binding get() = _binding!!

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            _binding = FragmentSettingsHomeBinding.inflate(inflater, container, false)
            return binding.root
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            binding.content.removeView(binding.accountSection)
            binding.content.addView(binding.accountSection, 0)
            binding.searchCard.setOnClickListener {
                navigate(SettingsNavGraphDirections.actionGlobalSettingsSearchFragment())
            }
            val items = settingsItems()
            addSectionHeader(R.string.settings_preferences)
            items.forEachIndexed { index, item ->
                if (index == 7) addSectionHeader(R.string.settings_app)
                if (index == items.lastIndex) addSectionHeader(R.string.settings_section_advanced)
                val rowBinding = ItemSettingsRowBinding.inflate(layoutInflater, binding.sections, false)
                rowBinding.icon.setImageResource(item.icon)
                rowBinding.title.setText(item.title)
                rowBinding.summary.setText(item.summary)
                rowBinding.root.contentDescription = getString(item.title) + ". " + getString(item.summary)
                rowBinding.divider.visibility = if (index == items.lastIndex) View.GONE else View.VISIBLE
                rowBinding.root.setOnClickListener { item.onClick() }
                binding.sections.addView(rowBinding.root)
            }
            val settingsActivity = requireActivity() as SettingsActivity
            val isLoggedIn = settingsActivity.isAccountConnected()
            val accountRow = ItemSettingsRowBinding.inflate(layoutInflater, binding.accountActions, false)
            val username = requireContext().tokenPrefs().getString(C.USERNAME, null)?.takeIf { it.isNotBlank() }
            val accountSummary = if (isLoggedIn) getString(R.string.settings_account_connected_summary)
            else getString(R.string.settings_account_signed_out_summary)
            accountRow.icon.setImageResource(R.drawable.ic_settings_network)
            accountRow.title.text = if (isLoggedIn) username ?: getString(R.string.settings_account_details)
            else getString(R.string.settings_account_connected_summary)
            accountRow.summary.text = accountSummary
            accountRow.arrow.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
            accountRow.divider.visibility = View.GONE
            accountRow.root.contentDescription = accountRow.title.text.toString() + ". " + accountSummary
            accountRow.root.setOnClickListener {
                if (isLoggedIn) {
                    findNavController().navigate(R.id.apiTokenSettingsFragment)
                } else {
                    settingsActivity.openAccountAction()
                }
            }
            binding.accountActions.addView(accountRow.root)
            binding.scrollView.post { binding.scrollView.scrollTo(0, 0) }
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(bottom = resources.getDimensionPixelSize(R.dimen.settings_section_spacing) * 2 + insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }

        private fun addSectionHeader(title: Int) {
            binding.sections.addView(TextView(requireContext()).apply {
                setText(title)
                textSize = 14f
                setPadding(16.dp(), 16.dp(), 16.dp(), 6.dp())
            })
        }

        private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

        private fun settingsItems(): List<SettingsItem> = listOf(
            SettingsItem(R.string.settings_general_notifications, R.drawable.ic_settings_notifications, R.string.settings_home_notifications_summary) {
                navigate(SettingsNavGraphDirections.actionGlobalLiveNotificationSettingsFragment())
            },
            SettingsItem(R.string.settings_section_playback, R.drawable.ic_settings_playback, R.string.settings_home_playback_summary) {
                navigate(SettingsNavGraphDirections.actionGlobalPlayerSettingsFragment())
            },
            SettingsItem(R.string.settings_home_controls, R.drawable.ic_settings_playback, R.string.settings_home_controls_summary) {
                navigate(SettingsNavGraphDirections.actionGlobalPlayerButtonSettingsFragment())
            },
            SettingsItem(R.string.settings_section_chat, R.drawable.ic_settings_chat, R.string.settings_home_chat_summary) {
                navigate(SettingsNavGraphDirections.actionGlobalChatSettingsFragment())
            },
            SettingsItem(R.string.settings_section_appearance, R.drawable.ic_settings_appearance, R.string.settings_home_appearance_summary) {
                navigate(SettingsNavGraphDirections.actionGlobalThemeSettingsFragment())
            },
            SettingsItem(R.string.settings_home_browsing, R.drawable.ic_settings_data, R.string.settings_home_browsing_summary) {
                navigate(SettingsNavGraphDirections.actionGlobalUiSettingsFragment())
            },
            SettingsItem(R.string.settings_section_downloads, R.drawable.ic_settings_download, R.string.settings_home_downloads_summary) {
                navigate(SettingsNavGraphDirections.actionGlobalDownloadSettingsFragment())
            },
            SettingsItem(R.string.settings_language, R.drawable.ic_settings_data, R.string.settings_item_language_summary) {
                findNavController().navigate(R.id.languageSettingsFragment)
            },
            SettingsItem(R.string.settings_updates, R.drawable.ic_settings_updates, R.string.settings_home_updates_summary) {
                navigate(SettingsNavGraphDirections.actionGlobalUpdateSettingsFragment())
            },
            SettingsItem(R.string.settings_backup_restore, R.drawable.ic_settings_data, R.string.settings_item_backup_summary) {
                findNavController().navigate(R.id.backupSettingsFragment)
            },
            SettingsItem(R.string.settings_about, R.drawable.ic_settings_advanced, R.string.app_name) {
                findNavController().navigate(R.id.aboutSettingsFragment)
            },
            SettingsItem(R.string.settings_section_advanced, R.drawable.ic_settings_advanced, R.string.settings_home_advanced_summary) {
                navigate(SettingsNavGraphDirections.actionGlobalDebugSettingsFragment())
            }
        )

        private fun navigate(directions: NavDirections) {
            requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
            findNavController().navigate(directions)
        }

        override fun onDestroyView() {
            _binding = null
            super.onDestroyView()
        }

        private data class SettingsItem(
            @StringRes val title: Int,
            @DrawableRes val icon: Int,
            @StringRes val summary: Int,
            val onClick: () -> Unit
        )
    }

    class SettingsFragment : MaterialPreferenceFragment() {

        private val settingsScreen: String?
            get() = arguments?.getString(ARG_SETTINGS_SCREEN)

        private val viewModel: SettingsViewModel by activityViewModels { SettingsViewModelFactory }
        private var backupResultLauncher: ActivityResultLauncher<Intent>? = null
        private var restoreResultLauncher: ActivityResultLauncher<Intent>? = null
        private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
        private var updateDownloadDialogBinding: DialogUpdateDownloadBinding? = null
        private var updateDownloadDialog: AlertDialog? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            backupResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    result.data?.data?.let {
                        viewModel.backupSettings(it.toString())
                    }
                }
            }
            restoreResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
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
                        networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                        gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true),
                        helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext())
                    )
                }
            }
            notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) {
                    findPreference<SwitchPreferenceCompat>("live_notifications_enabled")?.let {
                        it.isChecked = true
                        toggleLiveNotifications(true)
                    }
                } else {
                    findPreference<SwitchPreferenceCompat>("live_notifications_enabled")?.isChecked = false
                }
                updateLiveNotificationsSummary()
            }
        }

        private fun toggleLiveNotifications(enabled: Boolean) {
            viewModel.toggleNotifications(
                enabled = enabled,
                networkLibrary = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                gqlHeaders = TwitchApiHelper.getGQLHeaders(requireContext(), true),
                helixHeaders = TwitchApiHelper.getHelixHeaders(requireContext())
            )
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
                    putExtra("android.provider.extra.APP_PACKAGE", requireContext().packageName)
                })
            } catch (_: ActivityNotFoundException) {
                // The summary still explains the blocked state when no system screen is available.
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            if (settingsScreen == SCREEN_LIVE_NOTIFICATIONS) {
                LiveNotificationScheduler.migrateMode(requireContext())
            }
            setPreferencesFromResource(
                when (settingsScreen) {
                    SCREEN_LIVE_NOTIFICATIONS -> R.xml.live_notification_preferences
                    SCREEN_UPDATES -> R.xml.update_preferences
                    SCREEN_LANGUAGE -> R.xml.language_preferences
                    SCREEN_BACKUP -> R.xml.backup_preferences
                    SCREEN_ABOUT -> R.xml.about_preferences
                    SCREEN_DISPLAY_COMPATIBILITY -> R.xml.display_compatibility_preferences
                    SCREEN_BROWSING_INFORMATION -> R.xml.browsing_information_preferences
                    SCREEN_BROWSING_SEARCH -> R.xml.browsing_search_preferences
                    SCREEN_TABS -> R.xml.tabs_preferences
                    SCREEN_PLAYER_SEEK -> R.xml.player_seek_preferences
                    SCREEN_PLAYER_GESTURES -> R.xml.player_gestures_preferences
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
                    SCREEN_DEVELOPER_API -> R.xml.developer_api_preferences
                    SCREEN_ACCOUNT -> R.xml.account_network_preferences
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
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        false
                    } else if (!LiveNotificationScheduler.canPostNotifications(requireContext())) {
                        openNotificationSettings()
                        updateLiveNotificationsSummary()
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
            findPreference<Preference>("check_updates")?.setOnPreferenceClickListener {
                viewModel.checkUpdates(
                    requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP),
                    C.DEFAULT_UPDATE_URL,
                    notifyNoUpdates = true,
                )
                true
            }
            findPreference<Preference>("update_available_details")?.setOnPreferenceClickListener {
                if (UpdateState.isPending(requireContext())) {
                    UpdateState.read(requireContext())?.let(::showUpdateDialog)
                }
                true
            }
            findPreference<Preference>("ignore_update")?.setOnPreferenceClickListener {
                UpdateState.ignore(requireContext())
                updateUpdatePreferences()
                true
            }
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
            findPreference<Preference>("app_version")?.summary = getString(
                R.string.app_version_summary,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            )
            findPreference<Preference>("app_build")?.summary = getString(
                R.string.app_build_summary,
                BuildConfig.BUILD_TYPE,
            )
            findPreference<Preference>("app_package")?.summary = BuildConfig.APPLICATION_ID
            findPreference<Preference>("last_update_check")?.summary = requireContext().tokenPrefs()
                .getLong(C.UPDATE_LAST_CHECKED, 0L)
                .takeIf { it > 0L }
                ?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
                ?: getString(R.string.never)
            configureRedesignedPreferences()
        }

        private fun configureRedesignedPreferences() {
            val activity = requireActivity() as SettingsActivity
            val destinations = mapOf(
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
                "developer_api_authentication" to R.id.developerApiFragment,
            )
            destinations.forEach { (key, destination) ->
                findPreference<Preference>(key)?.setOnPreferenceClickListener {
                    findNavController().navigate(destination)
                    true
                }
            }
            listOf(
                C.UI_NAVIGATION_TAB_LIST,
                C.UI_FOLLOWING_TABS,
                C.UI_SAVED_TABS,
                C.UI_CHANNEL_TABS,
                C.UI_GAME_TABS,
                C.UI_SEARCH_TABS,
            ).forEach { key ->
                findPreference<Preference>("${key}_dialog")?.setOnPreferenceClickListener {
                    activity.showTabDialog(key, it.title)
                    true
                }
            }
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
            findPreference<Preference>("reset_settings")?.setOnPreferenceClickListener {
                requireActivity().getAlertDialogBuilder()
                    .setTitle(R.string.settings_reset_action)
                    .setMessage(R.string.settings_reset_summary)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        val context = requireContext()
                        LiveNotificationScheduler.disable(context)
                        viewModel.resetNotificationState()
                        SettingsMigration.resetUserPreferences(context)
                        UpdateState.clear(context)
                        context.tokenPrefs().edit {
                            remove(C.UPDATE_LAST_CHECKED)
                            remove(C.UPDATE_LAST_ATTEMPTED)
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
            findPreference<Preference>("account_status")?.summary = if (activity.isAccountConnected()) {
                getString(R.string.settings_account_connected)
            } else {
                getString(R.string.settings_account_disconnected)
            }
            findPreference<Preference>("account_logout")?.apply {
                isVisible = activity.isAccountConnected()
                setOnPreferenceClickListener {
                    activity.accountActionIsLogout = true
                    activity.loginResultLauncher?.launch(Intent(activity, LoginActivity::class.java))
                    true
                }
            }
            findPreference<SwitchPreferenceCompat>(C.SETTINGS_DEVELOPER_ENABLED)?.setOnPreferenceChangeListener { _, value ->
                val enabled = value as Boolean
                requireContext().prefs().edit { putBoolean(C.SETTINGS_DEVELOPER_ENABLED, enabled) }
                if (!enabled) {
                    (requireActivity() as SettingsActivity).setResult()
                    requireActivity().recreate()
                }
                true
            }
            findPreference<Preference>("copy_diagnostics")?.setOnPreferenceClickListener {
                val clipboard = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Xtra diagnostics", diagnosticInformation()))
                Toast.makeText(requireContext(), R.string.settings_diagnostics_copied, Toast.LENGTH_SHORT).show()
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
            findPreference<SwitchPreferenceCompat>("settings_mix_audio")?.setOnPreferenceChangeListener { _, value ->
                requireContext().prefs().edit { putBoolean(C.PLAYER_AUDIO_FOCUS, !(value as Boolean)) }
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
            if (settingsScreen == SCREEN_DEVELOPER_API) configureDeveloperCredentials()
            if (settingsScreen == SCREEN_CHAT_TRANSLATION) configureTranslationPreferences()
            if (settingsScreen == SCREEN_PLAYER_SEEK) configureSeekPreferences()
            if (settingsScreen == SCREEN_CHAT_APPEARANCE) configureChatSizePreferences()
            if (settingsScreen == SCREEN_DOWNLOAD_LIVE) configureLiveDownloadPreferences()
        }

        private fun configureChatSizePreferences() {
            appendCustomListValue(findPreference(C.CHAT_TEXT_SIZE), "sp")
            appendCustomListValue(findPreference(C.CHAT_EMOTE_SIZE), "dp")
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
                            requireActivity().getAlertDialogBuilder()
                                .setTitle(title)
                                .setView(input)
                                .setPositiveButton(android.R.string.ok) { _, _ ->
                                    val seconds = input.text.toString().toIntOrNull()
                                    if (seconds == null || seconds !in 1..600) {
                                        Toast.makeText(requireContext(), "Enter a value from 1 to 600 seconds.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        seekPreference.value = seconds.toString()
                                        seekPreference.summary = "${seconds} sec"
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                            false
                        } else {
                            preference.summary = "${newValue} sec"
                            true
                        }
                    }
                }
            }
        }

        private fun configureDeveloperCredentials() {
            listOf(C.USER_ID, C.USERNAME, C.TOKEN, C.GQL_TOKEN2, C.GQL_TOKEN_WEB).forEach { key ->
                findPreference<EditTextPreference>(key)?.apply {
                    isPersistent = false
                    text = requireContext().tokenPrefs().getString(key, null)
                    if (key in setOf(C.TOKEN, C.GQL_TOKEN2, C.GQL_TOKEN_WEB)) {
                        summary = "Sensitive value"
                        setOnBindEditTextListener {
                            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            it.transformationMethod = PasswordTransformationMethod.getInstance()
                        }
                    }
                    setOnPreferenceChangeListener { _, value ->
                        requireContext().tokenPrefs().edit { putString(key, value.toString()) }
                        true
                    }
                }
            }
            findPreference<EditTextPreference>(C.GQL_HEADERS)?.apply {
                isPersistent = false
                text = requireContext().tokenPrefs().getString(C.GQL_HEADERS, null)
                summary = "Sensitive value"
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    it.transformationMethod = PasswordTransformationMethod.getInstance()
                }
                setOnPreferenceChangeListener { _, value ->
                    requireContext().tokenPrefs().edit { putString(C.GQL_HEADERS, value.toString()) }
                    true
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
            appendLine("Player: ${requireContext().prefs().getString(C.PLAYER, C.EXOPLAYER)}")
            appendLine("Network engine: ${requireContext().prefs().getString(C.NETWORK_LIBRARY, "Automatic")}")
            appendLine("PiP: ${requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)}")
            appendLine("Notifications: ${Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED}")
            appendLine("ML Kit translation: ${Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a"}")
        }

        private fun updateUpdatePreferences() {
            val info = UpdateState.read(requireContext())
            val pending = UpdateState.isPending(requireContext())
            findPreference<PreferenceCategory>("updates_category")?.let { category ->
                val title = getString(R.string.settings_general_updates)
                category.title = if (pending) {
                    SpannableString("• $title").apply {
                        setSpan(
                            ForegroundColorSpan(
                                requireContext().getColor(android.R.color.holo_red_light)
                            ),
                            0,
                            length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                } else {
                    title
                }
            }
            findPreference<Preference>("update_available_details")?.apply {
                isVisible = info != null
                if (info != null) {
                    title = getString(R.string.update_available_version, info.version)
                    summary = info.body.ifBlank { getString(R.string.update_no_release_notes) }
                }
            }
            findPreference<Preference>("ignore_update")?.apply {
                isVisible = pending
                summary = info?.let { getString(R.string.ignore_update_summary, it.version) }
            }
            findPreference<Preference>("check_updates")?.summary = when {
                pending && info != null -> getString(R.string.update_pending_summary, info.version)
                UpdateState.isIgnored(requireContext()) && info != null -> getString(R.string.update_ignored_summary, info.version)
                UpdateState.isDownloaded(requireContext()) && info != null -> getString(R.string.update_downloaded_summary, info.version)
                else -> getString(R.string.check_updates_summary)
            }
            findPreference<Preference>("last_update_check")?.summary = requireContext().tokenPrefs()
                .getLong(C.UPDATE_LAST_CHECKED, 0L)
                .takeIf { it > 0L }
                ?.let { DateFormat.getDateTimeInstance().format(Date(it)) }
                ?: getString(R.string.never)
        }

        private fun showUpdateDialog(info: UpdateInfo) {
            val releaseNotes = buildString {
                if (info.title.isNotBlank() && !info.title.equals(info.version, true)) {
                    append(info.title)
                    append("\n")
                }
                append(info.version)
                if (info.body.isNotBlank()) {
                    append("\n\n")
                    append(info.body)
                }
                append("\n\n")
                append(getString(R.string.update_message))
            }
            requireActivity().getAlertDialogBuilder()
                .setTitle(getString(R.string.update_available_version, info.version))
                .setMessage(releaseNotes)
                .setPositiveButton(getString(R.string.yes)) { _, _ ->
                    val binding = DialogUpdateDownloadBinding.inflate(layoutInflater)
                    updateDownloadDialogBinding = binding
                    val size = info.size
                    if (size != null) {
                        binding.textView.text = getString(
                            R.string.downloading_update_progress,
                            Formatter.formatFileSize(requireContext(), 0),
                            Formatter.formatFileSize(requireContext(), size),
                        )
                    } else {
                        binding.textView.text = getString(R.string.downloading_update)
                        binding.progressBar.visibility = View.GONE
                    }
                    viewModel.downloadUpdate(requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP), info)
                    val dialog = requireActivity().getAlertDialogBuilder()
                        .setView(binding.root)
                        .setNegativeButton(getString(android.R.string.cancel), null)
                        .setOnDismissListener {
                            viewModel.updateJob?.cancel()
                            updateDownloadDialogBinding = null
                            updateDownloadDialog = null
                        }
                        .show()
                    updateDownloadDialog = dialog
                }
                .setNeutralButton(getString(R.string.ignore_update)) { _, _ ->
                    UpdateState.ignore(requireContext())
                    updateUpdatePreferences()
                }
                .setNegativeButton(getString(R.string.update_later), null)
                .show()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.liveNotificationResult.collectLatest { result ->
                        findPreference<SwitchPreferenceCompat>("live_notifications_enabled")?.isChecked = result.enabled
                        updateLiveNotificationsSummary()
                        if (result.failed) {
                            Toast.makeText(requireContext(), R.string.live_notifications_enable_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.updateInfo.collectLatest {
                        if (it != null) {
                            showUpdateDialog(it)
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.updateCheckFinished.collectLatest { found ->
                        updateUpdatePreferences()
                        if (!found) {
                            Toast.makeText(requireContext(), R.string.no_updates_found, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.updateProgress.collectLatest {
                        updateDownloadDialogBinding?.let { binding ->
                            val size = viewModel.updateSize
                            if (size != null) {
                                binding.textView.text = getString(
                                    R.string.downloading_update_progress,
                                    Formatter.formatFileSize(requireContext(), it),
                                    Formatter.formatFileSize(requireContext(), size),
                                )
                                binding.progressBar.progress = (((it.toFloat() / size) * 100)).toInt()
                            }
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.closeUpdateDialog.collectLatest {
                        updateDownloadDialog?.dismiss()
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.updateDownloadFailed.collectLatest {
                        Toast.makeText(requireContext(), R.string.update_download_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            updateUpdatePreferences()
        }

        override fun onResume() {
            super.onResume()
            val preference = findPreference<SwitchPreferenceCompat>("live_notifications_enabled")
            if (preference?.isChecked == true && !LiveNotificationScheduler.canPostNotifications(requireContext())) {
                preference.isChecked = false
                toggleLiveNotifications(false)
            }
            updateLiveNotificationsSummary()
            updateUpdatePreferences()
        }

        private companion object {
            const val ARG_SETTINGS_SCREEN = "settings_screen"
            const val SCREEN_LIVE_NOTIFICATIONS = "live_notifications"
            const val SCREEN_UPDATES = "updates"
            const val SCREEN_LANGUAGE = "language"
            const val SCREEN_BACKUP = "backup"
            const val SCREEN_ABOUT = "about"
            const val SCREEN_DISPLAY_COMPATIBILITY = "display_compatibility"
            const val SCREEN_BROWSING_INFORMATION = "browsing_information"
            const val SCREEN_BROWSING_SEARCH = "browsing_search"
            const val SCREEN_TABS = "tabs"
            const val SCREEN_PLAYER_SEEK = "player_seek"
            const val SCREEN_PLAYER_GESTURES = "player_gestures"
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
            const val SCREEN_DEVELOPER_API = "developer_api"
            const val SCREEN_ACCOUNT = "account"
        }
    }

    class ThemeSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.theme_preferences, rootKey)
            val changeListener = Preference.OnPreferenceChangeListener { _, _ ->
                (requireActivity() as? SettingsActivity)?.changed = true
                requireActivity().recreate()
                true
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                findPreference<SwitchPreferenceCompat>(C.SETTINGS_DEVICE_COLORS)?.isVisible = false
            }
            findPreference<ListPreference>(C.SETTINGS_THEME_MODE)?.onPreferenceChangeListener = changeListener
            findPreference<SwitchPreferenceCompat>(C.SETTINGS_DEVICE_COLORS)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.SETTINGS_DENSITY)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.UI_THEME_ROUNDED_CORNERS)?.onPreferenceChangeListener = changeListener
            findPreference<ListPreference>(C.SETTINGS_PROFILE_PICTURE_STYLE)?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                requireContext().prefs().edit { putBoolean(C.UI_ROUND_USER_IMAGE, value == "round") }
                true
            }
            findPreference<Preference>("appearance_display_compatibility")?.setOnPreferenceClickListener {
                findNavController().navigate(R.id.appearanceDisplayCompatibilityFragment)
                true
            }
        }

    }

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
                val tabList = requireContext().prefs().getString(C.UI_NAVIGATION_TAB_LIST, null).let { tabPref ->
                    val defaultTabs = C.DEFAULT_NAVIGATION_TAB_LIST.split(',')
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
                            "0" -> getString(R.string.games)
                            "1" -> getString(R.string.popular)
                            "2" -> getString(R.string.following)
                            "3" -> getString(R.string.saved)
                            else -> getString(R.string.popular)
                        },
                        default = split[1] != "0",
                        enabled = split[2] != "0",
                    )
                }
                (requireActivity() as? SettingsActivity)?.showDragListDialog(tabs, C.UI_NAVIGATION_TAB_LIST, preference.title)
                true
            }
            findPreference<Preference>("ui_following_tabs_dialog")?.setOnPreferenceClickListener { preference ->
                val tabList = requireContext().prefs().getString(C.UI_FOLLOWING_TABS, null).let { tabPref ->
                    val defaultTabs = C.DEFAULT_FOLLOWING_TABS.split(',')
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
                            "0" -> getString(R.string.games)
                            "1" -> getString(R.string.live)
                            "2" -> getString(R.string.videos)
                            "3" -> getString(R.string.channels)
                            else -> getString(R.string.live)
                        },
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

    class ChatSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.chat_preferences, rootKey)
            findPreference<Preference>("chat_appearance_page")?.setOnPreferenceClickListener { findNavController().navigate(R.id.chatAppearanceFragment); true }
            findPreference<Preference>("chat_username_page")?.setOnPreferenceClickListener { findNavController().navigate(R.id.chatUsernameFragment); true }
            findPreference<Preference>("chat_emotes_page")?.setOnPreferenceClickListener { findNavController().navigate(R.id.chatEmotesFragment); true }
            findPreference<Preference>("chat_features_page")?.setOnPreferenceClickListener { findNavController().navigate(R.id.chatFeaturesFragment); true }
            findPreference<Preference>("chat_history_page")?.setOnPreferenceClickListener { findNavController().navigate(R.id.chatHistoryFragment); true }
            findPreference<Preference>("chat_translation_page")?.setOnPreferenceClickListener { findNavController().navigate(R.id.chatTranslationFragment); true }
            findPreference<Preference>("chat_visibility_page")?.setOnPreferenceClickListener { findNavController().navigate(R.id.chatVisibilityFragment); true }
            findPreference<SwitchPreferenceCompat>(C.SETTINGS_CHAT_ENABLED)?.setOnPreferenceChangeListener { _, value ->
                requireContext().prefs().edit {
                    putBoolean(C.SETTINGS_CHAT_ENABLED, value as Boolean)
                    putBoolean(C.CHAT_DISABLE, !(value as Boolean))
                }
                true
            }
            val translationSupported = Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a"
            findPreference<Preference>("chat_translation_page")?.isVisible = translationSupported
            if (translationSupported) {
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
                                            if (!selectedItems.contains(language)) {
                                                selectedItems.add(language)
                                            }
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
                                        modelManager.download(
                                            TranslateRemoteModel.Builder(it).build(),
                                            DownloadConditions.Builder().build()
                                        )
                                    }
                                }
                                .setNegativeButton(getString(android.R.string.cancel), null)
                                .show()
                        }
                    true
                }
                findPreference<ListPreference>("chat_translate_target")?.apply {
                    entries = names
                    entryValues = languages.toTypedArray()
                }
            } else {
                findPreference<SwitchPreferenceCompat>("chat_translate")?.isVisible = false
                findPreference<Preference>("downloaded_languages")?.isVisible = false
                findPreference<ListPreference>("chat_translate_target")?.isVisible = false
            }
        }

    }

    class PlayerSettingsFragment : MaterialPreferenceFragment() {
        private val viewModel: SettingsViewModel by activityViewModels { SettingsViewModelFactory }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.playback_preferences, rootKey)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                findPreference<SwitchPreferenceCompat>(C.PLAYER_PICTURE_IN_PICTURE)?.isVisible = false
            }
            findPreference<SwitchPreferenceCompat>(C.SETTINGS_BACKGROUND_PLAYBACK)?.setOnPreferenceChangeListener { _, value ->
                val enabled = value as Boolean
                requireContext().prefs().edit { putBoolean(C.SETTINGS_BACKGROUND_PLAYBACK, enabled) }
                true
            }
            findPreference<Preference>("delete_video_positions")?.setOnPreferenceClickListener {
                requireActivity().getAlertDialogBuilder()
                    .setMessage(getString(R.string.delete_video_positions_message))
                    .setPositiveButton(getString(R.string.yes)) { _, _ ->
                        viewModel.deletePositions()
                    }
                    .setNegativeButton(getString(R.string.no), null)
                    .show()
                true
            }
        }

    }

    class PlayerButtonSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.player_controls_preferences, rootKey)
            findPreference<Preference>("player_seek_controls")?.setOnPreferenceClickListener { findNavController().navigate(R.id.playerSeekFragment); true }
            findPreference<Preference>("player_gestures")?.setOnPreferenceClickListener { findNavController().navigate(R.id.playerGesturesFragment); true }
            findPreference<Preference>("player_information")?.setOnPreferenceClickListener { findNavController().navigate(R.id.playerInformationFragment); true }
            findPreference<Preference>("player_speed_options")?.setOnPreferenceClickListener { showSpeedOptionsDialog(); true }
            findPreference<Preference>("customize_controls")?.setOnPreferenceClickListener { showControlLayoutDialog(); true }
        }

        private fun showSpeedOptionsDialog() {
            val items = readSpeedItems()
            val listAdapter = SettingsDragListAdapter()
            val itemTouchHelper = ItemTouchHelper(
                object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                        Collections.swap(items, viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                        listAdapter.notifyItemMoved(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                        return true
                    }

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                    override fun isLongPressDragEnabled(): Boolean = false
                }
            )
            listAdapter.itemTouchHelper = itemTouchHelper
            val recyclerView = RecyclerView(requireContext()).apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = listAdapter
                val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10F, resources.displayMetrics).toInt()
                setPadding(0, padding, 0, 0)
            }
            val listContainer = android.widget.FrameLayout(requireContext()).apply {
                addView(
                    recyclerView,
                    android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (resources.displayMetrics.heightPixels * 0.42f).toInt(),
                    ),
                )
            }
            itemTouchHelper.attachToRecyclerView(recyclerView)
            listAdapter.submitList(items)
            requireActivity().getAlertDialogBuilder()
                .setTitle(R.string.settings_playback_speed_options)
                .setMessage("Drag to reorder. Uncheck values you do not want to show.")
                .setView(listContainer)
                .setNeutralButton("Add custom speed") { _, _ -> showCustomSpeedDialog(items) }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    saveSpeeds(items)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun showCustomSpeedDialog(items: MutableList<SettingsDragListItem>) {
            // The neutral button dismisses the editor. Keep its current in-memory state
            // before opening the second dialog.
            saveSpeeds(items)
            val input = android.widget.EditText(requireContext()).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                hint = "> 0 and ≤ 16"
            }
            requireActivity().getAlertDialogBuilder()
                .setTitle("Add custom speed")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val value = input.text.toString().toDoubleOrNull()
                    if (value == null || value <= 0.0 || value > 16.0) {
                        Toast.makeText(requireContext(), "Enter a speed between 0.25 and 16.", Toast.LENGTH_SHORT).show()
                    } else {
                        if (items.none { it.key.toDoubleOrNull() == value }) {
                            items.add(SettingsDragListItem(value.toString(), "${value}×", default = false, enabled = true))
                            saveSpeeds(items)
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun readSpeedItems(): MutableList<SettingsDragListItem> {
            val serialized = requireContext().prefs().getString(C.SETTINGS_PLAYER_SPEED_OPTIONS, null)
            val values = serialized?.split(',')?.mapNotNull { item ->
                val parts = item.split(':')
                val speed = parts.firstOrNull()?.toDoubleOrNull()?.takeIf { it > 0.0 && it <= 16.0 }
                speed?.let {
                    SettingsDragListItem(
                        key = it.toString(),
                        text = "${it}×",
                        default = false,
                        enabled = parts.getOrNull(1)?.let { enabled -> enabled == "1" || enabled.equals("true", true) } ?: true,
                    )
                }
            }?.distinctBy { it.key }
            return (values ?: listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0, 4.0, 8.0).map {
                SettingsDragListItem(it.toString(), "${it}×", default = false, enabled = true)
            }).toMutableList()
        }

        private fun saveSpeeds(items: List<SettingsDragListItem>) {
            requireContext().prefs().edit {
                putString(C.SETTINGS_PLAYER_SPEED_OPTIONS, serializeSpeedOptions(items))
                putString(C.PLAYER_SPEED_LIST, items.filter { it.enabled }.joinToString("\n") { it.key })
            }
        }

        private fun showControlLayoutDialog() {
            val preferences = requireContext().prefs()
            val serialized = preferences.getString(C.SETTINGS_PLAYER_CONTROL_LAYOUT, null)
                ?.takeIf { it.isNotBlank() }
                ?: SettingsMigration.defaultControlLayout()
            val items = serialized
                .split(',')
                .filter { it.contains(':') }
                .map { item ->
                    val parts = item.split(':')
                    val action = parts[0]
                    val group = parts.getOrNull(1)?.takeIf { it in setOf("quick", "menu", "hidden") } ?: "hidden"
                    SettingsDragListItem(
                        key = action,
                        text = formatControlText(action, group),
                        default = false,
                        enabled = group != "hidden",
                        group = group,
                    )
                }
                .toMutableList()
            val listAdapter = SettingsDragListAdapter()
            val itemTouchHelper = ItemTouchHelper(
                object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                    override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                        Collections.swap(items, viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                        listAdapter.notifyItemMoved(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                        return true
                    }

                    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                    override fun isLongPressDragEnabled(): Boolean = false
                }
            )
            listAdapter.itemTouchHelper = itemTouchHelper
            listAdapter.cycleGroup = { item ->
                item.group = when (item.group) {
                    "quick" -> "menu"
                    "menu" -> "hidden"
                    else -> "quick"
                }
                item.enabled = item.group != "hidden"
                item.text = formatControlText(item.key, item.group)
                listAdapter.notifyItemChanged(items.indexOf(item))
            }
            val recyclerView = RecyclerView(requireContext()).apply {
                layoutManager = LinearLayoutManager(requireContext())
                adapter = listAdapter
                val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10F, resources.displayMetrics).toInt()
                setPadding(0, padding, 0, 0)
            }
            itemTouchHelper.attachToRecyclerView(recyclerView)
            listAdapter.submitList(items)
            requireActivity().getAlertDialogBuilder()
                .setTitle(R.string.settings_customize_controls)
                .setMessage("Drag to reorder. Tap the group button to cycle Quick controls, More menu and Hidden.")
                .setView(recyclerView)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val serialized = items.joinToString(",") { "${it.key}:${it.group}" }
                    preferences.edit { putString(C.SETTINGS_PLAYER_CONTROL_LAYOUT, serialized) }
                    syncLegacyControlVisibility(preferences, items.map { "${it.key}:${it.group}" })
                }
                .show()
        }

        private fun formatControlText(action: String, group: String): String {
            val title = when (action) {
                "minimize" -> getString(R.string.player_minimize)
                "download" -> getString(R.string.player_download)
                "follow" -> getString(R.string.player_follow)
                "quality" -> getString(R.string.player_quality)
                "speed" -> getString(R.string.player_playback_speed)
                "chapters" -> getString(R.string.player_vod_games)
                "restart" -> getString(R.string.player_restart)
                "live" -> getString(R.string.player_seek_live)
                "volume" -> getString(R.string.player_volume)
                "compressor" -> getString(R.string.player_audio_compressor)
                "mode" -> getString(R.string.settings_player_mode)
                "subtitles" -> getString(R.string.player_subtitles)
                "chat_input" -> getString(R.string.player_chat_input)
                "chat" -> getString(R.string.player_show_chat)
                "fullscreen" -> getString(R.string.fullscreen)
                "viewers" -> getString(R.string.viewer_list)
                "bookmark" -> getString(R.string.bookmark)
                "share" -> getString(R.string.share)
                "find_vod" -> getString(R.string.find_unlisted_video)
                "sleep" -> getString(R.string.sleep_timer)
                "aspect" -> getString(R.string.aspect_ratio)
                "reload_emotes" -> getString(R.string.reload_emotes)
                "disconnect_chat" -> getString(R.string.disconnect_chat)
                else -> action
            }
            val groupTitle = getString(when (group) {
                "quick" -> R.string.settings_control_group_quick
                "menu" -> R.string.settings_control_group_menu
                else -> R.string.settings_control_group_hidden
            })
            return "$title — $groupTitle"
        }

        private fun syncLegacyControlVisibility(preferences: android.content.SharedPreferences, items: List<String>) {
            val actionKeys = mapOf(
                "minimize" to (C.PLAYER_MINIMIZE to null),
                "download" to (C.PLAYER_DOWNLOAD to C.PLAYER_MENU_DOWNLOAD),
                "follow" to (C.PLAYER_FOLLOW to null),
                "quality" to (C.PLAYER_SETTINGS to C.PLAYER_MENU_QUALITY),
                "speed" to (C.PLAYER_SPEED_BUTTON to C.PLAYER_MENU_SPEED),
                "chapters" to (C.PLAYER_GAMES_BUTTON to C.PLAYER_MENU_GAMES),
                "restart" to (C.PLAYER_RESTART to C.PLAYER_MENU_RESTART),
                "live" to (C.PLAYER_SEEK_LIVE to null),
                "volume" to (C.PLAYER_VOLUME_BUTTON to C.PLAYER_MENU_VOLUME),
                "compressor" to (C.PLAYER_AUDIO_COMPRESSOR_BUTTON to null),
                "mode" to (C.PLAYER_MODE to null),
                "subtitles" to (C.PLAYER_SUBTITLES to C.PLAYER_MENU_SUBTITLES),
                "chat_input" to (C.PLAYER_CHAT_BAR_TOGGLE to C.PLAYER_MENU_CHAT_BAR),
                "chat" to (C.PLAYER_CHAT_TOGGLE to C.PLAYER_MENU_CHAT_TOGGLE),
                "fullscreen" to (C.PLAYER_FULLSCREEN to null),
                "viewers" to (C.PLAYER_VIEWER_LIST to C.PLAYER_MENU_VIEWER_LIST),
                "bookmark" to (null to C.PLAYER_MENU_BOOKMARK),
                "share" to (null to C.PLAYER_MENU_SHARE),
                "find_vod" to (null to C.PLAYER_MENU_FIND_VOD),
                "sleep" to (C.PLAYER_SLEEP to C.PLAYER_MENU_SLEEP),
                "aspect" to (C.PLAYER_ASPECT to C.PLAYER_MENU_ASPECT),
                "reload_emotes" to (null to C.PLAYER_MENU_RELOAD_EMOTES),
                "disconnect_chat" to (null to C.PLAYER_MENU_CHAT_DISCONNECT),
            )
            val groups = items.associate { item ->
                val parts = item.split(':')
                parts[0] to (parts.getOrNull(1) ?: "hidden")
            }
            preferences.edit {
                actionKeys.forEach { (action, keys) ->
                    val group = groups[action]
                    keys.first?.let { putBoolean(it, group == "quick") }
                    keys.second?.let { putBoolean(it, group == "menu") }
                }
                putBoolean(C.PLAYER_MENU, groups.values.any { it == "menu" })
            }
        }

    }

    class DownloadSettingsFragment : MaterialPreferenceFragment() {
        private val viewModel: SettingsViewModel by activityViewModels { SettingsViewModelFactory }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.download_preferences, rootKey)
            findPreference<Preference>("import_app_downloads")?.setOnPreferenceClickListener {
                viewModel.importDownloads()
                true
            }
            findPreference<Preference>("download_live_page")?.setOnPreferenceClickListener {
                findNavController().navigate(R.id.downloadLiveFragment)
                true
            }
        }

    }

    class DebugSettingsFragment : MaterialPreferenceFragment() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.debug_preferences, rootKey)
            findPreference<ListPreference>(C.NETWORK_LIBRARY)?.apply {
                val supported = buildList {
                    add(C.AUTOMATIC)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 7
                    ) {
                        add(C.HTTP_ENGINE)
                    }
                    if (CronetProvider.getAllProviders(requireContext()).any { it.isEnabled }) {
                        add(C.CRONET)
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
            findPreference<SwitchPreferenceCompat>("settings_mix_audio")?.apply {
                isChecked = !requireContext().prefs().getBoolean(C.PLAYER_AUDIO_FOCUS, false)
                setOnPreferenceChangeListener { _, value ->
                    requireContext().prefs().edit { putBoolean(C.PLAYER_AUDIO_FOCUS, !(value as Boolean)) }
                    true
                }
            }
            findPreference<Preference>("copy_diagnostics")?.setOnPreferenceClickListener {
                val clipboard = requireContext().getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("Xtra diagnostics", diagnosticInformation()))
                Toast.makeText(requireContext(), R.string.settings_diagnostics_copied, Toast.LENGTH_SHORT).show()
                true
            }
        }

        private fun diagnosticInformation(): String = buildString {
            appendLine("Xtra ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Build: ${BuildConfig.BUILD_TYPE}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Player: ${requireContext().prefs().getString(C.PLAYER, C.EXOPLAYER)}")
            appendLine("Network engine: ${requireContext().prefs().getString(C.NETWORK_LIBRARY, "Automatic")}")
            appendLine("PiP: ${requireContext().packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)}")
            appendLine("Notifications: ${Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED}")
            appendLine("ML Kit translation: ${Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a"}")
        }

    }

    class SettingsSearchFragment : Fragment() {
        private var preferences: List<SettingsSearchItem>? = null
        private var adapter: SettingsSearchAdapter? = null
        private var savedQuery: String? = null

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
            return RecyclerView(requireContext()).apply {
                clipToPadding = false
                layoutManager = LinearLayoutManager(requireContext())
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
                                        (view as RecyclerView).scrollToPosition(0)
                                    }
                                } catch (e: Exception) {

                                }
                            }
                        })
                    }
                })
            }
            (view as RecyclerView).adapter = adapter
            if (preferences == null) {
                val list = mutableListOf<SettingsSearchItem>()
                val preferenceManager = PreferenceManager(requireContext())
                val developerVisible = requireContext().prefs().getBoolean(C.SETTINGS_DEVELOPER_UNLOCKED, false) &&
                    requireContext().prefs().getBoolean(C.SETTINGS_DEVELOPER_ENABLED, false)
                listOf(
                    Triple(R.xml.live_notification_preferences, SettingsNavGraphDirections.actionGlobalLiveNotificationSettingsFragment(), getString(R.string.settings_general_notifications)),
                    Triple(R.xml.update_preferences, SettingsNavGraphDirections.actionGlobalUpdateSettingsFragment(), getString(R.string.settings_general_updates)),
                    Triple(R.xml.language_preferences, SettingsNavDirections(R.id.languageSettingsFragment), "App › Language"),
                    Triple(R.xml.backup_preferences, SettingsNavDirections(R.id.backupSettingsFragment), "App › Backup & restore"),
                    Triple(R.xml.about_preferences, SettingsNavDirections(R.id.aboutSettingsFragment), "App › About"),
                    Triple(R.xml.theme_preferences, SettingsNavGraphDirections.actionGlobalThemeSettingsFragment(), getString(R.string.settings_section_appearance)),
                    Triple(R.xml.display_compatibility_preferences, SettingsNavDirections(R.id.appearanceDisplayCompatibilityFragment), "Appearance › Display compatibility"),
                    Triple(R.xml.ui_preferences, SettingsNavGraphDirections.actionGlobalUiSettingsFragment(), getString(R.string.settings_home_browsing)),
                    Triple(R.xml.browsing_information_preferences, SettingsNavDirections(R.id.browsingInformationFragment), "Browsing › Displayed information"),
                    Triple(R.xml.browsing_search_preferences, SettingsNavDirections(R.id.browsingSearchFragment), "Browsing › Search history"),
                    Triple(R.xml.tabs_preferences, SettingsNavDirections(R.id.browsingTabsFragment), "Browsing › Navigation › Customize tabs"),
                    Triple(R.xml.playback_preferences, SettingsNavGraphDirections.actionGlobalPlayerSettingsFragment(), getString(R.string.settings_section_playback)),
                    Triple(R.xml.player_controls_preferences, SettingsNavGraphDirections.actionGlobalPlayerButtonSettingsFragment(), getString(R.string.settings_home_controls)),
                    Triple(R.xml.player_seek_preferences, SettingsNavDirections(R.id.playerSeekFragment), "Player controls › Seek controls"),
                    Triple(R.xml.player_gestures_preferences, SettingsNavDirections(R.id.playerGesturesFragment), "Player controls › Gestures"),
                    Triple(R.xml.player_information_preferences, SettingsNavDirections(R.id.playerInformationFragment), "Player controls › Player information"),
                    Triple(R.xml.chat_preferences, SettingsNavGraphDirections.actionGlobalChatSettingsFragment(), getString(R.string.settings_section_chat)),
                    Triple(R.xml.chat_appearance_preferences, SettingsNavDirections(R.id.chatAppearanceFragment), "Chat › Appearance"),
                    Triple(R.xml.chat_username_preferences, SettingsNavDirections(R.id.chatUsernameFragment), "Chat › Appearance › Username appearance"),
                    Triple(R.xml.chat_emotes_preferences, SettingsNavDirections(R.id.chatEmotesFragment), "Chat › Emotes & badges"),
                    Triple(R.xml.chat_features_preferences, SettingsNavDirections(R.id.chatFeaturesFragment), "Chat › Twitch features"),
                    Triple(R.xml.chat_history_preferences, SettingsNavDirections(R.id.chatHistoryFragment), "Chat › History"),
                    Triple(R.xml.chat_translation_preferences, SettingsNavDirections(R.id.chatTranslationFragment), "Chat › Translation"),
                    Triple(R.xml.chat_visibility_preferences, SettingsNavDirections(R.id.chatVisibilityFragment), "Chat › Message visibility"),
                    Triple(R.xml.download_preferences, SettingsNavGraphDirections.actionGlobalDownloadSettingsFragment(), getString(R.string.settings_section_downloads)),
                    Triple(R.xml.download_live_preferences, SettingsNavDirections(R.id.downloadLiveFragment), "Downloads › Live stream downloads"),
                    Triple(R.xml.proxy_preferences, SettingsNavDirections(R.id.proxySettingsFragment), "Advanced › Proxy"),
                    Triple(R.xml.debug_preferences, SettingsNavGraphDirections.actionGlobalDebugSettingsFragment(), "Advanced"),
                ).plus(if (developerVisible) listOf(
                    Triple(R.xml.developer_preferences, SettingsNavDirections(R.id.developerSettingsFragment), "Developer options"),
                    Triple(R.xml.developer_api_preferences, SettingsNavDirections(R.id.developerApiFragment), "Developer options › API & authentication"),
                ) else emptyList()).forEach { item ->
                    preferenceManager.inflateFromResource(requireContext(), item.first, null).forEach {
                        if (!it.isVisible) return@forEach
                        when (it) {
                            is SwitchPreferenceCompat -> {
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
                                list.add(SettingsSearchItem(
                                    navDirections = item.second,
                                    location = item.third,
                                    key = it.key,
                                    title = it.title,
                                    summary = it.summary,
                                    value = it.value.toString()
                                ))
                            }
                            is PreferenceCategory -> {}
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
                preferences = list
            }
            requireActivity().findViewById<SearchView>(R.id.searchView)?.let {
                savedQuery?.let { query -> it.setQuery(query, true) }
                it.requestFocus()
                WindowCompat.getInsetsController(requireActivity().window, it).show(WindowInsetsCompat.Type.ime())
            }
        }

        fun search(query: String) {
            savedQuery = query
            if (query.isNotBlank()) {
                preferences?.filter {
                    it.location?.contains(query, true) == true ||
                        it.key?.contains(query, true) == true ||
                        it.title?.contains(query, true) == true ||
                        it.summary?.contains(query, true) == true ||
                        it.value?.contains(query, true) == true
                }?.let { list ->
                    adapter?.submitList(list)
                }
            } else {
                adapter?.submitList(emptyList())
            }
        }

        override fun onDestroyView() {
            super.onDestroyView()
            (requireActivity() as? SettingsActivity)?.showSearchView(false)
        }
    }
}
