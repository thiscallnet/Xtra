package com.github.andreyasadchy.xtra.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupWithNavController
import androidx.preference.forEach
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.SettingsNavGraphDirections
import com.github.andreyasadchy.xtra.databinding.ActivitySettingsBinding
import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem
import com.github.andreyasadchy.xtra.repository.auth.AuthHealth
import com.github.andreyasadchy.xtra.ui.appearance.ActivityBackgroundController
import com.github.andreyasadchy.xtra.ui.appearance.AppearanceRepository
import com.github.andreyasadchy.xtra.ui.appearance.makeBackdropAwareChrome
import com.github.andreyasadchy.xtra.ui.following.FollowingTabs
import com.github.andreyasadchy.xtra.ui.login.TwitchWebLoginActivity
import com.github.andreyasadchy.xtra.util.isTelevision
import com.github.andreyasadchy.xtra.ui.search.SearchTabs
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.SettingsMigration
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.appbar.AppBarLayout
import kotlinx.coroutines.launch
import java.util.Collections

internal fun serializeSpeedOptions(items: List<SettingsDragListItem>): String =
    items.joinToString(",") { "${it.key}:${if (it.enabled) "1" else "0"}" }

internal fun isSettingsAccountConnected(health: AuthHealth): Boolean =
    health == AuthHealth.HEALTHY ||
        health == AuthHealth.UNKNOWN

internal fun needsUpdateNotificationUserAction(
    permissionMissing: Boolean,
    notificationsBlocked: Boolean,
    updatesChannelBlocked: Boolean,
): Boolean = permissionMissing || notificationsBlocked || updatesChannelBlocked

private const val DISCORD_URL = "https://discord.gg/2cKy8DNgPX"

class SettingsActivity : AppCompatActivity() {

