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
import android.text.format.Formatter
import android.text.method.PasswordTransformationMethod
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
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
import androidx.core.app.NotificationManagerCompat
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
import androidx.preference.PreferenceDataStore
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
import com.github.andreyasadchy.xtra.databinding.FragmentUpdateSettingsBinding
import com.github.andreyasadchy.xtra.databinding.FragmentSettingsHomeBinding
import com.github.andreyasadchy.xtra.databinding.ItemSettingsRowBinding
import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem
import com.github.andreyasadchy.xtra.model.ui.SettingsSearchItem
import com.github.andreyasadchy.xtra.repository.auth.AuthHealth
import com.github.andreyasadchy.xtra.ui.account.AccountActivity
import com.github.andreyasadchy.xtra.ui.common.IntegrityDialog
import com.github.andreyasadchy.xtra.ui.following.FollowingTabs
import com.github.andreyasadchy.xtra.ui.following.overview.FollowingOverviewSections
import com.github.andreyasadchy.xtra.ui.login.LoginActivity
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationScheduler
import com.github.andreyasadchy.xtra.ui.main.LiveNotificationService
import com.github.andreyasadchy.xtra.ui.settings.SettingsViewModel.Companion.SettingsViewModelFactory
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.PlayerControlLayout
import com.github.andreyasadchy.xtra.util.SettingsMigration
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.updater.UpdateError
import com.github.andreyasadchy.xtra.util.updater.UpdateCheckFrequency
import com.github.andreyasadchy.xtra.util.updater.UpdateCheckScheduler
import com.github.andreyasadchy.xtra.util.updater.UpdatePrimaryAction
import com.github.andreyasadchy.xtra.util.updater.UpdateReleaseHistory
import com.github.andreyasadchy.xtra.util.updater.UpdateRetryAction
import com.github.andreyasadchy.xtra.util.updater.UpdateState
import com.github.andreyasadchy.xtra.util.updater.UpdateTimeFormatter
import com.github.andreyasadchy.xtra.util.updater.UpdateVersionDisplay
import com.github.andreyasadchy.xtra.util.updater.downloadableRelease
import com.github.andreyasadchy.xtra.util.updater.errorTitle
import com.github.andreyasadchy.xtra.util.updater.primaryAction
import com.github.andreyasadchy.xtra.util.updater.retryAction
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.chatBadgeSizeOrDefault
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.parseChatBadgeSize
import com.github.andreyasadchy.xtra.util.proxyPrefs
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
import java.util.Collections
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

internal fun serializeSpeedOptions(items: List<SettingsDragListItem>): String =
    items.joinToString(",") { "${it.key}:${if (it.enabled) "1" else "0"}" }

internal fun isSettingsAccountConnected(health: AuthHealth): Boolean =
    health == AuthHealth.HEALTHY || health == AuthHealth.UNKNOWN

internal fun needsUpdateNotificationUserAction(
    permissionMissing: Boolean,
    notificationsBlocked: Boolean,
    updatesChannelBlocked: Boolean,
): Boolean = permissionMissing || notificationsBlocked || updatesChannelBlocked

