package com.github.andreyasadchy.xtra.ui.player

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.media3.common.Tracks
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.PlayerSettingsBinding
import com.github.andreyasadchy.xtra.ui.settings.EXTRA_SETTINGS_SCREEN
import com.github.andreyasadchy.xtra.ui.settings.SETTINGS_SCREEN_PLAYER_CONTROLS
import com.github.andreyasadchy.xtra.ui.settings.SETTINGS_SCREEN_PLAYER_HUD
import com.github.andreyasadchy.xtra.ui.settings.SETTINGS_SCREEN_PLAYER
import com.github.andreyasadchy.xtra.ui.settings.SETTINGS_SCREEN_CHAT
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.tv.TvChatMode
import com.github.andreyasadchy.xtra.ui.tv.TvFocusHelper
import com.github.andreyasadchy.xtra.ui.tv.tvChatMode
import com.github.andreyasadchy.xtra.ui.player.hud.HudElementId
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.isChatEnabled
import com.github.andreyasadchy.xtra.util.isTelevision
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class PlayerSettingsDialog : BottomSheetDialogFragment() {

    companion object {

        private const val TYPE = "type"
        private const val SPEED = "speed"
        private const val VOD_GAMES = "vod_games"

        fun newInstance(type: String?, speedText: String?, vodGames: Boolean): PlayerSettingsDialog {
            return PlayerSettingsDialog().apply {
                arguments = Bundle().apply {
                    putString(TYPE, type)
                    putString(SPEED, speedText)
                    putBoolean(VOD_GAMES, vodGames)
                }
            }
        }
    }

    private var _binding: PlayerSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = PlayerSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val menuBottomPadding = binding.menuContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val bottomInset = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            ).bottom
            binding.menuContainer.setPadding(
                binding.menuContainer.paddingLeft,
                binding.menuContainer.paddingTop,
                binding.menuContainer.paddingRight,
                menuBottomPadding + bottomInset,
            )
            insets
        }
        ViewCompat.requestApplyInsets(view)
        val behavior = BottomSheetBehavior.from(view.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        val arguments = requireArguments()
        val type = arguments.getString(TYPE)
        val isTv = requireContext().isTelevision()
        with(binding) {
            menuCustomizeHud.isVisible = !isTv
            if (menuCustomizeHud.isVisible) {
                menuCustomizeHud.setOnClickListener {
                    showHudEditor()
                }
            }
            menuPlayerControlSettings.setOnClickListener {
                dismiss()
                val intent = Intent(requireContext(), SettingsActivity::class.java).apply {
                    putExtra(EXTRA_SETTINGS_SCREEN, SETTINGS_SCREEN_PLAYER_CONTROLS)
                }
                (activity as? MainActivity)?.settingsResultLauncher?.launch(intent)
                    ?: startActivity(intent)
            }
            if (isTv && requireContext().prefs().isChatEnabled()) {
                val modeValues = resources.getStringArray(R.array.tvChatModeValues)
                val modeEntries = resources.getStringArray(R.array.tvChatModeEntries)
                menuChatLayout.visibility = View.VISIBLE
                menuChatLayout.text = getString(R.string.settings_tv_chat_layout) + ": " +
                    modeEntries.getOrElse(modeValues.indexOf(tvChatMode(requireContext()).name.lowercase())) { modeEntries.first() }
                menuChatLayout.setOnClickListener {
                    val currentMode = tvChatMode(requireContext()).name.lowercase()
                    requireContext().getAlertDialogBuilder()
                        .setTitle(R.string.settings_tv_chat_layout)
                        .setSingleChoiceItems(modeEntries, modeValues.indexOf(currentMode)) { dialog, which ->
                            val selected = modeValues.getOrNull(which) ?: return@setSingleChoiceItems
                            requireContext().prefs().edit { putString(C.TV_CHAT_MODE, selected) }
                            menuChatLayout.text = getString(R.string.settings_tv_chat_layout) + ": " +
                                modeEntries.getOrElse(which) { modeEntries.first() }
                            when (selected) {
                                "hidden" -> (parentFragment as? Media3PlayerFragment)?.hideChat() ?: (parentFragment as? PlayerFragment)?.hideChat()
                                else -> (parentFragment as? Media3PlayerFragment)?.showChat() ?: (parentFragment as? PlayerFragment)?.showChat()
                            }
                            dialog.dismiss()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
                menuConfigureTvChat.visibility = View.VISIBLE
                menuConfigureTvChat.setOnClickListener {
                    dismiss()
                    val intent = Intent(requireContext(), SettingsActivity::class.java).apply {
                        putExtra(EXTRA_SETTINGS_SCREEN, SETTINGS_SCREEN_CHAT)
                    }
                    (activity as? MainActivity)?.settingsResultLauncher?.launch(intent)
                        ?: startActivity(intent)
                }
            }
            if (type != BasePlaybackService.STREAM &&
                (isTv ||
                    requireContext().prefs().getBoolean(C.PLAYER_MENU_SPEED, false) ||
                    canShowHudActionInOverflow(HudElementId.SPEED))
            ) {
                menuSpeed.visibility = View.VISIBLE
                menuSpeed.setOnClickListener {
                    (parentFragment as? Media3PlayerFragment)?.showSpeedDialog() ?:
                    (parentFragment as? PlayerFragment)?.showSpeedDialog()
                    dismiss()
                }
                setSpeed(arguments.getString(SPEED))
            }
            if (requireContext().prefs().getBoolean(C.PLAYER_MENU_QUALITY, false) ||
                canShowHudActionInOverflow(HudElementId.QUALITY)
            ) {
                menuQuality.visibility = View.VISIBLE
                // Quality may not have a label yet while the controller/HLS
                // playlist is starting. Always route the tap through the
                // fragment so Media3 can retain it until qualities arrive.
                menuQuality.setOnClickListener {
                    openQualityDialog()
                }
                (parentFragment as? Media3PlayerFragment)?.setQualityText() ?:
                (parentFragment as? PlayerFragment)?.setQualityText()
            }
            listOf(
                HudElementId.FOLLOW to menuFollow,
                HudElementId.CLIP to menuClip,
                HudElementId.GO_LIVE to menuGoLive,
                HudElementId.AUDIO_MODE to menuAudioMode,
                HudElementId.AUDIO_COMPRESSOR to menuAudioCompressor,
                HudElementId.INTERACTION_LOCK to menuInteractionLock,
                HudElementId.MINIMIZE to menuMinimize,
            ).forEach { (id, menuItem) ->
                if (canShowHudActionInOverflow(id)) {
                    menuItem.visibility = View.VISIBLE
                    hudActionContentDescription(id)?.let { description ->
                        menuItem.text = description
                        menuItem.contentDescription = description
                    }
                    menuItem.isEnabled = isHudActionEnabled(id)
                    if (id == HudElementId.AUDIO_COMPRESSOR) {
                        ViewCompat.setStateDescription(
                            menuItem,
                            getString(
                                if (requireContext().prefs().getBoolean(C.PLAYER_AUDIO_COMPRESSOR, false)) {
                                    R.string.enabled_setting
                                } else {
                                    R.string.disabled_setting
                                },
                            ),
                        )
                    }
                    menuItem.setOnClickListener {
                        if (!isHudActionEnabled(id)) return@setOnClickListener
                        performHudAction(id)
                        dismiss()
                    }
                }
            }
            val videoInfoParent = parentFragment as? PlaybackVideoInfoHost
            if (videoInfoParent != null &&
                requireContext().prefs().getBoolean(C.PLAYER_MENU_VIDEO_INFO, false)
            ) {
                menuVideoInfo.visibility = View.VISIBLE
                menuVideoInfo.setOnClickListener {
                    videoInfoParent.showVideoInfoDialog()
                    dismiss()
                }
            }
            if (type == BasePlaybackService.STREAM) {
                val media3Parent = parentFragment as? Media3PlayerFragment
                val legacyParent = parentFragment as? PlayerFragment
                if (media3Parent != null || legacyParent?.isLiveCaptionsAvailable() == true) {
                    menuLiveCaptionSettings.visibility = View.VISIBLE
                    menuLiveCaptionSettings.setOnClickListener {
                        dismiss()
                        media3Parent?.openLiveCaptionSettings()
                            ?: legacyParent?.openLiveCaptionSettings()
                    }
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_VIEWER_LIST, true)) {
                    menuViewerList.visibility = View.VISIBLE
                    menuViewerList.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.openViewerList() ?:
                        (parentFragment as? PlayerFragment)?.openViewerList()
                        dismiss()
                    }
                }
                if (parentFragment is PlayerFragment &&
                    requireContext().prefs().getBoolean(C.PLAYER_MENU_FIND_VOD, true)
                ) {
                    menuFindVod.visibility = View.VISIBLE
                    menuFindVod.setOnClickListener {
                        (parentFragment as PlayerFragment).findVideoUrl()
                        dismiss()
                    }
                }
                if (isTv ||
                    requireContext().prefs().getBoolean(C.PLAYER_MENU_RESTART, false) ||
                    canShowHudActionInOverflow(HudElementId.RESTART)
                ) {
                    menuRestart.visibility = View.VISIBLE
                    menuRestart.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.restartPlayer() ?:
                        (parentFragment as? PlayerFragment)?.restartPlayer()
                        dismiss()
                    }
                }
                if (requireContext().prefs().isChatEnabled()) {
                    val isLoggedIn = !requireContext().tokenPrefs().getString(C.USERNAME, null).isNullOrBlank() &&
                            (!TwitchApiHelper.getGQLHeaders(requireContext(), true)[C.HEADER_TOKEN].isNullOrBlank() ||
                                    !TwitchApiHelper.getHelixHeaders(requireContext())[C.HEADER_TOKEN].isNullOrBlank())
                    if (isLoggedIn &&
                        (requireContext().prefs().getBoolean(C.PLAYER_MENU_CHAT_BAR, true) ||
                            canShowHudActionInOverflow(HudElementId.CHAT_INPUT))
                    ) {
                        menuChatBar.visibility = View.VISIBLE
                        if (requireContext().prefs().getBoolean(C.KEY_CHAT_BAR_VISIBLE, true)) {
                            menuChatBar.text = getString(R.string.hide_chat_bar)
                        } else {
                            menuChatBar.text = getString(R.string.show_chat_bar)
                        }
                        menuChatBar.setOnClickListener {
                            (parentFragment as? Media3PlayerFragment)?.toggleChatBar() ?:
                            (parentFragment as? PlayerFragment)?.toggleChatBar()
                            dismiss()
                        }
                    }
                    if (requireContext().prefs().getBoolean(C.PLAYER_MENU_CHAT_DISCONNECT, true)) {
                        menuChatDisconnect.visibility = View.VISIBLE
                        if (((parentFragment as? Media3PlayerFragment)?.isActive() ?: (parentFragment as? PlayerFragment)?.isActive()) == true) {
                            menuChatDisconnect.text = getString(R.string.disconnect_chat)
                            menuChatDisconnect.setOnClickListener {
                                (parentFragment as? Media3PlayerFragment)?.disconnect() ?:
                                (parentFragment as? PlayerFragment)?.disconnect()
                                dismiss()
                            }
                        } else {
                            menuChatDisconnect.text = getString(R.string.connect_chat)
                            menuChatDisconnect.setOnClickListener {
                                (parentFragment as? Media3PlayerFragment)?.reconnect() ?:
                                (parentFragment as? PlayerFragment)?.reconnect()
                                dismiss()
                            }
                        }
                    }
                }
                if (requireContext().prefs().getBoolean(C.DEBUG_PLAYER_MENU_PLAYLIST_TAGS, false)) {
                    menuMediaPlaylistTags.visibility = View.VISIBLE
                    menuMediaPlaylistTags.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.showPlaylistTags(true) ?:
                        (parentFragment as? PlayerFragment)?.showPlaylistTags(true)
                        dismiss()
                    }
                    menuMultivariantPlaylistTags.visibility = View.VISIBLE
                    menuMultivariantPlaylistTags.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.showPlaylistTags(false) ?:
                        (parentFragment as? PlayerFragment)?.showPlaylistTags(false)
                        dismiss()
                    }
                }
            }
            if (type == BasePlaybackService.VIDEO) {
                if (arguments.getBoolean(VOD_GAMES)) {
                    setVodGames()
                }
                if (requireContext().prefs().getBoolean(C.PLAYER_MENU_BOOKMARK, true)) {
                    (parentFragment as? Media3PlayerFragment)?.checkBookmark() ?:
                    (parentFragment as? PlayerFragment)?.checkBookmark()
                }
            }
            if (type != BasePlaybackService.OFFLINE_VIDEO &&
                (requireContext().prefs().getBoolean(C.PLAYER_MENU_DOWNLOAD, true) ||
                    canShowHudActionInOverflow(HudElementId.DOWNLOAD))
            ) {
                menuDownload.visibility = View.VISIBLE
                menuDownload.setOnClickListener {
                    (parentFragment as? Media3PlayerFragment)?.showDownloadDialog() ?:
                    (parentFragment as? PlayerFragment)?.showDownloadDialog()
                    dismiss()
                }
            }
            if (requireContext().prefs().getBoolean(C.PLAYER_MENU_SHARE, true)) {
                menuShare.visibility = View.VISIBLE
                menuShare.setOnClickListener {
                    (parentFragment as? Media3PlayerFragment)?.share() ?:
                    (parentFragment as? PlayerFragment)?.share()
                    dismiss()
                }
            }
            if (type != BasePlaybackService.CLIP &&
                (requireContext().prefs().getBoolean(C.PLAYER_MENU_SLEEP, true) ||
                    canShowHudActionInOverflow(HudElementId.SLEEP_TIMER))
            ) {
                menuTimer.visibility = View.VISIBLE
                menuTimer.setOnClickListener {
                    (parentFragment as? Media3PlayerFragment)?.showSleepTimerDialog() ?:
                    (parentFragment as? PlayerFragment)?.showSleepTimerDialog()
                    dismiss()
                }
            }
            if (((parentFragment as? Media3PlayerFragment)?.getIsPortrait() ?: (parentFragment as? PlayerFragment)?.getIsPortrait()) == false) {
                if (isTv ||
                    requireContext().prefs().getBoolean(C.PLAYER_MENU_ASPECT, false) ||
                    canShowHudActionInOverflow(HudElementId.ASPECT_RATIO)
                ) {
                    menuRatio.visibility = View.VISIBLE
                    menuRatio.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.setResizeMode() ?:
                        (parentFragment as? PlayerFragment)?.setResizeMode()
                        dismiss()
                    }
                }
            }
            // The compact portrait layout deliberately keeps chat out of the
            // video when it is too narrow, but that must not remove the action
            // from More.
            if (requireContext().prefs().isChatEnabled() &&
                (isTv ||
                    requireContext().prefs().getBoolean(C.PLAYER_MENU_CHAT_TOGGLE, false) ||
                    canShowHudActionInOverflow(HudElementId.CHAT))
            ) {
                menuChatToggle.visibility = View.VISIBLE
                if (requireContext().prefs().getBoolean(C.KEY_CHAT_OPENED, true)) {
                    menuChatToggle.text = getString(R.string.hide_chat)
                    menuChatToggle.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.hideChat() ?:
                        (parentFragment as? PlayerFragment)?.hideChat()
                        dismiss()
                    }
                } else {
                    menuChatToggle.text = getString(R.string.show_chat)
                    menuChatToggle.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.showChat() ?:
                        (parentFragment as? PlayerFragment)?.showChat()
                        dismiss()
                    }
                }
            }
            if (isTv ||
                requireContext().prefs().getBoolean(C.PLAYER_MENU_VOLUME, false) ||
                canShowHudActionInOverflow(HudElementId.VOLUME)
            ) {
                menuVolume.visibility = View.VISIBLE
                menuVolume.setOnClickListener {
                    (parentFragment as? Media3PlayerFragment)?.showVolumeDialog() ?:
                    (parentFragment as? PlayerFragment)?.showVolumeDialog()
                    dismiss()
                }
            }
            if (requireContext().prefs().getBoolean(C.CHAT_TRANSLATE, false) && Build.SUPPORTED_64_BIT_ABIS.firstOrNull() == "arm64-v8a") {
                val translateAll = (parentFragment as? Media3PlayerFragment)?.getTranslateAllMessages() ?: (parentFragment as? PlayerFragment)?.getTranslateAllMessages()
                if (translateAll != null) {
                    menuTranslateAll.visibility = View.VISIBLE
                    if (translateAll) {
                        menuTranslateAll.setOnClickListener {
                            (parentFragment as? Media3PlayerFragment)?.deleteTranslatedChannel() ?:
                            (parentFragment as? PlayerFragment)?.deleteTranslatedChannel()
                            dismiss()
                        }
                    } else {
                        menuTranslateAll.setOnClickListener {
                            (parentFragment as? Media3PlayerFragment)?.saveTranslatedChannel() ?:
                            (parentFragment as? PlayerFragment)?.saveTranslatedChannel()
                            dismiss()
                        }
                    }
                }
            }
            (parentFragment as? Media3PlayerFragment)?.setSubtitlesButton() ?:
            (parentFragment as? PlayerFragment)?.setSubtitlesButton()
            if ((type == BasePlaybackService.STREAM || type == BasePlaybackService.VIDEO) &&
                requireContext().prefs().isChatEnabled() &&
                requireContext().prefs().getBoolean(C.PLAYER_MENU_RELOAD_EMOTES, true)
            ) {
                menuReloadEmotes.visibility = View.VISIBLE
                menuReloadEmotes.setOnClickListener {
                    (parentFragment as? Media3PlayerFragment)?.reloadEmotes() ?:
                    (parentFragment as? PlayerFragment)?.reloadEmotes()
                    dismiss()
                }
            }
        }
        setMenuTextColor(view)

        if (requireContext().isTelevision()) {
            TvFocusHelper.installClickableDescendants(binding.menuContainer)
            binding.menuContainer.post {
                val firstAction = (0 until binding.menuContainer.childCount)
                    .asSequence()
                    .map(binding.menuContainer::getChildAt)
                    .firstOrNull { it.isVisible && it.isFocusable && it.isEnabled }
                firstAction?.requestFocus()
            }
        }
    }

    private fun canShowHudActionInOverflow(id: HudElementId): Boolean =
        (parentFragment as? Media3PlayerFragment)?.canShowHudActionInOverflow(id)
            ?: (parentFragment as? PlayerFragment)?.canShowHudActionInOverflow(id)
            ?: false

    private fun hudActionContentDescription(id: HudElementId): CharSequence? =
        (parentFragment as? Media3PlayerFragment)?.hudActionContentDescription(id)
            ?: (parentFragment as? PlayerFragment)?.hudActionContentDescription(id)

    private fun isHudActionEnabled(id: HudElementId): Boolean =
        (parentFragment as? Media3PlayerFragment)?.isHudActionEnabled(id)
            ?: (parentFragment as? PlayerFragment)?.isHudActionEnabled(id)
            ?: false

    private fun performHudAction(id: HudElementId) {
        (parentFragment as? Media3PlayerFragment)?.performHudAction(id)
            ?: (parentFragment as? PlayerFragment)?.performHudAction(id)
    }

    private fun showHudEditor() {
        dismiss()
        val intent = Intent(requireContext(), SettingsActivity::class.java).apply {
            putExtra(EXTRA_SETTINGS_SCREEN, SETTINGS_SCREEN_PLAYER_HUD)
        }
        (activity as? MainActivity)?.settingsResultLauncher?.launch(intent)
            ?: startActivity(intent)
    }

    private fun setMenuTextColor(view: View) {
        if (view is TextView) {
            val themeMode = requireContext().prefs().getString(C.SETTINGS_THEME_MODE, "system")
            val isDark = when (themeMode) {
                "dark", "amoled" -> true
                "light" -> false
                else -> (view.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
            view.setTextColor(if (isDark) Color.WHITE else Color.BLACK)
        } else if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                setMenuTextColor(view.getChildAt(index))
            }
        }
    }

    fun setQuality(text: String?) {
        with(binding) {
            if (!text.isNullOrBlank() && menuQuality.isVisible) {
                qualityValue.visibility = View.VISIBLE
                qualityValue.text = text
            }
        }
    }

    private fun openQualityDialog() {
        (parentFragment as? Media3PlayerFragment)?.showQualityDialog() ?:
        (parentFragment as? PlayerFragment)?.showQualityDialog()
        dismiss()
    }

    fun setSpeed(text: String?) {
        with(binding) {
            if (!text.isNullOrBlank() && menuSpeed.isVisible) {
                speedValue.visibility = View.VISIBLE
                speedValue.text = text
            }
        }
    }

    fun setVodGames() {
        with(binding) {
            if (requireContext().isTelevision() ||
                requireContext().prefs().getBoolean(C.PLAYER_MENU_GAMES, false) ||
                canShowHudActionInOverflow(HudElementId.CHAPTERS)
            ) {
                menuVodGames.visibility = View.VISIBLE
                menuVodGames.setOnClickListener {
                    (parentFragment as? Media3PlayerFragment)?.showVodGames() ?:
                    (parentFragment as? PlayerFragment)?.showVodGames()
                    dismiss()
                }
            }
        }
    }

    fun setBookmarkText(isBookmarked: Boolean) {
        with(binding) {
            menuBookmark.visibility = View.VISIBLE
            menuBookmark.text = getString(if (isBookmarked) R.string.remove_bookmark else R.string.add_bookmark)
            menuBookmark.setOnClickListener {
                (parentFragment as? Media3PlayerFragment)?.saveBookmark() ?:
                (parentFragment as? PlayerFragment)?.saveBookmark()
                dismiss()
            }
        }
    }

    fun setSubtitles(subtitles: Tracks.Group? = null) {
        with(binding) {
            if (subtitles != null &&
                (requireContext().isTelevision() ||
                    requireContext().prefs().getBoolean(C.PLAYER_MENU_SUBTITLES, true) ||
                    canShowHudActionInOverflow(HudElementId.CAPTIONS))
            ) {
                menuSubtitles.visibility = View.VISIBLE
                if (subtitles.isSelected) {
                    menuSubtitles.text = getString(R.string.hide_subtitles)
                    menuSubtitles.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.toggleSubtitles(false) ?:
                        (parentFragment as? PlayerFragment)?.toggleSubtitles(false)
                        requireContext().prefs().edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, false) }
                        dismiss()
                    }
                } else {
                    menuSubtitles.text = getString(R.string.show_subtitles)
                    menuSubtitles.setOnClickListener {
                        (parentFragment as? Media3PlayerFragment)?.toggleSubtitles(true) ?:
                        (parentFragment as? PlayerFragment)?.toggleSubtitles(true)
                        requireContext().prefs().edit { putBoolean(C.PLAYER_SUBTITLES_ENABLED, true) }
                        dismiss()
                    }
                }
            } else {
                menuSubtitles.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
