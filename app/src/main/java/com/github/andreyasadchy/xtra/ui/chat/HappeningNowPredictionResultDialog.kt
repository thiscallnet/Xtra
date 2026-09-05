package com.github.andreyasadchy.xtra.ui.chat

import android.app.Dialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder

class HappeningNowPredictionResultDialog : DialogFragment() {

    companion object {
        const val TAG = "happeningNowPredictionResult"

        private const val ARG_TITLE = "title"
        private const val ARG_OUTCOME_TITLES = "outcomeTitles"
        private const val ARG_OUTCOME_POINTS = "outcomePoints"
        private const val ARG_WINNER_INDEX = "winnerIndex"

        fun newInstance(prediction: Prediction): HappeningNowPredictionResultDialog {
            val outcomes = prediction.outcomes.orEmpty()
            return HappeningNowPredictionResultDialog().apply {
                arguments = bundleOf(
                    ARG_TITLE to prediction.title.orEmpty(),
                    ARG_OUTCOME_TITLES to ArrayList(outcomes.map { it.title.orEmpty() }),
                    ARG_OUTCOME_POINTS to ArrayList(outcomes.map { it.totalPoints ?: 0 }),
                    ARG_WINNER_INDEX to happeningNowWinnerIndex(prediction),
                )
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val args = requireArguments()
        val titles = args.getStringArrayList(ARG_OUTCOME_TITLES).orEmpty()
        val points = args.getIntegerArrayList(ARG_OUTCOME_POINTS).orEmpty()
        val winnerIndex = args.getInt(ARG_WINNER_INDEX, -1)

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(8))
        }

        titles.forEachIndexed { index, title ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }
            val outcomeText = TextView(context).apply {
                text = if (index == winnerIndex) {
                    getString(R.string.happening_now_winner, title)
                } else {
                    title
                }
                if (index == winnerIndex) setTypeface(typeface, Typeface.BOLD)
            }
            val pointsText = TextView(context).apply {
                text = TwitchApiHelper.formatCount(
                    points.getOrElse(index) { 0 },
                    compact = false,
                )
                gravity = Gravity.END
                if (index == winnerIndex) setTypeface(typeface, Typeface.BOLD)
            }
            row.addView(
                outcomeText,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(
                pointsText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            content.addView(row)
        }

        return context.getAlertDialogBuilder()
            .setTitle(
                args.getString(ARG_TITLE)
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.happening_now_prediction_result),
            )
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