private const val DISCORD_URL = "https://discord.gg/2cKy8DNgPX"

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var changed = false
    private var accountActionIsLogout = false
    private var loginResultLauncher: ActivityResultLauncher<Intent>? = null
    private var accountResultLauncher: ActivityResultLauncher<Intent>? = null
    var searchItem: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val landscapeChatWidthChanged = SettingsMigration.migrate(this)
        if (landscapeChatWidthChanged || savedInstanceState?.getBoolean(KEY_CHANGED) == true) {
            setResult()
        }
        applyTheme()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        loginResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            accountActionIsLogout = false
            if (result.resultCode == RESULT_OK) {
                setResult(RESULT_OK)
                finish()
            }
        }
        accountResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) recreate()
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
        return isSettingsAccountConnected(
            (application as XtraApp).xtraModule.authSessionMaintainer.authHealth.value,
        )
    }

    fun openAccountAction() {
        val health = (application as XtraApp).xtraModule.authSessionMaintainer.authHealth.value
        accountActionIsLogout = health == AuthHealth.HEALTHY
        loginResultLauncher?.launch(Intent(this, LoginActivity::class.java).apply {
            if (health == AuthHealth.REAUTH_REQUIRED) {
                putExtra(LoginActivity.EXTRA_REAUTHORIZE, true)
            } else {
                putExtra(LoginActivity.EXTRA_LOGOUT, accountActionIsLogout)
            }
        })
    }

    fun showDragListDialog(
        list: List<SettingsDragListItem>,
        prefKey: String,
        title: CharSequence?,
        showDefaultSelector: Boolean = true,
    ) {
        val listAdapter = SettingsDragListAdapter()
        val preview = when (prefKey) {
            C.UI_NAVIGATION_TAB_LIST -> SettingsLayoutPreview(this, list, SettingsLayoutPreview.Mode.NAVIGATION)
            C.UI_FOLLOWING_TABS,
            C.UI_SAVED_TABS,
            C.UI_CHANNEL_TABS,
            C.UI_GAME_TABS,
            C.UI_SEARCH_TABS,
            -> SettingsLayoutPreview(this, list, SettingsLayoutPreview.Mode.TABS)
            C.UI_FOLLOWING_OVERVIEW_SECTIONS -> SettingsLayoutPreview(this, list, SettingsLayoutPreview.Mode.SECTIONS)
            else -> null
        }
        val itemTouchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    Collections.swap(list, viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    listAdapter.notifyItemMoved(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    preview?.refresh()
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
        if (showDefaultSelector) {
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
                preview?.refresh()
            }
        }
        listAdapter.onItemChanged = { preview?.refresh() }
        itemTouchHelper.attachToRecyclerView(recyclerView)
        listAdapter.submitList(list)
        val dialogView = preview?.let { layoutPreview ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    layoutPreview,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        bottomMargin = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            6F,
                            resources.displayMetrics,
                        ).toInt()
                    },
                )
                addView(
                    recyclerView,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (resources.displayMetrics.heightPixels * 0.34f).toInt(),
                    ),
                )
            }
        } ?: recyclerView
        getAlertDialogBuilder()
            .setTitle(title)
            .setView(dialogView)
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
            C.UI_NAVIGATION_TAB_LIST -> mapOf("0" to getString(R.string.browse), "4" to getString(R.string.discover), "1" to getString(R.string.following_overview), "2" to getString(R.string.following), "3" to getString(R.string.saved))
            C.UI_FOLLOWING_TABS -> FollowingTabs.definitions.associate { it.key to getString(it.titleRes) }
            C.UI_SAVED_TABS -> mapOf("0" to getString(R.string.bookmarks), "1" to getString(R.string.downloads), "2" to getString(R.string.filters), "3" to getString(R.string.clips))
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
        private var accountRow: ItemSettingsRowBinding? = null

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
            val discordRow = ItemSettingsRowBinding.inflate(layoutInflater, binding.communityActions, false)
            discordRow.icon.setImageResource(R.drawable.ic_settings_discord)
            discordRow.title.setText(R.string.settings_join_discord)
            discordRow.summary.setText(R.string.settings_join_discord_summary)
            discordRow.root.contentDescription = getString(R.string.settings_join_discord) + ". " +
                    getString(R.string.settings_join_discord_summary)
            discordRow.divider.visibility = View.GONE
            discordRow.root.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, DISCORD_URL.toUri()))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(requireContext(), R.string.no_browser_found, Toast.LENGTH_SHORT).show()
                }
            }
            binding.communityActions.addView(discordRow.root)
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
            accountRow = ItemSettingsRowBinding.inflate(layoutInflater, binding.accountActions, false)
            binding.accountActions.addView(accountRow!!.root)
            renderAccountRow()
            binding.scrollView.post { binding.scrollView.scrollTo(0, 0) }
            ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.updatePadding(bottom = resources.getDimensionPixelSize(R.dimen.settings_section_spacing) * 2 + insets.bottom)
                WindowInsetsCompat.CONSUMED
            }
        }

        override fun onResume() {
            super.onResume()
            renderAccountRow()
        }

        private fun renderAccountRow() {
            val row = accountRow ?: return
            val settingsActivity = requireActivity() as SettingsActivity
            val isLoggedIn = settingsActivity.isAccountConnected()
            val username = requireContext().tokenPrefs().getString(C.USERNAME, null)?.takeIf { it.isNotBlank() }
            val accountSummary = if (isLoggedIn) getString(R.string.settings_account_connected_summary)
            else getString(R.string.settings_account_signed_out_summary)
            row.icon.setImageResource(R.drawable.ic_settings_network)
            row.title.text = if (isLoggedIn) username ?: getString(R.string.settings_account_details)
            else getString(R.string.settings_account_connected_summary)
            row.summary.text = accountSummary
            row.arrow.visibility = if (isLoggedIn) View.VISIBLE else View.GONE
            row.divider.visibility = View.GONE
            row.root.contentDescription = row.title.text.toString() + ". " + accountSummary
            row.root.setOnClickListener {
                if (settingsActivity.isAccountConnected()) {
                    settingsActivity.accountResultLauncher?.launch(Intent(requireContext(), AccountActivity::class.java))
                } else {
                    settingsActivity.openAccountAction()
                }
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
            accountRow = null
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
                    viewModel.reportLiveNotificationPermissionDenied()
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
            if (settingsScreen == SCREEN_PROXY) {
                // The data store must be installed before inflation. Preference only
                // reads its initial value while it is being attached.
                preferenceManager.preferenceDataStore = createProxyPreferenceDataStore()
            }
            setPreferencesFromResource(
                when (settingsScreen) {
                    SCREEN_LIVE_NOTIFICATIONS -> R.xml.live_notification_preferences
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
            configureRedesignedPreferences()
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
            append(liveNotificationDiagnostics(requireContext()))
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
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
            if (settingsScreen == SCREEN_PROXY || settingsScreen == SCREEN_DEVELOPER_API) {
                requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            val preference = findPreference<SwitchPreferenceCompat>("live_notifications_enabled")
            if (preference?.isChecked == true && !LiveNotificationScheduler.canPostNotifications(requireContext())) {
                preference.isChecked = false
                toggleLiveNotifications(false)
            }
            updateLiveNotificationsSummary()
        }

        override fun onPause() {
            if (settingsScreen == SCREEN_PROXY || settingsScreen == SCREEN_DEVELOPER_API) {
                requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            super.onPause()
        }

        private companion object {
            const val ARG_SETTINGS_SCREEN = "settings_screen"
            const val SCREEN_LIVE_NOTIFICATIONS = "live_notifications"
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
        }
    }

    class UpdateSettingsFragment : Fragment() {

        private var _binding: FragmentUpdateSettingsBinding? = null
        private val binding get() = _binding!!
        private var technicalDetailsExpanded = false
        private lateinit var updateNotificationPermissionLauncher: ActivityResultLauncher<String>
        private val repository
            get() = (requireContext().applicationContext as XtraApp).xtraModule.updateRepository

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            updateNotificationPermissionLauncher = registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {
                if (_binding != null) renderUpdateNotificationPermission()
            }
        }

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            _binding = FragmentUpdateSettingsBinding.inflate(inflater, container, false)
            return binding.root
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            val preferences = requireContext().prefs()
            val frequencies = UpdateCheckFrequency.entries
            val selectedFrequency = UpdateCheckFrequency.fromPreference(
                preferences.getString(C.UPDATE_CHECK_FREQUENCY, null),
            )
            preferences.edit { putString(C.UPDATE_CHECK_FREQUENCY, selectedFrequency.preferenceValue) }
            binding.frequencyInput.setSimpleItems(frequencies.map { getString(it.labelRes) }.toTypedArray())
            binding.frequencyInput.setText(getString(selectedFrequency.labelRes), false)
            binding.frequencySummary.text = getString(
                R.string.update_check_frequency_description,
                getString(selectedFrequency.labelRes),
            )
            fun renderAutomaticSettings(enabled: Boolean) {
                binding.frequencyInputLayout.isEnabled = enabled
                binding.frequencyInput.isEnabled = enabled
            }
            val automaticChecksEnabled = preferences.getBoolean(C.UPDATE_CHECK_ENABLED, true)
            binding.automaticCheck.isChecked = automaticChecksEnabled
            renderAutomaticSettings(automaticChecksEnabled)
            binding.notificationPermissionButton.setOnClickListener {
                requestUpdateNotificationPermission()
            }
            renderUpdateNotificationPermission()
            binding.automaticCheck.setOnCheckedChangeListener { _, enabled ->
                preferences.edit { putBoolean(C.UPDATE_CHECK_ENABLED, enabled) }
                renderAutomaticSettings(enabled)
                renderUpdateNotificationPermission()
                if (enabled && updateNotificationsNeedUserAction()) requestUpdateNotificationPermission()
                UpdateCheckScheduler.schedule(requireContext())
            }
            binding.frequencyInput.setOnItemClickListener { _, _, position, _ ->
                val frequency = frequencies[position]
                preferences.edit { putString(C.UPDATE_CHECK_FREQUENCY, frequency.preferenceValue) }
                binding.frequencySummary.text = getString(
                    R.string.update_check_frequency_description,
                    getString(frequency.labelRes),
                )
                UpdateCheckScheduler.schedule(requireContext())
            }
            binding.checkButton.setOnClickListener {
                repository.check(preferences.getString(C.NETWORK_LIBRARY, C.OKHTTP), C.DEFAULT_UPDATE_URL)
            }
            binding.downloadButton.setOnClickListener {
                repository.downloadCurrent()
            }
            binding.installButton.setOnClickListener {
                val state = repository.state.value
                if (state is UpdateState.AwaitingUserAction) {
                    repository.launchPendingInstall()
                } else if (state is UpdateState.Error && state.cause == UpdateError.InstallPermissionDenied &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    !requireContext().packageManager.canRequestPackageInstalls()
                ) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${requireContext().packageName}".toUri()))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(requireContext(), R.string.update_error_install, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    repository.refreshInstallPermission()
                    repository.install()
                }
            }
            binding.cancelButton.setOnClickListener { repository.cancelDownload() }
            binding.retryButton.setOnClickListener { repository.retry() }
            binding.skipButton.setOnClickListener {
                (repository.state.value as? UpdateState.Available)?.release?.let(repository::skip)
            }
            binding.notNowButton.setOnClickListener {
                (repository.state.value as? UpdateState.Available)?.release?.let(repository::defer)
            }
            binding.undoSkipButton.setOnClickListener { repository.undoSkip() }
            binding.technicalDetailsToggle.setOnClickListener {
                technicalDetailsExpanded = !technicalDetailsExpanded
                render(repository.state.value)
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch { repository.state.collectLatest(::render) }
                    launch { repository.releaseHistory.collectLatest { render(repository.state.value) } }
                    launch { repository.releaseHistoryComplete.collectLatest { render(repository.state.value) } }
                }
            }
        }

        override fun onResume() {
            super.onResume()
            repository.refreshInstallPermission()
            repository.resumePendingInstall()
            if (_binding != null) renderUpdateNotificationPermission()
        }

        private fun renderUpdateNotificationPermission() {
            val automaticChecksEnabled = requireContext().prefs().getBoolean(C.UPDATE_CHECK_ENABLED, true)
            val permissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            val needsUserAction = updateNotificationsNeedUserAction()
            binding.notificationPermissionButton.visibility = if (automaticChecksEnabled && needsUserAction) {
                View.VISIBLE
            } else {
                View.GONE
            }
            binding.notificationPermissionButton.text = getString(
                if (permissionMissing) {
                    R.string.update_notifications_enable
                } else {
                    R.string.update_notifications_open_settings
                },
            )
        }

        private fun updateNotificationsNeedUserAction(): Boolean {
            val permissionMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            val notificationsBlocked = !NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
            return needsUpdateNotificationUserAction(
                permissionMissing = permissionMissing,
                notificationsBlocked = notificationsBlocked,
                updatesChannelBlocked = isUpdatesNotificationChannelBlocked(),
            )
        }

        private fun requestUpdateNotificationPermission() {
            if (!updateNotificationsNeedUserAction()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                if (ActivityCompat.shouldShowRequestPermissionRationale(requireActivity(), Manifest.permission.POST_NOTIFICATIONS)) {
                    openUpdateNotificationSettings()
                } else {
                    updateNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                openUpdateNotificationSettings()
            }
        }

        private fun openUpdateNotificationSettings() {
            val notificationsBlocked = !NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()
            val intent = if (!notificationsBlocked && isUpdatesNotificationChannelBlocked()) {
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, getString(R.string.notification_updates_channel_id))
            } else {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
            }
            runCatching {
                startActivity(intent)
            }
        }

        private fun isUpdatesNotificationChannelBlocked(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            val notificationManager = requireContext().getSystemService(NotificationManager::class.java) ?: return false
            return notificationManager.getNotificationChannel(getString(R.string.notification_updates_channel_id))?.importance ==
                NotificationManager.IMPORTANCE_NONE
        }

        private fun render(state: UpdateState) {
            val release = when (state) {
                is UpdateState.Available -> state.release
                is UpdateState.Skipped -> state.release
                is UpdateState.Deferred -> state.release
                is UpdateState.Downloading -> state.release
                is UpdateState.Downloaded -> state.release
                is UpdateState.Installing -> state.release
                is UpdateState.AwaitingUserAction -> state.release
                is UpdateState.Error -> state.release
                else -> null
            }
            val recentRelease = when (state) {
                is UpdateState.UpToDate -> state.release
                else -> release
            }
            binding.statusTitle.text = when (state) {
                UpdateState.Idle -> getString(R.string.settings_updates)
                UpdateState.Checking -> getString(R.string.update_checking)
                is UpdateState.UpToDate -> getString(R.string.update_up_to_date)
                is UpdateState.Available, is UpdateState.Skipped, is UpdateState.Deferred -> getString(R.string.update_available)
                is UpdateState.Downloading -> getString(R.string.downloading_update)
                is UpdateState.Downloaded -> getString(R.string.update_ready_to_install, state.release.displayVersion)
                is UpdateState.Installing -> getString(R.string.update_installing)
                is UpdateState.AwaitingUserAction -> getString(R.string.update_awaiting_user_action)
                is UpdateState.Error -> errorTitle(state.stage)
            }
            binding.versionText.text = getString(
                R.string.update_version,
                release?.displayVersion ?: UpdateVersionDisplay.installed(
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE.toLong(),
                    BuildConfig.CI_VERSION_CODE_BASE.toLong(),
                ),
            )
            binding.currentVersionText.text = if (release != null && state !is UpdateState.UpToDate) {
                getString(
                    R.string.update_current_version,
                    UpdateVersionDisplay.installed(
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE.toLong(),
                        BuildConfig.CI_VERSION_CODE_BASE.toLong(),
                    ),
                )
            } else ""
            val showReleaseDetails = release != null && state !is UpdateState.UpToDate
            binding.notesTitle.visibility = if (showReleaseDetails) View.VISIBLE else View.GONE
            binding.notesText.visibility = if (showReleaseDetails) View.VISIBLE else View.GONE
            binding.notesText.text = if (release != null && state !is UpdateState.UpToDate) {
                UpdateReleaseHistory.formatForUpdate(
                    historyComplete = repository.releaseHistoryComplete.value,
                    cumulativeReleases = repository.releasesSinceInstalled(release),
                    latestRelease = release,
                    noReleaseNotes = getString(R.string.update_no_release_notes),
                    incompleteHistoryMessage = getString(R.string.update_history_incomplete),
                )
            } else {
                ""
            }
            binding.statusMessage.text = when (state) {
                UpdateState.Checking -> getString(R.string.update_checking)
                is UpdateState.Available -> when {
                    state.previouslySkipped -> getString(R.string.update_previously_skipped)
                    state.previouslyDeferred -> getString(R.string.update_deferred)
                    else -> ""
                }
                is UpdateState.Skipped -> getString(R.string.update_previously_skipped)
                is UpdateState.Deferred -> getString(R.string.update_deferred)
                is UpdateState.Downloading -> state.progress?.let { progress ->
                    val total = progress.totalBytes
                    if (total != null && total > 0L) {
                        getString(
                            R.string.update_downloaded_progress,
                            "${Formatter.formatFileSize(requireContext(), progress.downloadedBytes)} / ${Formatter.formatFileSize(requireContext(), total)} (${progress.percent}%)",
                        )
                    } else {
                        getString(R.string.downloading_update)
                    }
                }.orEmpty()
                is UpdateState.Installing -> getString(R.string.update_installing)
                is UpdateState.AwaitingUserAction -> getString(R.string.update_awaiting_user_action)
                is UpdateState.Error -> errorMessage(state.cause)
                else -> ""
            }
            binding.checkButton.isEnabled = state !is UpdateState.Checking &&
                state !is UpdateState.Downloading &&
                state !is UpdateState.Installing &&
                state !is UpdateState.AwaitingUserAction
            binding.checkButton.text = getString(if (state is UpdateState.Checking) R.string.update_checking else R.string.check_for_updates)
            binding.downloadButton.visibility = if (state.downloadableRelease() != null) View.VISIBLE else View.GONE
            binding.installButton.visibility = if (state is UpdateState.Downloaded ||
                state is UpdateState.AwaitingUserAction ||
                state is UpdateState.Error && state.primaryAction() in setOf(
                    UpdatePrimaryAction.INSTALL,
                    UpdatePrimaryAction.ALLOW_INSTALL,
                )
            ) View.VISIBLE else View.GONE
            binding.installButton.text = when {
                state is UpdateState.Error && state.primaryAction() == UpdatePrimaryAction.ALLOW_INSTALL ->
                    getString(R.string.allow_install)
                state is UpdateState.AwaitingUserAction -> getString(R.string.continue_install)
                state is UpdateState.Error && state.primaryAction() == UpdatePrimaryAction.INSTALL ->
                    getString(R.string.retry_install)
                else -> getString(R.string.install_update)
            }
            binding.cancelButton.visibility = if (state is UpdateState.Downloading) View.VISIBLE else View.GONE
            binding.retryButton.visibility = if (state is UpdateState.Error &&
                state.retryable && state.retryAction() != UpdateRetryAction.INSTALL
            ) View.VISIBLE else View.GONE
            binding.skipButton.visibility = if (state is UpdateState.Available && !state.previouslySkipped) View.VISIBLE else View.GONE
            binding.notNowButton.visibility = if (state is UpdateState.Available && !state.previouslySkipped) View.VISIBLE else View.GONE
            binding.undoSkipButton.visibility = if (state is UpdateState.Skipped ||
                state is UpdateState.Available && state.previouslySkipped
            ) View.VISIBLE else View.GONE
            binding.lastCheckedText.text = getString(
                R.string.last_successful_update_check,
                UpdateTimeFormatter.format(requireContext(), repository.lastSuccessfulCheck),
            )
            binding.technicalDetailsToggle.visibility = if (showReleaseDetails) View.VISIBLE else View.GONE
            binding.technicalDetails.visibility = if (showReleaseDetails && technicalDetailsExpanded) View.VISIBLE else View.GONE
            binding.technicalDetailsToggle.text = getString(
                if (technicalDetailsExpanded) R.string.hide_technical_details else R.string.technical_details,
            )
            binding.technicalDetails.text = release?.let {
                buildString {
                    appendLine(getString(R.string.technical_details))
                    appendLine(getString(R.string.update_tag, it.tagName))
                    it.buildNumber?.let { buildNumber -> appendLine(getString(R.string.update_build_number, buildNumber)) }
                    appendLine(getString(R.string.update_release, it.releaseUrl))
                    it.publishedAt?.let { timestamp -> appendLine(getString(R.string.update_published, timestamp)) }
                    if (it.rawBody.isNotBlank()) {
                        appendLine()
                        appendLine(it.rawBody)
                    }
                }
            }.orEmpty()
            val recentReleases = repository.recentReleases(recentRelease)
            binding.recentUpdatesCard.visibility = if (recentReleases.isEmpty()) View.GONE else View.VISIBLE
            binding.recentUpdatesText.text = UpdateReleaseHistory.formatGrouped(
                recentReleases,
                getString(R.string.update_no_release_notes),
            )
        }

        private fun errorTitle(stage: com.github.andreyasadchy.xtra.util.updater.UpdateStage): String = getString(
            when (stage.errorTitle()) {
                com.github.andreyasadchy.xtra.util.updater.UpdateErrorTitle.CHECK -> R.string.update_check_failed
                com.github.andreyasadchy.xtra.util.updater.UpdateErrorTitle.PARSE -> R.string.update_parse_failed
                com.github.andreyasadchy.xtra.util.updater.UpdateErrorTitle.ASSET_SELECTION -> R.string.update_asset_selection_failed
                com.github.andreyasadchy.xtra.util.updater.UpdateErrorTitle.DOWNLOAD -> R.string.update_download_failed_title
                com.github.andreyasadchy.xtra.util.updater.UpdateErrorTitle.INSTALL -> R.string.update_install_failed_title
            },
        )

        private fun errorMessage(error: UpdateError): String = getString(
            when (error) {
                UpdateError.NoConnection -> R.string.update_error_no_connection
                UpdateError.Timeout -> R.string.update_error_timeout
                UpdateError.RateLimited -> R.string.update_error_rate_limited
                UpdateError.NotFound -> R.string.update_error_not_found
                UpdateError.Server -> R.string.update_error_server
                UpdateError.InvalidResponse, UpdateError.UnexpectedResponse -> R.string.update_error_invalid_response
                UpdateError.MissingApk -> R.string.update_error_missing_apk
                UpdateError.AmbiguousApk -> R.string.update_error_ambiguous_apk
                UpdateError.IncompatibleApk -> R.string.update_error_incompatible_apk
                UpdateError.DownloadFailed, UpdateError.DownloadCancelled, UpdateError.DownloadedFileMissing -> R.string.update_error_download
                UpdateError.InstallPermissionDenied, UpdateError.InstallCancelled, UpdateError.InstallFailed -> R.string.update_error_install
            }
        )

        override fun onDestroyView() {
            _binding = null
            super.onDestroyView()
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
            findPreference<ListPreference>(C.SETTINGS_FONT_FAMILY)?.onPreferenceChangeListener = changeListener
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
                            "0" -> getString(R.string.browse)
                            "4" -> getString(R.string.discover)
                            "1" -> getString(R.string.following_overview)
                            "2" -> getString(R.string.following)
                            "3" -> getString(R.string.saved)
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
            findPreference<Preference>("clip_settings")?.setOnPreferenceClickListener { findNavController().navigate(R.id.clipSettingsFragment); true }
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
            val items = PlayerControlLayout.controlPlacements(
                preferences.getString(C.SETTINGS_PLAYER_CONTROL_LAYOUT, null),
                SettingsMigration.defaultControlLayout(),
            )
            val editor = PlayerControlLayoutEditor(
                context = requireContext(),
                initialItems = items,
                labelFor = ::controlTitle,
            )
            requireActivity().getAlertDialogBuilder()
                .setTitle(R.string.settings_customize_controls)
                .setView(editor)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val serialized = editor.serializedLayout()
                    preferences.edit { putString(C.SETTINGS_PLAYER_CONTROL_LAYOUT, serialized) }
                    SettingsMigration.syncLegacyControlVisibility(preferences, serialized)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun controlTitle(action: String): String = when (action) {
            "minimize" -> getString(R.string.player_minimize)
            "download" -> getString(R.string.player_download)
            "follow" -> getString(R.string.player_follow)
            "quality" -> getString(R.string.player_quality)
            "speed" -> getString(R.string.player_playback_speed)
            "chapters" -> getString(R.string.player_vod_games)
            "restart" -> getString(R.string.player_restart)
            "live" -> getString(R.string.player_seek_live)
            "clip" -> getString(R.string.player_clip)
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
            append(liveNotificationDiagnostics(requireContext()))
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
                    Triple(R.xml.update_search_preferences, SettingsNavGraphDirections.actionGlobalUpdateSettingsFragment(), getString(R.string.settings_general_updates)),
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
                    Triple(R.xml.clip_preferences, SettingsNavDirections(R.id.clipSettingsFragment), "Player controls › Local clips"),
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
