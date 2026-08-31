package com.github.andreyasadchy.xtra.ui.player.captions

import android.os.Bundle
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs

/** A small, player-like preview used to position the real caption overlay. */
class LiveCaptionPositionDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): AlertDialog {
        val content = layoutInflater.inflate(R.layout.dialog_live_caption_position, null)
        val preview = content.findViewById<FrameLayout>(R.id.liveCaptionPositionPreview)
        val caption = content.findViewById<LiveCaptionOverlayView>(R.id.liveCaptionPositionSample)
        caption.submitCaption(getString(R.string.live_caption_position_preview_text), 1L)

        val preferences = requireContext().prefs()
        var workingX = preferences.getFloat(C.PLAYER_LIVE_CAPTION_POSITION_X, 0f)
        var workingY = preferences.getFloat(C.PLAYER_LIVE_CAPTION_POSITION_Y, 0f)
        var downX = 0f
        var downY = 0f
        var startTranslationX = 0f
        var startTranslationY = 0f

        fun applyWorkingPosition() {
            if (preview.width == 0 || preview.height == 0) return
            caption.translationX = (workingX * preview.width).coerceIn(
                -caption.left.toFloat(),
                (preview.width - caption.right).toFloat(),
            )
            caption.translationY = (workingY * preview.height).coerceIn(
                -caption.top.toFloat(),
                (preview.height - caption.bottom).toFloat(),
            )
        }

        preview.post { applyWorkingPosition() }
        caption.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startTranslationX = caption.translationX
                    startTranslationY = caption.translationY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = (startTranslationX + event.rawX - downX).coerceIn(
                        -caption.left.toFloat(),
                        (preview.width - caption.right).toFloat(),
                    )
                    val y = (startTranslationY + event.rawY - downY).coerceIn(
                        -caption.top.toFloat(),
                        (preview.height - caption.bottom).toFloat(),
                    )
                    caption.translationX = x
                    caption.translationY = y
                    workingX = if (preview.width == 0) 0f else x / preview.width
                    workingY = if (preview.height == 0) 0f else y / preview.height
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.live_caption_position_dialog_title)
            .setView(content)
            .setNeutralButton(R.string.live_caption_position_reset, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                preferences.edit {
                    putFloat(C.PLAYER_LIVE_CAPTION_POSITION_X, workingX)
                    putFloat(C.PLAYER_LIVE_CAPTION_POSITION_Y, workingY)
                }
            }
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        workingX = 0f
                        workingY = 0f
                        caption.translationX = 0f
                        caption.translationY = 0f
                    }
                }
            }
    }
}