    private var settingsChromeVisible = true
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var appBackgroundController: ActivityBackgroundController
    internal var changed = false
    private var hudChanged = false
    private var accountActionIsLogout = false
    private var loginResultLauncher: ActivityResultLauncher<Intent>? = null
    internal var accountResultLauncher: ActivityResultLauncher<Intent>? = null
    private var settingsHighlightPreference: String? = null
    var searchItem: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val landscapeChatWidthChanged = SettingsMigration.migrate(this)
        changed = savedInstanceState?.getBoolean(KEY_CHANGED) == true
        hudChanged = savedInstanceState?.getBoolean(KEY_HUD_CHANGED) == true
        if (landscapeChatWidthChanged) {
            changed = true
        }
        if (changed || hudChanged) {
            updateActivityResult()
        }
        applyTheme()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySettingsContentWidth()
        appBackgroundController = ActivityBackgroundController(
            root = binding.root,
            image = binding.appBackgroundImage,
            scrim = binding.appBackgroundScrim,
            repository = AppearanceRepository(this),
        )
        makeBackdropAwareChrome(binding.root)
        if (isTelevision()) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        loginResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            accountActionIsLogout = false
            if (result.resultCode == RESULT_OK) {
                changed = true
                updateActivityResult()
                finish()
            }
        }
        accountResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                setResult()
                recreate()
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
                leftMargin = if (settingsChromeVisible) cutoutInsets.left else 0
                rightMargin = if (settingsChromeVisible) cutoutInsets.right else 0
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
        settingsHighlightPreference = intent.getStringExtra(EXTRA_SETTINGS_HIGHLIGHT_PREFERENCE)
        if (savedInstanceState == null) {
            when (intent.getStringExtra(EXTRA_SETTINGS_SCREEN)) {
                SETTINGS_SCREEN_TABS -> navController.navigate(R.id.browsingTabsFragment)
                SETTINGS_SCREEN_PLAYER_CONTROLS -> navController.navigate(R.id.playerButtonSettingsFragment)
                SETTINGS_SCREEN_PLAYER_HUD -> navController.navigate(R.id.playerHudEditorFragment)
                SETTINGS_SCREEN_PLAYER -> navController.navigate(R.id.playerSettingsFragment)
                SETTINGS_SCREEN_LIVE_CAPTIONS -> navController.navigate(R.id.liveCaptionsFragment)
                SETTINGS_SCREEN_CHAT -> navController.navigate(R.id.chatSettingsFragment)
            }
        }
        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                (supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0) as? SettingsSearchFragment)?.search(query)
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                (supportFragmentManager.findFragmentById(R.id.navHostFragment)?.childFragmentManager?.fragments?.getOrNull(0) as? SettingsSearchFragment)?.search(newText)
                return false
            }
        })
    }

    private fun applySettingsContentWidth() {
        val maxWidthDp = 840
        val contentWidth = if (settingsChromeVisible && !isTelevision() && resources.configuration.screenWidthDp >= maxWidthDp) {
            (maxWidthDp * resources.displayMetrics.density).toInt()
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        binding.navHostFragment.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            width = contentWidth
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
    }

    override fun onStart() {
        super.onStart()
        if (::appBackgroundController.isInitialized) appBackgroundController.start()
    }

    override fun onStop() {
        appBackgroundController.stop()
        super.onStop()
    }

    internal fun consumeSettingsHighlightPreference(): String? {
        return settingsHighlightPreference?.also { settingsHighlightPreference = null }
    }

    internal fun setSettingsChromeVisible(visible: Boolean) {
        if (!::binding.isInitialized) return
        settingsChromeVisible = visible
        applySettingsContentWidth()
        binding.appBar.isVisible = visible
        ViewCompat.requestApplyInsets(binding.root)
        // The editor supplies its own app bar. Remove the scrolling behavior
        // while the settings chrome is hidden so the editor really occupies
        // the full window instead of retaining an invisible 88dp top inset.
        binding.navHostFragment.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            behavior = if (visible) AppBarLayout.ScrollingViewBehavior() else null
        }
        binding.navHostFragment.requestLayout()
    }

    fun isAccountConnected(): Boolean {
        return isSettingsAccountConnected(accountAuthHealth())
    }

    fun accountAuthHealth(): AuthHealth =
        (application as XtraApp).xtraModule.authSessionMaintainer.authHealth.value

    fun openAccountAction() {
        val health = (application as XtraApp).xtraModule.authSessionMaintainer.authHealth.value
        accountActionIsLogout = isSettingsAccountConnected(health)
        loginResultLauncher?.launch(Intent(this, TwitchWebLoginActivity::class.java).apply {
            if (health == AuthHealth.REAUTH_REQUIRED) {
                putExtra(TwitchWebLoginActivity.EXTRA_REAUTHORIZE, true)
            } else {
                putExtra(TwitchWebLoginActivity.EXTRA_LOGOUT, accountActionIsLogout)
            }
        })
    }

    fun openDiscord() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, DISCORD_URL.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_browser_found, Toast.LENGTH_SHORT).show()
        }
    }

    fun showDragListDialog(
        list: List<SettingsDragListItem>,
        prefKey: String,
        title: CharSequence?,
        showDefaultSelector: Boolean = true,
    ) {
        val listAdapter = SettingsDragListAdapter()
        listAdapter.minimumVisibleItems = minimumVisibleItemsForPreference(prefKey)
        ensureMinimumVisibleItems(list, listAdapter.minimumVisibleItems)
        if (showDefaultSelector) promoteDefaultToVisible(list)
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
                setDefaultItem(list, item)
                listAdapter.notifyDataSetChanged()
                preview?.refresh()
            }
        }
        listAdapter.onItemChanged = {
            ensureMinimumVisibleItems(list, listAdapter.minimumVisibleItems)
            if (showDefaultSelector) promoteDefaultToVisible(list)
            listAdapter.notifyDataSetChanged()
            preview?.refresh()
        }
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
                ensureMinimumVisibleItems(list, listAdapter.minimumVisibleItems)
                if (showDefaultSelector) promoteDefaultToVisible(list)
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

    internal fun showSearchView(showSearch: Boolean) {
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
            C.UI_NAVIGATION_TAB_LIST -> navigationTabDefaults(isTelevision())
            C.UI_FOLLOWING_TABS -> C.DEFAULT_FOLLOWING_TABS
            C.UI_SAVED_TABS -> C.DEFAULT_SAVED_TABS
            C.UI_CHANNEL_TABS -> C.DEFAULT_CHANNEL_TABS
            C.UI_GAME_TABS -> C.DEFAULT_GAME_TABS
            else -> C.DEFAULT_SEARCH_TABS
        }
        val labels = when (prefKey) {
            C.UI_NAVIGATION_TAB_LIST -> mapOf("0" to getString(R.string.browse), "4" to getString(R.string.discover), "1" to getString(R.string.following_overview), "2" to getString(R.string.following), "3" to getString(R.string.saved), "5" to getString(R.string.statistics), "6" to getString(R.string.drops))
            C.UI_FOLLOWING_TABS -> FollowingTabs.definitions.associate { it.key to getString(it.titleRes) }
            C.UI_SAVED_TABS -> mapOf("0" to getString(R.string.bookmarks), "1" to getString(R.string.downloads), "2" to getString(R.string.filters), "3" to getString(R.string.clips))
            C.UI_CHANNEL_TABS -> mapOf("0" to getString(R.string.suggestions), "1" to getString(R.string.videos), "2" to getString(R.string.clips), "3" to getString(R.string.chat), "4" to getString(R.string.about))
            C.UI_GAME_TABS -> mapOf("0" to getString(R.string.videos), "1" to getString(R.string.live), "2" to getString(R.string.clips))
            else -> mapOf("4" to getString(R.string.search_all), "0" to getString(R.string.videos), "1" to getString(R.string.streams), "2" to getString(R.string.channels), "3" to getString(R.string.games))
        }
        val stored = if (prefKey == C.UI_SEARCH_TABS) {
            SearchTabs.resolve(prefs().getString(prefKey, null)).joinToString(",")
        } else {
            prefs().getString(prefKey, null)
        }
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

    internal fun setResult() {
        changed = true
        updateActivityResult()
    }

    internal fun setHudResult() {
        hudChanged = true
        updateActivityResult()
    }

    private fun updateActivityResult() {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_HUD_CHANGED, hudChanged && !changed))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_CHANGED, changed)
        outState.putBoolean(KEY_HUD_CHANGED, hudChanged)
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val KEY_CHANGED = "changed"
        const val KEY_HUD_CHANGED = "hud_changed"
        const val EXTRA_HUD_CHANGED = "hud_changed"
    }
}
