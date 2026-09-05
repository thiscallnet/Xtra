package com.github.andreyasadchy.xtra.ui.chat

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Poll
import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.google.android.material.button.MaterialButton

internal class HappeningNowView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    data class RenderState(
        val gift: HappeningNowGift?,
        val activePrediction: Prediction?,
        val recentPredictionResult: Prediction?,
        val activePoll: Poll?,
        val canBetPrediction: Boolean,
        val canVotePoll: Boolean,
        val newIds: Set<String>,
        val dismissedIds: Set<String>,
    )

    private val inflater = LayoutInflater.from(context)
    private val title: TextView
    private val chevron: ImageView
    private val newBadge: TextView
    private val cards: LinearLayout

    private var expanded = true
    private var visibleNewCount = 0

    init {
        orientation = VERTICAL
        inflater.inflate(R.layout.view_happening_now, this, true)

        title = findViewById(R.id.happeningTitle)
        chevron = findViewById(R.id.happeningChevron)
        newBadge = findViewById(R.id.happeningNewBadge)
        cards = findViewById(R.id.happeningCards)

        findViewById<View>(R.id.happeningHeader).setOnClickListener {
            expanded = !expanded
            updateExpandedState()
        }

        visibility = GONE
    }

    fun render(
        state: RenderState,
        onOpenChannelPoints: () -> Unit,
        onDismiss: (String) -> Unit,
    ) {
        cards.removeAllViews()

        val visibleKeys = mutableListOf<String>()

        state.gift?.let { gift ->
            val key = HappeningNowKeys.gift(gift.stableId)
            if (key !in state.dismissedIds) {
                addGiftCard(gift)
                visibleKeys += key
            }
        }

        state.activePrediction?.let { prediction ->
            val id = prediction.id?.takeIf { it.isNotBlank() }
            if (id != null) {
                val key = HappeningNowKeys.prediction(id)
                if (key !in state.dismissedIds) {
                    addPredictionCard(
                        prediction = prediction,
                        stableKey = key,
                        canBet = state.canBetPrediction,
                        historicalResult = false,
                        onOpenChannelPoints = onOpenChannelPoints,
                        onDismiss = onDismiss,
                    )
                    visibleKeys += key
                }
            }
        }

        state.recentPredictionResult?.let { prediction ->
            val id = prediction.id?.takeIf { it.isNotBlank() }
            if (id != null && id != state.activePrediction?.id) {
                val key = HappeningNowKeys.predictionResult(id)
                if (key !in state.dismissedIds) {
                    addPredictionCard(
                        prediction = prediction,
                        stableKey = key,
                        canBet = false,
                        historicalResult = true,
                        onOpenChannelPoints = onOpenChannelPoints,
                        onDismiss = onDismiss,
                    )
                    visibleKeys += key
                }
            }
        }

        state.activePoll?.let { poll ->
            val id = poll.id?.takeIf { it.isNotBlank() }
            if (id != null) {
                val key = HappeningNowKeys.poll(id)
                if (key !in state.dismissedIds) {
                    addPollCard(
                        poll = poll,
                        stableKey = key,
                        canVote = state.canVotePoll,
                        onOpenChannelPoints = onOpenChannelPoints,
                        onDismiss = onDismiss,
                    )
                    visibleKeys += key
                }
            }
        }

        val count = visibleKeys.size
        isVisible = count > 0

        if (count == 0) {
            return
        }

        title.text = context.getString(R.string.happening_now_title, count)

        visibleNewCount = visibleKeys.count(state.newIds::contains)
        newBadge.text = resources.getQuantityString(
            R.plurals.happening_now_new_events,
            visibleNewCount,
            visibleNewCount,
        )

        updateExpandedState()
    }

    private fun updateExpandedState() {
        cards.isVisible = expanded
        newBadge.isVisible = expanded && visibleNewCount > 0

        chevron.setImageResource(
            if (expanded) {
                R.drawable.ic_happening_now_chevron_up
            } else {
                R.drawable.ic_happening_now_chevron_down
            },
        )

        chevron.contentDescription = context.getString(
            if (expanded) {
                R.string.happening_now_collapse
            } else {
                R.string.happening_now_expand
            },
        )
    }

    private fun addGiftCard(gift: HappeningNowGift) {
        val view = inflater.inflate(
            R.layout.view_happening_now_gift_card,
            cards,
            false,
        )

        val giftText = view.findViewById<TextView>(R.id.happeningGiftText)
        val giftCount = view.findViewById<TextView>(R.id.happeningGiftCount)
        val text = context.getString(
            R.string.happening_now_gifted_subs,
            gift.gifterDisplayName,
        )

        giftText.text = SpannableString(text).apply {
            setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                gift.gifterDisplayName.length.coerceAtMost(length),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        giftCount.text = context.getString(
            R.string.happening_now_gift_count,
            gift.count,
        )

        cards.addView(view)
    }

    private fun addPredictionCard(
        prediction: Prediction,
        stableKey: String,
        canBet: Boolean,
        historicalResult: Boolean,
        onOpenChannelPoints: () -> Unit,
        onDismiss: (String) -> Unit,
    ) {
        val view = inflater.inflate(
            R.layout.view_happening_now_activity_card,
            cards,
            false,
        )

        val kicker = view.findViewById<TextView>(R.id.happeningKicker)
        val cardTitle = view.findViewById<TextView>(R.id.happeningCardTitle)
        val subtitle = view.findViewById<TextView>(R.id.happeningSubtitle)
        val action = view.findViewById<MaterialButton>(R.id.happeningAction)
        val more = view.findViewById<ImageButton>(R.id.happeningMore)
        val progress = view.findViewById<LinearLayout>(R.id.happeningProgress)
        val outcomes = prediction.outcomes.orEmpty()

        if (historicalResult) {
            kicker.isVisible = false
            cardTitle.text = prediction.title
                ?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.happening_now_prediction_result)

            val winner = outcomes.firstOrNull {
                !prediction.winningOutcomeId.isNullOrBlank() &&
                    it.id == prediction.winningOutcomeId
            }
            if (winner != null) {
                val points = TwitchApiHelper.formatCount(
                    winner.totalPoints ?: 0,
                    compact = true,
                )
                subtitle.text = context.getString(
                    R.string.happening_now_go_to,
                    points,
                    winner.title.orEmpty(),
                )
                subtitle.isVisible = true
            } else {
                subtitle.isVisible = false
            }

            action.setText(R.string.happening_now_see_details)
            action.setOnClickListener { onOpenChannelPoints() }
            progress.isVisible = false
        } else {
            kicker.setText(
                if (canBet) {
                    R.string.happening_now_predict_with_points
                } else {
                    R.string.happening_now_prediction
                },
            )

            val totals = happeningNowPredictionTotals(outcomes)
            cardTitle.text = prediction.title
                ?.takeIf { it.isNotBlank() }
                ?: totals
                ?: context.getString(R.string.happening_now_prediction)

            bindPredictionTotals(view, outcomes, subtitle)

            action.setText(
                if (canBet) {
                    R.string.happening_now_predict
                } else {
                    R.string.happening_now_see_details
                },
            )
            action.setOnClickListener {
                if (canBet) {
                    onOpenChannelPoints()
                } else {
                    onOpenChannelPoints()
                }
            }
            bindProgress(progress, outcomes.map { it.totalPoints ?: 0 })
        }

        bindDismissMenu(more, stableKey, onDismiss)
        view.findViewById<View>(R.id.happeningCardContent).setOnClickListener {
            toggleExpandedTitle(cardTitle)
        }
        cards.addView(view)
    }

    private fun addPollCard(
        poll: Poll,
        stableKey: String,
        canVote: Boolean,
        onOpenChannelPoints: () -> Unit,
        onDismiss: (String) -> Unit,
    ) {
        val view = inflater.inflate(
            R.layout.view_happening_now_activity_card,
            cards,
            false,
        )

        val kicker = view.findViewById<TextView>(R.id.happeningKicker)
        val cardTitle = view.findViewById<TextView>(R.id.happeningCardTitle)
        val subtitle = view.findViewById<TextView>(R.id.happeningSubtitle)
        val action = view.findViewById<MaterialButton>(R.id.happeningAction)
        val more = view.findViewById<ImageButton>(R.id.happeningMore)
        val progress = view.findViewById<LinearLayout>(R.id.happeningProgress)

        kicker.setText(R.string.happening_now_poll)
        cardTitle.text = poll.title.orEmpty()
        subtitle.text = context.getString(
            R.string.happening_now_votes,
            TwitchApiHelper.formatCount(poll.totalVotes ?: 0, compact = true),
        )
        subtitle.isVisible = true
        action.setText(
            if (canVote) {
                R.string.happening_now_vote
            } else {
                R.string.happening_now_see_details
            },
        )
        action.setOnClickListener { onOpenChannelPoints() }
        bindProgress(progress, poll.choices.orEmpty().map { it.totalVotes ?: 0 })
        bindDismissMenu(more, stableKey, onDismiss)
        view.findViewById<View>(R.id.happeningCardContent).setOnClickListener {
            toggleExpandedTitle(cardTitle)
        }
        cards.addView(view)
    }

    private fun bindPredictionTotals(
        view: View,
        outcomes: List<Prediction.PredictionOutcome>,
        subtitle: TextView,
    ) {
        val totals = view.findViewById<LinearLayout>(R.id.happeningPredictionTotals)
        totals.removeAllViews()
        subtitle.isVisible = false

        if (outcomes.size < 2) {
            totals.isVisible = false
            return
        }

        outcomes.take(2).forEachIndexed { index, outcome ->
            if (index > 0) {
                totals.addView(TextView(context).apply {
                    text = " vs "
                    setTextColor(ContextCompat.getColor(context, R.color.happeningNowTextSecondary))
                    textSize = 13f
                })
            }

            totals.addView(TextView(context).apply {
                text = TwitchApiHelper.formatCount(outcome.totalPoints ?: 0, compact = true)
                setTextColor(ContextCompat.getColor(context, R.color.happeningNowTextPrimary))
                textSize = 13f
                val dot = predictionColorDot(outcome, index, outcomes.size)
                dot.setBounds(0, 0, dp(8), dp(8))
                setCompoundDrawablesRelative(dot, null, null, null)
                compoundDrawablePadding = dp(4)
            })
        }

        totals.isVisible = true
    }

    private fun predictionColorDot(
        outcome: Prediction.PredictionOutcome,
        index: Int,
        outcomeCount: Int,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(predictionColor(outcome, index, outcomeCount))
        setSize(dp(8), dp(8))
    }

    private fun predictionColor(
        outcome: Prediction.PredictionOutcome,
        index: Int,
        outcomeCount: Int,
    ): Int = when {
        outcomeCount > 2 -> Color.rgb(70, 132, 255)
        outcome.color.equals("PINK", ignoreCase = true) -> Color.rgb(238, 23, 153)
        outcome.color.equals("BLUE", ignoreCase = true) -> Color.rgb(70, 132, 255)
        outcomeCount == 2 && index == 1 -> Color.rgb(238, 23, 153)
        else -> Color.rgb(70, 132, 255)
    }

    private fun toggleExpandedTitle(title: TextView) {
        val expanded = title.maxLines != 1
        title.maxLines = if (expanded) 1 else Int.MAX_VALUE
        title.ellipsize = if (expanded) android.text.TextUtils.TruncateAt.END else null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun bindProgress(container: LinearLayout, values: List<Int>) {
        container.removeAllViews()

        if (values.size < 2) {
            container.isVisible = false
            return
        }

        container.isVisible = true
        val allZero = values.all { it <= 0 }
        values.forEachIndexed { index, value ->
            val segment = View(context).apply {
                setBackgroundColor(
                    ContextCompat.getColor(
                        context,
                        if (index % 2 == 0) {
                            R.color.happeningNowProgressLight
                        } else {
                            R.color.happeningNowProgressDark
                        },
                    ),
                )
            }
            container.addView(
                segment,
                LayoutParams(
                    0,
                    LayoutParams.MATCH_PARENT,
                    if (allZero) 1f else value.coerceAtLeast(1).toFloat(),
                ),
            )
        }
    }

    private fun bindDismissMenu(
        anchor: ImageButton,
        stableKey: String,
        onDismiss: (String) -> Unit,
    ) {
        anchor.setOnClickListener {
            PopupMenu(context, anchor).apply {
                menu.add(R.string.happening_now_dismiss)
                setOnMenuItemClickListener {
                    onDismiss(stableKey)
                    true
                }
                show()
            }
        }
    }
}
