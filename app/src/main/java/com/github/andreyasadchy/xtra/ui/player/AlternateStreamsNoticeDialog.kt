package com.github.andreyasadchy.xtra.ui.player

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.settings.EXTRA_SETTINGS_HIGHLIGHT_PREFERENCE
import com.github.andreyasadchy.xtra.ui.settings.EXTRA_SETTINGS_SCREEN
import com.github.andreyasadchy.xtra.ui.settings.SETTINGS_SCREEN_PLAYER
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs

class AlternateStreamsNoticeDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        requireContext().getAlertDialogBuilder()
            .setTitle(R.string.alternate_streams_notice_title)
            .setMessage(R.string.alternate_streams_notice_message)
            .setPositiveButton(R.string.alternate_streams_notice_settings) { _, _ ->
                val intent = Intent(requireContext(), SettingsActivity::class.java).apply {
                    putExtra(EXTRA_SETTINGS_SCREEN, SETTINGS_SCREEN_PLAYER)
                    putExtra(EXTRA_SETTINGS_HIGHLIGHT_PREFERENCE, C.PLAYER_VAFT_ENABLED)
                }
                (activity as? MainActivity)?.settingsResultLauncher?.launch(intent)
                    ?: startActivity(intent)
            }
            .setNegativeButton(R.string.alternate_streams_notice_understand, null)
            .create()

    override fun onStart() {
        super.onStart()
        // A displayed notice is one-time even when dismissed with Back or outside the dialog.
        requireContext().prefs().edit { putBoolean(C.PLAYER_VAFT_AD_NOTICE_SEEN, true) }
    }

    companion object {
        const val TAG = "alternateStreamsNotice"
    }
}
