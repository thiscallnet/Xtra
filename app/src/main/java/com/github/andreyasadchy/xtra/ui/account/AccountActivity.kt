package com.github.andreyasadchy.xtra.ui.account

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.ActivityAccountBinding
import com.github.andreyasadchy.xtra.databinding.ItemAccountBlockedUserBinding
import com.github.andreyasadchy.xtra.databinding.ItemAccountSettingBinding
import com.github.andreyasadchy.xtra.model.helix.game.Game
import com.github.andreyasadchy.xtra.model.helix.user.BlockedUser
import com.github.andreyasadchy.xtra.model.helix.user.User
import com.github.andreyasadchy.xtra.repository.auth.AuthHealth
import com.github.andreyasadchy.xtra.ui.login.TwitchWebLoginActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class AccountActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccountBinding
    private val viewModel: AccountViewModel by viewModels()
    private val authSessionMaintainer by lazy { (application as XtraApp).xtraModule.authSessionMaintainer }
    private var page = PAGE_MAIN
    private var categoryDialog: androidx.appcompat.app.AlertDialog? = null
    private var categoryResultsContainer: LinearLayout? = null
    private var lastActionMessage: String? = null
    private var lastActionError: String? = null
    private var blockedUsersRequested = false
    private var blockedUsersQuery = ""
    private var logoutPending = false
    private var authHealth = AuthHealth.UNKNOWN

    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (logoutPending) {
            logoutPending = false
            if (result.resultCode == RESULT_OK) {
                setResult(RESULT_OK)
                finish()
            } else {
                viewModel.refresh()
            }
            return@registerForActivityResult
        }
        if (result.resultCode == RESULT_OK) {
            blockedUsersRequested = false
            viewModel.refresh()
        } else {
            viewModel.refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = intent.getStringExtra(EXTRA_PAGE) ?: PAGE_MAIN
        applyTheme()
        binding = ActivityAccountBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets()
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.viewChannel.setOnClickListener { openOwnProfile() }
        binding.blockedUsersSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                blockedUsersQuery = s?.toString().orEmpty()
                renderBlockedUsers(viewModel.uiState.value)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        configurePage()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collectLatest(::render)
                }
                launch {
                    viewModel.categoryResults.collectLatest(::renderCategoryResults)
                }
                launch {
                    authSessionMaintainer.authHealth.collectLatest { health ->
                        authHealth = health
                        render(viewModel.uiState.value)
                    }
                }
            }
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.toolbar.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                topMargin = insets.top
            }
            binding.scrollView.updatePadding(bottom = 16.dp() + insets.bottom)
            windowInsets
        }
    }

    private fun configurePage() {
        when (page) {
            PAGE_CHAT_SETTINGS -> {
                binding.toolbar.setTitle(R.string.account_chat_settings_title)
                binding.headerCard.isGone = true
                binding.errorText.isGone = true
                binding.accountMain.isGone = true
                binding.chatSettingsPage.isVisible = true
                binding.blockedUsersPage.isGone = true
            }
            PAGE_BLOCKED_USERS -> {
                binding.toolbar.setTitle(R.string.account_blocked_users_title)
                binding.headerCard.isGone = true
                binding.errorText.isGone = true
                binding.accountMain.isGone = true
                binding.chatSettingsPage.isGone = true
                binding.blockedUsersPage.isVisible = true
            }
            else -> {
                binding.toolbar.setTitle(R.string.account_hub_title)
                binding.headerCard.isVisible = true
                binding.accountMain.isVisible = true
                binding.chatSettingsPage.isGone = true
                binding.blockedUsersPage.isGone = true
            }
        }
    }

    private fun render(state: AccountUiState) {
        renderHeader(state.user)
        renderAuthHealth(state)
        binding.progressBar.isVisible = state.loading && state.user == null
        binding.errorText.isVisible = page == PAGE_MAIN && !state.error.isNullOrBlank()
        binding.errorText.text = state.error

        when (page) {
            PAGE_CHAT_SETTINGS -> renderChatSettings(state)
            PAGE_BLOCKED_USERS -> renderBlockedUsers(state)
            else -> renderMain(state)
        }

        if (state.actionMessage == null) lastActionMessage = null
        if (state.actionError == null) lastActionError = null
        if (state.actionMessage != null && state.actionMessage != lastActionMessage) {
            lastActionMessage = state.actionMessage
            Toast.makeText(this, state.actionMessage, Toast.LENGTH_SHORT).show()
            viewModel.consumeActionMessage()
        }
        if (state.actionError != null && state.actionError != lastActionError) {
            lastActionError = state.actionError
            Toast.makeText(this, state.actionError, Toast.LENGTH_LONG).show()
            viewModel.consumeActionMessage()
        }

        if (page == PAGE_BLOCKED_USERS && !state.loading && !blockedUsersRequested) {
            blockedUsersRequested = true
            viewModel.loadBlockedUsers(reset = true)
        }
    }

    private fun renderAuthHealth(state: AccountUiState) {
        val spec = when {
            authHealth == AuthHealth.REAUTH_REQUIRED -> AuthHealthCardSpec(
                title = R.string.auth_health_reauth_title,
                message = R.string.auth_health_reauth_message,
                action = R.string.auth_health_reconnect,
                reauthorize = true,
            )
            state.webSession && page == PAGE_MAIN -> AuthHealthCardSpec(
                title = R.string.account_web_session_title,
                message = R.string.account_web_session_message,
                action = R.string.account_web_session_action,
                reauthorize = false,
            )
            else -> null
        }
        binding.authHealthCard.isVisible = page == PAGE_MAIN && spec != null
        if (page != PAGE_MAIN || spec == null) return
        binding.authHealthTitle.setText(spec.title)
        binding.authHealthMessage.setText(spec.message)
        binding.authHealthAction.setText(spec.action)
        binding.authHealthAction.setOnClickListener {
            if (spec.reauthorize) {
                loginLauncher.launch(
                    Intent(this, TwitchWebLoginActivity::class.java)
                        .putExtra(TwitchWebLoginActivity.EXTRA_REAUTHORIZE, true),
                )
            } else if (state.webSession) {
                showPermissions(state.scopes, webSession = true)
            } else {
                openManageOnTwitch()
            }
        }
    }

    private fun renderHeader(user: User?) {
        val login = user?.login?.takeIf { it.isNotBlank() }
            ?: tokenPrefs().getString(C.USERNAME, null)
        val displayName = user?.displayName?.takeIf { it.isNotBlank() } ?: login
        binding.displayName.text = displayName ?: getString(R.string.account_hub_title)
        binding.login.text = login?.let { "@$it" }.orEmpty()
        binding.broadcasterBadge.apply {
            val badge = when (user?.broadcasterType?.lowercase()) {
                "affiliate" -> getString(R.string.account_affiliate)
                "partner" -> getString(R.string.account_partner)
                else -> null
            }
            text = badge
            isVisible = badge != null
        }
        binding.viewChannel.isEnabled = user?.id != null || !login.isNullOrBlank()
        val imageUrl = TwitchApiHelper.getProfileImage(user?.profileImageURL)
            ?: tokenPrefs().getString(C.PROFILE_IMAGE_URL, null)
        if (imageUrl.isNullOrBlank()) {
            binding.avatar.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.baseline_person_black_24))
        } else {
            imageLoader.enqueue(
                ImageRequest.Builder(this)
                    .data(imageUrl)
                    .transformations(CircleCropTransformation())
                    .target(binding.avatar)
                    .build(),
            )
        }
    }

    private fun renderMain(state: AccountUiState) {
        binding.profileRows.removeAllViews()
        binding.channelRows.removeAllViews()
        binding.privacyRows.removeAllViews()
        binding.accountRows.removeAllViews()

        val canEditBio = state.capabilities.editBio
        addSettingRow(
            binding.profileRows,
            getString(R.string.account_bio),
            state.user?.description?.takeIf { it.isNotBlank() } ?: getString(R.string.account_not_set),
            onClick = if (canEditBio) ({ showBioEditor(state.user?.description.orEmpty()) }) else ({ reconnectFor(R.string.account_bio) }),
        )
        addSettingRow(
            binding.profileRows,
            getString(R.string.account_chat_color),
            when {
                !state.capabilities.editChatColor -> getString(R.string.account_reconnect_to_enable)
                state.chatColorLoadError != null -> getString(R.string.account_load_failed)
                else -> state.chatColor ?: getString(R.string.account_not_set)
            },
            color = state.chatColor,
            onClick = when {
                !state.capabilities.editChatColor -> ({ reconnectFor(R.string.account_chat_color) })
                state.chatColorLoadError != null -> ({ viewModel.refresh() })
                else -> ({ showChatColorDialog(state.chatColor) })
            },
        )

        val channelAvailable = state.capabilities.editChannel && state.channel != null && state.channelLoadError == null
        val editableChannel = state.channel?.takeIf { channelAvailable }
        val channelValue = { value: String? ->
            when {
                editableChannel != null -> value?.takeIf { it.isNotBlank() } ?: getString(R.string.account_not_set)
                state.capabilities.editChannel -> getString(R.string.account_load_failed)
                else -> getString(R.string.account_reconnect_to_enable)
            }
        }
        val channelUnavailableClick = if (state.capabilities.editChannel) {
            { viewModel.refresh() }
        } else {
            { reconnectFor(R.string.account_channel_section) }
        }
        addSettingRow(
            binding.channelRows,
            getString(R.string.account_stream_title),
            channelValue(state.channel?.title),
            onClick = if (editableChannel != null) ({ showTextEditor(R.string.account_stream_title, editableChannel.title.orEmpty(), 140) { viewModel.updateChannel(title = it) } }) else channelUnavailableClick,
        )
        addSettingRow(
            binding.channelRows,
            getString(R.string.account_category),
            channelValue(state.channel?.gameName),
            onClick = if (editableChannel != null) ({ showCategoryDialog(editableChannel.gameName) }) else channelUnavailableClick,
        )
        addSettingRow(
            binding.channelRows,
            getString(R.string.account_language),
            if (editableChannel != null) languageLabel(editableChannel.language) else channelValue(null),
            onClick = if (editableChannel != null) ({ showLanguageDialog(editableChannel.language) }) else channelUnavailableClick,
        )
        addSettingRow(
            binding.channelRows,
            getString(R.string.account_tags),
            if (editableChannel != null) {
                editableChannel.tags.joinToString(", ").takeUnless { it.isNullOrBlank() } ?: getString(R.string.account_not_set)
            } else if (state.webSession && !state.capabilities.editChannelTags) {
                getString(R.string.account_tags_not_editable_in_session)
            } else {
                channelValue(null)
            },
            enabled = !state.webSession || state.capabilities.editChannelTags,
            onClick = when {
                editableChannel != null && state.capabilities.editChannelTags -> ({ showTextEditor(R.string.account_tags, editableChannel.tags.joinToString(", "), 260) { updateTags(it) } })
                editableChannel != null -> null
                state.webSession && !state.capabilities.editChannelTags -> null
                else -> channelUnavailableClick
            },
        )
        val chatEnabled = state.capabilities.editChatSettings && state.chatSettings != null && state.chatSettingsLoadError == null
        val chatSettingsClick = when {
            chatEnabled -> ({ openPage(PAGE_CHAT_SETTINGS) })
            state.capabilities.editChatSettings -> ({ viewModel.refresh() })
            else -> ({ reconnectFor(R.string.account_chat_settings) })
        }
        addSettingRow(
            binding.channelRows,
            getString(R.string.account_chat_settings),
            if (chatEnabled) chatSettingsSummary(state) else if (state.capabilities.editChatSettings) getString(R.string.account_load_failed) else getString(R.string.account_reconnect_to_enable),
            onClick = chatSettingsClick,
        )

        val blockedEnabled = state.capabilities.readBlockedUsers
        addSettingRow(
            binding.privacyRows,
            getString(R.string.account_blocked_users),
            if (blockedEnabled) getString(R.string.account_manage_blocked_users) else getString(R.string.account_reconnect_to_enable),
            onClick = if (blockedEnabled) ({ openPage(PAGE_BLOCKED_USERS) }) else ({ reconnectFor(R.string.account_blocked_users) }),
        )

        addSettingRow(
            binding.accountRows,
            getString(R.string.account_permissions),
            if (state.webSession) getString(R.string.account_web_session_permissions_summary)
            else getString(R.string.account_permissions_summary, state.scopes.size),
            onClick = { showPermissions(state.scopes, state.webSession) },
        )
        addSettingRow(
            binding.accountRows,
            getString(R.string.account_manage_on_twitch),
            getString(R.string.account_open_twitch),
            onClick = { openManageOnTwitch() },
        )
        addSettingRow(
            binding.accountRows,
            getString(R.string.log_out),
            "",
            onClick = { confirmLogout() },
        )
    }

    private fun renderChatSettings(state: AccountUiState) {
        binding.chatSettingsRows.removeAllViews()
        val settings = state.chatSettings?.takeIf { state.chatSettingsLoadError == null } ?: run {
            addSettingRow(
                binding.chatSettingsRows,
                getString(R.string.account_chat_settings),
                if (state.capabilities.editChatSettings) getString(R.string.account_load_failed) else getString(R.string.account_reconnect_to_enable),
                onClick = if (state.capabilities.editChatSettings) ({ viewModel.refresh() }) else ({ reconnectFor(R.string.account_chat_settings) }),
            )
            return
        }
        val enabled = state.capabilities.editChatSettings
        addToggleRow(
            getString(R.string.account_followers_only),
            settings.followerMode,
            enabled,
        ) { viewModel.updateChatSettings(followers = it) }
        addSettingRow(
            binding.chatSettingsRows,
            getString(R.string.account_follower_duration),
            formatFollowerDuration(settings.followerModeDuration),
            enabled = enabled && settings.followerMode,
            onClick = { showDurationEditor(R.string.account_follower_duration, settings.followerModeDuration ?: 0, false) },
        )
        addToggleRow(
            getString(R.string.account_slow_mode),
            settings.slowMode,
            enabled,
        ) { viewModel.updateChatSettings(slow = it) }
        addSettingRow(
            binding.chatSettingsRows,
            getString(R.string.account_slow_interval),
            getString(R.string.account_seconds, settings.slowModeWaitTime ?: 30),
            enabled = enabled && settings.slowMode,
            onClick = { showDurationEditor(R.string.account_slow_interval, settings.slowModeWaitTime ?: 30, true) },
        )
        addToggleRow(
            getString(R.string.account_subscribers_only),
            settings.subscriberMode,
            enabled,
        ) { viewModel.updateChatSettings(subs = it) }
        addToggleRow(
            getString(R.string.account_emote_only),
            settings.emoteMode,
            enabled,
        ) { viewModel.updateChatSettings(emote = it) }
        addToggleRow(
            getString(R.string.account_unique_chat),
            settings.uniqueChatMode,
            enabled,
        ) { viewModel.updateChatSettings(unique = it) }
    }

    private fun addToggleRow(
        label: String,
        checked: Boolean,
        enabled: Boolean,
        onChanged: (Boolean) -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            minimumHeight = 56.dp()
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(16.dp(), 4.dp(), 12.dp(), 4.dp())
        }
        val labelView = TextView(this).apply {
            text = label
            textSize = 16f
            setTextColor(MaterialColors.getColor(this, android.R.attr.textColorPrimary, Color.WHITE))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switch = MaterialSwitch(this).apply {
            isChecked = checked
            isEnabled = enabled
            contentDescription = label
        }
        switch.setOnCheckedChangeListener { _, value ->
            if (enabled) onChanged(value)
        }
        row.addView(labelView)
        row.addView(switch)
        binding.chatSettingsRows.addView(row)
    }

    private fun renderBlockedUsers(state: AccountUiState) {
        binding.blockedUsersRows.removeAllViews()
        val query = blockedUsersQuery.trim().lowercase()
        val visibleUsers = state.blockedUsers.filter { user ->
            query.isBlank() || listOfNotNull(user.displayName, user.login).any { it.lowercase().contains(query) }
        }
        visibleUsers.forEachIndexed { index, user ->
            val item = ItemAccountBlockedUserBinding.inflate(LayoutInflater.from(this), binding.blockedUsersRows, false)
            bindBlockedUser(item, user, state.capabilities.manageBlockedUsers)
            if (index == visibleUsers.lastIndex) item.root.findViewById<View>(R.id.divider)?.isGone = true
            binding.blockedUsersRows.addView(item.root)
        }
        binding.blockedUsersProgress.isVisible = state.blockedUsersLoading
        val loadError = state.blockedUsersLoadError
        binding.blockedUsersEmpty.text = loadError ?: if (query.isBlank()) {
            getString(R.string.account_blocked_users_empty)
        } else {
            getString(R.string.account_blocked_users_no_match)
        }
        binding.blockedUsersEmpty.isVisible = !state.blockedUsersLoading &&
            (loadError != null || (visibleUsers.isEmpty() && state.error.isNullOrBlank()))
        binding.loadMoreBlockedUsers.text = getString(
            if (loadError != null) R.string.account_retry else R.string.account_load_more,
        )
        binding.loadMoreBlockedUsers.isVisible = !state.blockedUsersLoading &&
            (loadError != null || state.blockedUsersCursor != null)
        binding.loadMoreBlockedUsers.setOnClickListener {
            viewModel.loadBlockedUsers(reset = loadError != null)
        }
    }

    private fun bindBlockedUser(binding: ItemAccountBlockedUserBinding, user: BlockedUser, canUnblock: Boolean) {
        binding.displayName.text = user.displayName ?: user.login ?: getString(R.string.account_unknown_user)
        binding.login.text = user.login?.let { "@$it" }.orEmpty()
        binding.unblock.isVisible = canUnblock
        binding.unblock.setOnClickListener { viewModel.unblockUser(user) }
    }

    private fun addSettingRow(
        container: LinearLayout,
        label: String,
        value: String,
        color: String? = null,
        enabled: Boolean = true,
        onClick: (() -> Unit)? = null,
    ) {
        val row = ItemAccountSettingBinding.inflate(LayoutInflater.from(this), container, false)
        row.label.text = label
        row.value.text = value
        row.root.isEnabled = enabled
        row.root.alpha = if (enabled) 1f else 0.55f
        row.root.isClickable = onClick != null
        row.root.isFocusable = onClick != null
        row.arrow.isVisible = onClick != null
        color?.let { parseColor(it) }?.let { parsed ->
            row.colorSwatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(parsed)
            }
            row.colorSwatch.isVisible = true
        }
        row.root.setOnClickListener { if (enabled) onClick?.invoke() }
        container.addView(row.root)
    }

    private fun showBioEditor(current: String) {
        showTextEditor(R.string.account_bio, current, 300) { viewModel.updateBio(it) }
    }

    private fun showTextEditor(title: Int, current: String, maxLength: Int, onSave: (String) -> Unit) {
        val inputLayout = TextInputLayout(this).apply {
            hint = getString(title)
            isCounterEnabled = true
            counterMaxLength = maxLength
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val input = TextInputEditText(this).apply {
            setText(current)
            setSelection(text?.length ?: 0)
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = if (title == R.string.account_bio) 3 else 1
            maxLines = if (title == R.string.account_bio) 6 else 3
        }
        inputLayout.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(inputLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> onSave(input.text?.toString().orEmpty()) }
            .show()
    }

    private fun showChatColorDialog(current: String?) {
        val options = TWITCH_CHAT_COLOR_OPTIONS
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        options.forEach { option ->
            val row = TextView(this).apply {
                text = "●  ${option.name}  ${option.hex}"
                textSize = 16f
                setTextColor(Color.parseColor(option.hex))
                setPadding(8.dp(), 12.dp(), 8.dp(), 12.dp())
                contentDescription = option.name
                setOnClickListener {
                    dialog.dismiss()
                    viewModel.updateChatColor(option.apiValue)
                }
            }
            list.addView(row)
        }
        val custom = MaterialButton(this).apply {
            text = getString(R.string.account_custom_color)
            setOnClickListener {
                dialog.dismiss()
                showCustomColorEditor(current)
            }
        }
        list.addView(custom)
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_chat_color)
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun showCustomColorEditor(current: String?) {
        val inputLayout = TextInputLayout(this).apply { hint = getString(R.string.account_hex_color) }
        val input = TextInputEditText(this).apply {
            setText(current?.takeIf { it.startsWith("#") } ?: "#")
            inputType = InputType.TYPE_CLASS_TEXT
            filters = arrayOf(InputFilter.LengthFilter(7))
        }
        inputLayout.addView(input)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_custom_color)
            .setView(inputLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString()?.trim().orEmpty().uppercase()
                if (!isValidCustomChatColor(value)) {
                    inputLayout.error = getString(R.string.account_invalid_color)
                } else {
                    dialog.dismiss()
                    viewModel.updateChatColor(value)
                }
            }
        }
        dialog.show()
    }

    private fun showCategoryDialog(current: String?) {
        val searchLayout = TextInputLayout(this).apply {
            hint = getString(R.string.account_search_categories)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(current.orEmpty())
            setSelection(text?.length ?: 0)
        }
        searchLayout.addView(input)
        val resultList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        categoryResultsContainer = resultList
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchLayout)
            addView(ScrollView(this@AccountActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 320.dp())
                addView(resultList)
            })
        }
        categoryDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_category)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        categoryDialog?.setOnDismissListener {
            categoryResultsContainer = null
            categoryDialog = null
        }
        var searchJob: Job? = null
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(250)
                    viewModel.searchCategories(s?.toString().orEmpty())
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        categoryDialog?.show()
    }

    private fun renderCategoryResults(games: List<Game>) {
        val container = categoryResultsContainer ?: return
        container.removeAllViews()
        games.forEach { game ->
            val id = game.id ?: return@forEach
            TextView(this).apply {
                text = game.name.orEmpty()
                textSize = 16f
                setPadding(8.dp(), 12.dp(), 8.dp(), 12.dp())
                setOnClickListener {
                    categoryDialog?.dismiss()
                    viewModel.updateChannel(gameId = id, gameName = game.name)
                }
            }.also(container::addView)
        }
    }

    private fun showLanguageDialog(current: String?) {
        val normalizedCurrent = current?.trim()?.lowercase(Locale.ROOT)
        val languages = SUPPORTED_LANGUAGE_CODES.map { code ->
            LanguageOption(code, languageLabel(code))
        }.toMutableList().apply {
            if (!normalizedCurrent.isNullOrBlank() && none { it.code == normalizedCurrent }) {
                add(0, LanguageOption(normalizedCurrent, getString(R.string.account_language_current, normalizedCurrent)))
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_language)
            .setSingleChoiceItems(
                languages.map { it.label }.toTypedArray(),
                languages.indexOfFirst { it.code == normalizedCurrent },
            ) { dialog, which ->
                viewModel.updateChannel(language = languages[which].code)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDurationEditor(title: Int, current: Int, slow: Boolean) {
        val inputLayout = TextInputLayout(this).apply { hint = getString(title) }
        val input = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setSelectAllOnFocus(true)
        }
        inputLayout.addView(input)
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(inputLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val value = input.text?.toString()?.toIntOrNull() ?: return@setPositiveButton
                if (slow) {
                    viewModel.updateChatSettings(slow = true, slowDuration = value)
                } else {
                    viewModel.updateChatSettings(followers = true, followersDuration = value)
                }
            }
            .show()
    }

    private fun updateTags(text: String) {
        val tags = text.split(',', '\n').map(String::trim).filter(String::isNotBlank)
        viewModel.updateChannel(tags = tags)
    }

    private fun openPage(target: String) {
        startActivity(Intent(this, AccountActivity::class.java).putExtra(EXTRA_PAGE, target))
    }

    private fun openOwnProfile() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.INTENT_OPEN_OWN_PROFILE
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finish()
    }

    private fun reconnectFor(feature: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_reconnect_title)
            .setMessage(getString(R.string.account_reconnect_feature_message, getString(feature)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.account_reconnect) { _, _ ->
                loginLauncher.launch(
                    Intent(this, TwitchWebLoginActivity::class.java)
                        .putExtra(TwitchWebLoginActivity.EXTRA_REAUTHORIZE, true),
                )
            }
            .show()
    }

    private fun confirmLogout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_logout_confirm)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.log_out) { _, _ ->
                logoutPending = true
                loginLauncher.launch(
                    Intent(this, TwitchWebLoginActivity::class.java)
                        .putExtra(TwitchWebLoginActivity.EXTRA_LOGOUT, true),
                )
            }
            .show()
    }

    private fun showPermissions(scopes: Set<String>, webSession: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_permissions)
            .setMessage(
                if (webSession) getString(R.string.account_web_session_permissions)
                else if (scopes.isEmpty()) getString(R.string.account_no_permissions)
                else scopes.sorted().joinToString("\n"),
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openManageOnTwitch() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, "https://dashboard.twitch.tv/settings/channel".toUri()))
        }.onFailure {
            Toast.makeText(this, R.string.no_browser_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatFollowerDuration(minutes: Int?): String = when {
        minutes == null || minutes == 0 -> getString(R.string.account_all_followers)
        else -> getString(R.string.account_minutes, minutes)
    }

    private fun chatSettingsSummary(state: AccountUiState): String {
        val settings = state.chatSettings ?: return getString(R.string.account_not_set)
        return listOfNotNull(
            if (settings.followerMode) getString(R.string.account_followers_only) else null,
            if (settings.slowMode) getString(R.string.account_slow_mode) else null,
            if (settings.subscriberMode) getString(R.string.account_subscribers_only) else null,
        ).ifEmpty { listOf(getString(R.string.account_chat_open)) }.joinToString(", ")
    }

    private fun languageLabel(code: String?): String {
        val normalized = code?.trim()?.lowercase(Locale.ROOT)
        return when (normalized) {
            "en" -> getString(R.string.account_language_english)
            "es" -> getString(R.string.account_language_spanish)
            "de" -> getString(R.string.account_language_german)
            "fr" -> getString(R.string.account_language_french)
            "pt" -> getString(R.string.account_language_portuguese)
            "ru" -> getString(R.string.account_language_russian)
            "ja" -> getString(R.string.account_language_japanese)
            "ko" -> getString(R.string.account_language_korean)
            "zh" -> getString(R.string.account_language_chinese)
            "other" -> getString(R.string.account_language_other)
            null, "" -> getString(R.string.account_not_set)
            else -> Locale.forLanguageTag(normalized).getDisplayLanguage(Locale.getDefault()).takeIf { it.isNotBlank() } ?: normalized
        }
    }

    private fun parseColor(value: String): Int? = runCatching { Color.parseColor(value) }.getOrNull()

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private data class AuthHealthCardSpec(
        val title: Int,
        val message: Int,
        val action: Int,
        val reauthorize: Boolean,
    )

    companion object {
        const val EXTRA_PAGE = "account_page"
        const val PAGE_MAIN = "main"
        const val PAGE_CHAT_SETTINGS = "chat_settings"
        const val PAGE_BLOCKED_USERS = "blocked_users"

        private data class LanguageOption(val code: String, val label: String)

        private val SUPPORTED_LANGUAGE_CODES = listOf(
            "ar", "bg", "cs", "da", "de", "el", "en", "es", "fi", "fr", "hu", "it",
            "ja", "ko", "nl", "no", "pl", "pt", "ro", "ru", "sk", "sv", "th", "tr",
            "vi", "zh", "other",
        )
    }
}
