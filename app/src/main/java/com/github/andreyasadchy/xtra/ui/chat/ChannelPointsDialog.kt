package com.github.andreyasadchy.xtra.ui.chat

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.request.target
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.DialogChannelPointsBinding
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.model.chat.Poll
import com.github.andreyasadchy.xtra.model.chat.PollVoteState
import com.github.andreyasadchy.xtra.model.chat.Prediction
import com.github.andreyasadchy.xtra.model.chat.PredictionBetState
import com.github.andreyasadchy.xtra.model.ui.ChannelPoints
import com.github.andreyasadchy.xtra.model.ui.ChannelPointReward
import com.github.andreyasadchy.xtra.model.ui.ChannelPointRewardInput
import com.github.andreyasadchy.xtra.model.ui.ChannelPointRewardRedemption
import com.github.andreyasadchy.xtra.model.ui.WatchStreak
import com.github.andreyasadchy.xtra.ui.view.GridAutofitLayoutManager
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.chat.PollState
import com.github.andreyasadchy.xtra.util.chat.PredictionState
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.max
import kotlin.math.roundToInt

class ChannelPointsDialog : DialogFragment() {

    interface Listener {
        fun channelPointsFlow(): StateFlow<ChannelPoints?>
        fun watchStreakFlow(): StateFlow<WatchStreak?>
        fun pollFlow(): StateFlow<Poll?>
        fun activePollFlow(): StateFlow<Poll?>
        fun pollSecondsLeftFlow(): StateFlow<Int?>
        fun predictionFlow(): StateFlow<Prediction?>
        fun ongoingPredictionFlow(): StateFlow<Prediction?>
        fun predictionSecondsLeftFlow(): StateFlow<Int?>
        fun pollVoteStateFlow(): StateFlow<PollVoteState>
        fun canVotePoll(): Boolean
        fun votePoll(choiceId: String)
        fun dismissPoll()
        fun predictionBetStateFlow(): StateFlow<PredictionBetState>
        fun canBetPrediction(): Boolean
        fun betPrediction(outcomeId: String, points: Int)
        fun channelName(): String?
        fun channelEmotePickerItems(): List<Emote>
        fun channelEmotePickerUpdates(): Flow<Unit>
        fun channelPointModifiedEmotePickerItems(): List<Emote>
        fun channelPointModifiedEmotePickerUpdates(): Flow<Unit>
        fun redeemChannelPointReward(reward: ChannelPointReward, textInput: String?, emoteId: String?)
        fun startChannelPointReward(reward: ChannelPointReward)
        fun startWatchStreakShare(streak: WatchStreak)
    }

    companion object {
        const val TAG = "channelPointsDialog"
        private const val REWARD_COLUMNS = 3
        private const val MIN_PREDICTION_POINTS = 10
        private const val MAX_PREDICTION_POINTS = 250_000
        private val BLUE_PREDICTION_COLOR = Color.rgb(70, 132, 255)
        private val PINK_PREDICTION_COLOR = Color.rgb(238, 23, 153)
    }

    private var _binding: DialogChannelPointsBinding? = null
    private val binding get() = _binding!!
    private lateinit var listener: Listener
    private var predictionDraftId: String? = null
    private var predictionAmountDraft = MIN_PREDICTION_POINTS.toString()
    private var predictionAmountWatcher: TextWatcher? = null

    private data class RewardInputContent(
        val input: EditText,
        val view: View,
        val pickerUpdates: Flow<Unit>? = null,
        val refreshPicker: (() -> Unit)? = null,
        val selectedEmoteId: (() -> String?)? = null,
    )

    override fun onAttach(context: Context) {
        super.onAttach(context)
        listener = parentFragment as? Listener
            ?: error("ChannelPointsDialog must be shown by a ChannelPointsDialog.Listener")
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogChannelPointsBinding.inflate(layoutInflater)
        binding.close.setOnClickListener { dismiss() }
        binding.pollDismiss.setOnClickListener { listener.dismissPoll() }
        predictionAmountWatcher = binding.predictionBetAmount.addTextChangedListener { text ->
            if (predictionDraftId != null) {
                predictionAmountDraft = text?.toString().orEmpty()
            }
        }
        val dialog = requireContext().getAlertDialogBuilder()
            .setView(binding.root)
            .create()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val basicState = combine(
                    listener.channelPointsFlow(),
                    listener.watchStreakFlow(),
                    listener.pollFlow(),
                    listener.predictionFlow(),
                ) { channelPoints, watchStreak, poll, prediction ->
                    BasicDialogState(channelPoints, watchStreak, poll, prediction)
                }
                val activeTimingState = combine(
                    listener.activePollFlow(),
                    listener.ongoingPredictionFlow(),
                    listener.pollSecondsLeftFlow(),
                    listener.predictionSecondsLeftFlow(),
                ) { activePoll, ongoingPrediction, pollSeconds, predictionSeconds ->
                    ActiveTimingState(activePoll, ongoingPrediction, pollSeconds, predictionSeconds)
                }
                val activeState = combine(
                    activeTimingState,
                    listener.pollVoteStateFlow(),
                    listener.predictionBetStateFlow(),
                ) { timing, pollVoteState, predictionBetState ->
                    ActiveDialogState(
                        timing.activePoll,
                        timing.ongoingPrediction,
                        timing.pollSecondsLeft,
                        timing.predictionSecondsLeft,
                        pollVoteState,
                        predictionBetState,
                    )
                }
                combine(basicState, activeState) { basic, active ->
                    DialogState(
                        channelPoints = basic.channelPoints,
                        watchStreak = basic.watchStreak,
                        poll = basic.poll,
                        activePoll = active.activePoll,
                        pollSecondsLeft = active.pollSecondsLeft,
                        prediction = basic.prediction,
                        ongoingPrediction = active.ongoingPrediction,
                        predictionSecondsLeft = active.predictionSecondsLeft,
                        pollVoteState = active.pollVoteState,
                        predictionBetState = active.predictionBetState,
                    )
                }.collectLatest(::render)
            }
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val density = resources.displayMetrics.density
            val maxWidth = (640 * density).roundToInt()
            val screenWidth = resources.displayMetrics.widthPixels
            val width = (screenWidth * 0.94f).roundToInt().coerceAtMost(maxWidth)
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun render(state: DialogState) {
        val numberFormat = NumberFormat.getInstance()
        val points = state.channelPoints
        binding.headerTitle.text = listener.channelName()?.let {
            getString(R.string.channel_points_rewards_for, it)
        } ?: getString(R.string.channel_points_watch_streak)
        binding.balance.text = points?.let {
            getString(R.string.channel_points_current_balance, numberFormat.format(it.balance))
        } ?: getString(R.string.channel_points_unavailable)
        setChannelPointsIcon(
            binding.balanceIcon,
            points?.iconUrl,
            MaterialColors.getColor(binding.balanceIcon, androidx.appcompat.R.attr.colorControlNormal),
        )

        renderPrediction(
            state.prediction,
            state.ongoingPrediction,
            state.predictionSecondsLeft,
            state.predictionBetState,
            state.channelPoints,
            numberFormat,
        )
        renderPoll(state.poll, state.activePoll, state.pollSecondsLeft, state.pollVoteState, numberFormat)
        renderWatchStreak(state.watchStreak, points, numberFormat)
        renderRewards(points, numberFormat)
    }

    private fun renderWatchStreak(
        streak: WatchStreak?,
        points: ChannelPoints?,
        numberFormat: NumberFormat,
    ) {
        binding.streakNoticeCard.isVisible = false
        binding.streakSummary.isVisible = streak != null
        binding.streakLabel.isVisible = streak != null
        binding.streakStatusLabel.isVisible = false
        binding.streakProgressCard.isVisible = false
        binding.streakShare.isVisible = false
        binding.streakProgressValue.isVisible = true
        binding.streakEmpty.isVisible = streak == null

        if (streak != null) {
            binding.streakCount.text = numberFormat.format(streak.streakCount)
            val next = streak.nextMilestone?.takeIf { it > 0 }
            if (next != null) {
                val distance = (next - streak.streakCount).coerceAtLeast(0)
                binding.streakNotice.text = if (distance > 0) {
                    getString(R.string.channel_points_streak_notice, distance, next)
                } else {
                    getString(R.string.channel_points_streak_reached)
                }
                binding.streakNoticeCard.isVisible = true
                binding.streakStatusLabel.isVisible = true
                binding.streakStatusLabel.setText(
                    if (distance > 0) {
                        R.string.channel_points_streak_in_progress
                    } else {
                        R.string.channel_points_streak_reached
                    },
                )
                binding.streakProgressCard.isVisible = true
                binding.streakProgressTitle.text = getString(
                    R.string.channel_points_streak_milestone,
                    next,
                )
                val canShare = distance == 0 &&
                        streak.shareStatus.equals(WatchStreak.SHARE_STATUS_CAN_SHARE, ignoreCase = true) &&
                        !streak.milestoneId.isNullOrBlank()
                binding.streakShare.isVisible = canShare
                binding.streakProgressValue.isVisible = !canShare
                if (canShare) {
                    binding.streakShare.setOnClickListener {
                        listener.startWatchStreakShare(streak)
                        dismiss()
                    }
                }
                binding.streakProgressValue.text = getString(
                    R.string.channel_points_streak_progress,
                    streak.streakCount.coerceAtMost(next),
                    next,
                )
                binding.streakProgress.max = 100
                binding.streakProgress.progress = ((streak.streakCount.toDouble() / next) * 100)
                    .roundToInt()
                    .coerceIn(0, 100)
                binding.streakDescription.text = streak.rewardPoints?.let {
                    getString(
                        R.string.channel_points_streak_description,
                        next,
                        numberFormat.format(it),
                    )
                } ?: getString(R.string.channel_points_streak_description_no_reward, next)
            }
        }

        val streakRewards = points?.watchStreakRewards.orEmpty()
        binding.streakRewardsTitle.isVisible = streakRewards.isNotEmpty()
        binding.streakRewards.isVisible = streakRewards.isNotEmpty()
        binding.streakRewards.removeAllViews()
        streakRewards.forEach { reward ->
            addRow(
                binding.streakRewards,
                reward.streakLength?.let {
                    getString(
                        R.string.channel_points_streak_reward,
                        if (it >= 5) "5+" else it.toString(),
                        numberFormat.format(reward.points),
                    )
                } ?: getString(
                    R.string.channel_points_streak_reward_unknown,
                    numberFormat.format(reward.points),
                ),
            )
        }
    }

    private fun renderRewards(points: ChannelPoints?, numberFormat: NumberFormat) {
        val rewards = points?.rewards.orEmpty()
        binding.rewardsTitle.isVisible = rewards.isNotEmpty()
        binding.rewardsList.isVisible = rewards.isNotEmpty()
        binding.rewardsList.removeAllViews()
        rewards.forEachIndexed { index, reward ->
            addRewardCard(index, reward, numberFormat, points?.iconUrl)
        }
    }

    private fun addRewardCard(
        index: Int,
        reward: ChannelPointReward,
        numberFormat: NumberFormat,
        pointsIconUrl: String?,
    ) {
        val density = resources.displayMetrics.density
        val card = MaterialCardView(requireContext()).apply {
            radius = 4 * density
            strokeWidth = 0
            setCardBackgroundColor(parseColor(reward.backgroundColor, R.color.channel_points_reward_default))
            isClickable = true
            isFocusable = true
            contentDescription = reward.title
            setOnClickListener {
                if (reward.inputType == ChannelPointRewardInput.TEXT) {
                    listener.startChannelPointReward(reward)
                    dismiss()
                } else {
                    showRewardRedemptionDialog(reward)
                }
            }
        }
        val content = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val image = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(R.drawable.ic_channel_points)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }
        reward.imageUrl?.let { imageUrl ->
            image.imageTintList = null
            requireContext().imageLoader.enqueue(
                ImageRequest.Builder(requireContext())
                    .data(imageUrl)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .crossfade(true)
                    .target(image)
                    .build(),
            )
        }
        content.addView(image)
        content.addView(TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(36),
            ).apply {
                topMargin = dp(2)
            }
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(Color.WHITE)
            text = reward.title
            textSize = 12f
        })
        val cost = LinearLayout(requireContext()).apply {
            background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_channel_points_pill)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        cost.addView(ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
            setChannelPointsIcon(this, pointsIconUrl, Color.WHITE)
        })
        cost.addView(TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(3)
            }
            setTextColor(Color.WHITE)
            text = numberFormat.format(reward.cost)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(cost)
        card.addView(content)
        binding.rewardsList.addView(card, android.widget.GridLayout.LayoutParams().apply {
            width = 0
            height = dp(132)
            rowSpec = android.widget.GridLayout.spec(index / REWARD_COLUMNS)
            columnSpec = android.widget.GridLayout.spec(index % REWARD_COLUMNS, 1f)
            setMargins(dp(2), dp(2), dp(2), dp(2))
        })
    }

    private fun showRewardRedemptionDialog(reward: ChannelPointReward) {
        val inputContent = createRewardInputContent(reward)
        val dialog = requireContext().getAlertDialogBuilder()
            .setTitle(reward.title)
            .setMessage(reward.prompt ?: getString(R.string.channel_points_reward_confirm))
            .apply { inputContent?.let { setView(it.view) } }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.channel_points_reward_redeem) { _, _ ->
                val textInput = inputContent?.input?.text?.toString()
                    ?.takeIf { reward.inputType == ChannelPointRewardInput.TEXT }
                listener.redeemChannelPointReward(
                    reward,
                    textInput,
                    inputContent?.selectedEmoteId?.invoke(),
                )
            }
            .create()
        val pickerUpdatesJob: Job? = inputContent?.let { content ->
            content.pickerUpdates?.let { updates ->
                lifecycleScope.launch {
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        updates.collectLatest { content.refreshPicker?.invoke() }
                    }
                }
            }
        }
        dialog.setOnDismissListener { pickerUpdatesJob?.cancel() }
        dialog.show()
        inputContent?.let { content ->
            val redeemButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
            fun updateRedeemButton() {
                redeemButton.isEnabled = when (reward.inputType) {
                    ChannelPointRewardInput.NONE -> true
                    ChannelPointRewardInput.TEXT -> content.input.text.isNotBlank()
                    ChannelPointRewardInput.EMOTE -> content.selectedEmoteId?.invoke()?.isNotBlank() == true
                }
            }
            content.input.addTextChangedListener { updateRedeemButton() }
            updateRedeemButton()
        }
    }

    private fun createRewardInputContent(reward: ChannelPointReward): RewardInputContent? {
        return when (reward.inputType) {
            ChannelPointRewardInput.NONE -> null
            ChannelPointRewardInput.TEXT -> {
                val input = EditText(requireContext()).apply {
                    hint = getString(R.string.channel_points_reward_text_hint)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    minLines = 2
                    maxLines = 4
                    gravity = Gravity.TOP or Gravity.START
                }
                RewardInputContent(
                    input = input,
                    view = LinearLayout(requireContext()).apply {
                        setPadding(dp(24), 0, dp(24), 0)
                        addView(input, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ))
                    },
                )
            }
            ChannelPointRewardInput.EMOTE -> {
                var selectedEmoteId: String? = null
                val modified = reward.redemptionType == ChannelPointRewardRedemption.CHOSEN_MODIFIED_SUB_EMOTE
                val input = EditText(requireContext()).apply {
                    hint = getString(R.string.channel_points_reward_emote_hint)
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSingleLine(true)
                }
                var allEmotes = if (modified) {
                    listener.channelPointModifiedEmotePickerItems()
                } else {
                    listener.channelEmotePickerItems()
                }
                val adapter = EmotesAdapter(
                    this,
                    { emote ->
                        selectedEmoteId = emote.id
                        input.setText(emote.name.orEmpty())
                        input.setSelection(input.text.length)
                    },
                    "4",
                    "0",
                )
                val picker = RecyclerView(requireContext()).apply {
                    itemAnimator = null
                    setPadding(dp(10), 0, dp(10), 0)
                    this.adapter = adapter
                    layoutManager = GridAutofitLayoutManager(requireContext(), dp(50))
                }
                fun updatePicker() {
                    val query = input.text.toString()
                    adapter.submitList(
                        allEmotes.filter { it.name.orEmpty().contains(query, ignoreCase = true) },
                    )
                }
                val refreshPicker: () -> Unit = {
                    allEmotes = if (modified) {
                        listener.channelPointModifiedEmotePickerItems()
                    } else {
                        listener.channelEmotePickerItems()
                    }
                    updatePicker()
                }
                input.addTextChangedListener { text ->
                    selectedEmoteId = allEmotes.firstOrNull {
                        it.name.equals(text?.toString(), ignoreCase = true)
                    }?.id
                    updatePicker()
                }
                updatePicker()
                RewardInputContent(
                    input = input,
                    view = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(24), 0, dp(24), 0)
                        addView(input, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ))
                        addView(picker, LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(180),
                        ).apply {
                            topMargin = dp(8)
                        })
                    },
                    pickerUpdates = if (modified) {
                        listener.channelPointModifiedEmotePickerUpdates()
                    } else {
                        listener.channelEmotePickerUpdates()
                    },
                    refreshPicker = refreshPicker,
                    selectedEmoteId = { selectedEmoteId },
                )
            }
        }
    }

    private fun setChannelPointsIcon(image: ImageView, imageUrl: String?, fallbackTint: Int) {
        image.setImageResource(R.drawable.ic_channel_points)
        image.imageTintList = ColorStateList.valueOf(fallbackTint)
        if (!imageUrl.isNullOrBlank()) {
            image.imageTintList = null
            requireContext().imageLoader.enqueue(
                ImageRequest.Builder(requireContext())
                    .data(imageUrl)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .crossfade(true)
                    .target(image)
                    .build(),
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun parseColor(value: String?, fallback: Int): Int {
        return runCatching { Color.parseColor(value ?: "") }.getOrElse {
            ContextCompat.getColor(requireContext(), fallback)
        }
    }

    private fun renderPoll(
        poll: Poll?,
        activePoll: Poll?,
        pollSecondsLeft: Int?,
        pollVoteState: PollVoteState,
        numberFormat: NumberFormat,
    ) {
        binding.pollCard.isVisible = poll != null
        binding.pollChoices.removeAllViews()
        if (poll == null) return

        val status = poll.status.orEmpty().uppercase()
        val isActive = activePoll != null && PollState.isActive(poll)
        binding.pollCardTitle.text = getString(
            if (isActive) R.string.channel_points_poll else R.string.channel_points_last_poll,
            poll.title.orEmpty(),
        )
        val displayStatus = if (!isActive && status == "ACTIVE" && (poll.endsAt?.let { it <= System.currentTimeMillis() } == true || poll.remainingMilliseconds == 0L)) {
            "COMPLETED"
        } else status
        val totalVotes = (poll.totalVotes ?: poll.choices.orEmpty().sumOf { it.totalVotes ?: 0 }).coerceAtLeast(0)
        val statusText = if (isActive) {
            getString(
                R.string.channel_points_poll_active_with_time,
                pollSecondsLeft?.let { android.text.format.DateUtils.formatElapsedTime(it.toLong()) } ?: "—",
            )
        } else if (displayStatus.isBlank()) {
            getString(R.string.channel_points_poll_status_unknown)
        } else {
            getString(R.string.channel_points_poll_status, displayStatus)
        }
        val details = buildList {
            add(statusText)
            add(getString(R.string.channel_points_poll_total_votes, numberFormat.format(totalVotes)))
            poll.channelPointsPerVote?.let { add(getString(R.string.channel_points_poll_channel_points, numberFormat.format(it))) }
            poll.bitsPerVote?.let { add(getString(R.string.channel_points_poll_bits, numberFormat.format(it))) }
            poll.startedAt?.let { add(getString(R.string.channel_points_poll_started, TwitchApiHelper.formatDate(requireContext(), it))) }
            poll.endedAt?.let { add(getString(R.string.channel_points_poll_ended, TwitchApiHelper.formatDate(requireContext(), it))) }
            poll.durationSeconds?.let { add(getString(R.string.channel_points_poll_duration, android.text.format.DateUtils.formatElapsedTime(it.toLong()))) }
            pollVoteState.error?.takeIf { pollVoteState.pollId == poll.id }?.let {
                add(getString(R.string.channel_points_poll_vote_error, it))
            }
        }
        binding.pollCardStatus.text = details.joinToString(" · ")
        val maxVotes = poll.choices.orEmpty().mapNotNull { it.totalVotes }.maxOrNull()
        val winners = poll.choices.orEmpty().filter { maxVotes != null && it.totalVotes == maxVotes }
        val voteStateMatches = pollVoteState.pollId == poll.id
        val canVote = isActive && listener.canVotePoll() && voteStateMatches &&
                pollVoteState.pendingChoiceId == null && pollVoteState.selectedChoiceId == null
        poll.choices.orEmpty().forEachIndexed { index, choice ->
            val percent = if (totalVotes > 0) {
                (((choice.totalVotes ?: 0).toLong() * 100.0) / totalVotes).roundToInt()
            } else 0
            val prefix = if (!isActive && maxVotes != null && maxVotes > 0 && winners.contains(choice)) "🏆 " else ""
            val voteDetails = buildList {
                add(numberFormat.format(choice.totalVotes ?: 0))
                choice.channelPointsVotes?.let { add("${numberFormat.format(it)} CP") }
                choice.bitsVotes?.let { add("${numberFormat.format(it)} bits") }
            }.joinToString(" · ")
            val choiceText = "$prefix$percent% · $voteDetails · ${choice.title}"
            val selected = voteStateMatches && pollVoteState.selectedChoiceId == choice.id
            val pending = voteStateMatches && pollVoteState.pendingChoiceId == choice.id
            val showButton = isActive && choice.id != null &&
                    (canVote || selected || pending || (voteStateMatches && pollVoteState.inFlight))
            if (showButton) {
                binding.pollChoices.addView(
                    MaterialButton(requireContext()).apply {
                        this.text = when {
                            selected -> getString(R.string.channel_points_poll_choice_selected, choiceText)
                            pending -> getString(R.string.channel_points_poll_choice_pending, choiceText)
                            else -> choiceText
                        }
                        isAllCaps = false
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        isCheckable = true
                        isChecked = selected
                        isEnabled = canVote && !selected && !pending
                        if (selected) {
                            strokeWidth = dp(2)
                            strokeColor = ColorStateList.valueOf(
                                MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary),
                            )
                        }
                        setOnClickListener {
                            listener.votePoll(choice.id)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            bottomMargin = dp(4)
                        }
                    },
                )
            } else {
                addRow(
                    binding.pollChoices,
                    if (selected) getString(R.string.channel_points_poll_choice_selected, choiceText) else choiceText,
                )
            }
        }
    }

    private fun renderPrediction(
        prediction: Prediction?,
        ongoingPrediction: Prediction?,
        predictionSecondsLeft: Int?,
        predictionBetState: PredictionBetState,
        channelPoints: ChannelPoints?,
        numberFormat: NumberFormat,
    ) {
        binding.predictionCard.isVisible = prediction != null
        binding.predictionOutcomes.removeAllViews()
        if (prediction == null) {
            resetPredictionDraftIfNeeded(null)
            binding.predictionBetRow.isVisible = false
            binding.predictionBetHint.isVisible = false
            return
        }
        resetPredictionDraftIfNeeded(prediction.id)

        binding.predictionCardTitle.text = getString(R.string.channel_points_prediction, prediction.title.orEmpty())
        val status = PredictionState.status(prediction)
        val isBettingOpen = ongoingPrediction != null && PredictionState.isBettingOpen(prediction)
        binding.predictionCardStatus.text = when {
            isBettingOpen -> predictionSecondsLeft?.let {
                getString(
                    R.string.channel_points_prediction_active_with_time,
                    android.text.format.DateUtils.formatElapsedTime(it.toLong()),
                )
            } ?: getString(R.string.channel_points_prediction_active)
            status == "CANCEL_PENDING" -> getString(R.string.channel_points_prediction_cancel_pending)
            PredictionState.isOngoing(prediction) -> getString(R.string.channel_points_prediction_locked)
            PredictionState.isFinal(prediction) && status in setOf("CANCELED", "CANCELLED", "REFUNDED") -> getString(R.string.channel_points_prediction_canceled)
            PredictionState.isFinal(prediction) -> getString(R.string.channel_points_prediction_resolved)
            else -> getString(R.string.channel_points_prediction_closed)
        }

        val outcomes = prediction.outcomes.orEmpty()
        val betStateMatches = predictionBetState.predictionId == prediction.id
        val selectedOutcomeId = predictionBetState.outcomeId.takeIf { betStateMatches }
        val betInFlight = betStateMatches && predictionBetState.inFlight
        val predictionBetError = predictionBetState.error.takeIf { betStateMatches }
        val totalPoints = outcomes.mapNotNull { it.totalPoints }.sum().takeIf { it > 0 }
        val totalUsers = outcomes.mapNotNull { it.totalUsers }.sum().takeIf { it > 0 }
        binding.predictionCardMeta.text = buildList {
            totalPoints?.let { add(getString(R.string.channel_points_prediction_total_points, numberFormat.format(it))) }
            totalUsers?.let { add(getString(R.string.channel_points_prediction_total_users, numberFormat.format(it))) }
            prediction.startedAt?.let {
                val relative = android.text.format.DateUtils.getRelativeTimeSpanString(
                    it,
                    System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS,
                    android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE,
                )
                add(getString(R.string.channel_points_prediction_started_ago, relative))
            }
            prediction.lockedAt?.let { add(getString(R.string.channel_points_prediction_locked_at, TwitchApiHelper.formatDate(requireContext(), it))) }
            prediction.endedAt?.let { add(getString(R.string.channel_points_prediction_ended, TwitchApiHelper.formatDate(requireContext(), it))) }
            prediction.startedAt?.let { started ->
                val end = prediction.endedAt ?: prediction.lockedAt
                if (end != null && end >= started) {
                    add(getString(R.string.channel_points_prediction_duration, android.text.format.DateUtils.formatElapsedTime(((end - started) / 1_000L).coerceAtLeast(0L))))
                }
            }
        }.joinToString(" · ")
        binding.predictionCardMeta.isVisible = binding.predictionCardMeta.text.isNotBlank()

        val twoOutcomePrediction = outcomes.size == 2 && outcomes.all { !it.id.isNullOrBlank() }
        binding.predictionOutcomes.orientation = if (twoOutcomePrediction) {
            LinearLayout.HORIZONTAL
        } else {
            LinearLayout.VERTICAL
        }
        outcomes.forEachIndexed { index, outcome ->
            addPredictionOutcome(
                container = binding.predictionOutcomes,
                outcome = outcome,
                index = index,
                outcomeCount = outcomes.size,
                totalPoints = totalPoints,
                numberFormat = numberFormat,
                winner = PredictionState.isFinal(prediction) && prediction.winningOutcomeId == outcome.id,
                tie = status == "RESOLVED" && prediction.winningOutcomeId.isNullOrBlank() &&
                    totalPoints != null && outcome.totalPoints == outcomes.mapNotNull { it.totalPoints }.maxOrNull(),
                viewerSelected = selectedOutcomeId == outcome.id,
                viewerAmount = predictionBetState.amount.takeIf { betStateMatches && selectedOutcomeId == outcome.id },
            )
        }

        val canBet = isBettingOpen && twoOutcomePrediction && listener.canBetPrediction()
        if (binding.predictionBetRow.isVisible != canBet) {
            binding.predictionBetRow.isVisible = canBet
        }
        val hint = when {
            predictionBetError != null -> predictionBetError
            betInFlight -> getString(R.string.channel_points_prediction_bet_pending)
            selectedOutcomeId != null -> getString(
                R.string.channel_points_prediction_bet_selected,
                numberFormat.format(predictionBetState.amount ?: 0),
            )
            isBettingOpen && !listener.canBetPrediction() -> getString(R.string.channel_points_prediction_bet_login)
            isBettingOpen && !twoOutcomePrediction -> getString(R.string.channel_points_prediction_bet_unavailable)
            isBettingOpen && channelPoints != null && channelPoints.balance < MIN_PREDICTION_POINTS -> getString(
                R.string.channel_points_prediction_bet_balance,
                numberFormat.format(channelPoints.balance),
            )
            else -> null
        }
        if (binding.predictionBetHint.isVisible != (hint != null)) {
            binding.predictionBetHint.isVisible = hint != null
        }
        binding.predictionBetHint.text = hint
        if (canBet) {
            binding.predictionBetAmount.apply {
                if (inputType != InputType.TYPE_CLASS_NUMBER) {
                    inputType = InputType.TYPE_CLASS_NUMBER
                }
                setSelectAllOnFocus(true)
            }
            binding.predictionBetLeft.text = outcomes[0].title
            binding.predictionBetRight.text = outcomes[1].title
            stylePredictionButton(binding.predictionBetLeft, BLUE_PREDICTION_COLOR)
            stylePredictionButton(binding.predictionBetRight, PINK_PREDICTION_COLOR)
            binding.predictionBetLeft.isEnabled = !betInFlight && selectedOutcomeId == null
            binding.predictionBetRight.isEnabled = !betInFlight && selectedOutcomeId == null
            binding.predictionBetLeft.setOnClickListener {
                placePredictionBet(binding.predictionBetAmount, outcomes[0].id)
            }
            binding.predictionBetRight.setOnClickListener {
                placePredictionBet(binding.predictionBetAmount, outcomes[1].id)
            }
        }
    }

    private fun resetPredictionDraftIfNeeded(predictionId: String?) {
        if (predictionId == null) {
            predictionDraftId = null
            predictionAmountDraft = MIN_PREDICTION_POINTS.toString()
            return
        }
        if (predictionDraftId == predictionId) return
        predictionDraftId = predictionId
        predictionAmountDraft = MIN_PREDICTION_POINTS.toString()
        if (binding.predictionBetAmount.text?.toString() != predictionAmountDraft) {
            binding.predictionBetAmount.setText(predictionAmountDraft)
            binding.predictionBetAmount.setSelection(binding.predictionBetAmount.length())
        }
    }

    private fun addPredictionOutcome(
        container: LinearLayout,
        outcome: Prediction.PredictionOutcome,
        index: Int,
        outcomeCount: Int,
        totalPoints: Int?,
        numberFormat: NumberFormat,
        winner: Boolean,
        tie: Boolean,
        viewerSelected: Boolean,
        viewerAmount: Int?,
    ) {
        val color = when {
            outcome.color.equals("PINK", true) -> PINK_PREDICTION_COLOR
            outcome.color.equals("BLUE", true) -> BLUE_PREDICTION_COLOR
            outcomeCount == 2 && index == 1 -> PINK_PREDICTION_COLOR
            else -> BLUE_PREDICTION_COLOR
        }
        val percent = if (totalPoints != null && totalPoints > 0 && outcome.totalPoints != null) {
            ((outcome.totalPoints.toLong() * 100.0) / totalPoints).roundToInt().coerceIn(0, 100)
        } else null
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.TRANSPARENT)
                if (viewerSelected) {
                    setStroke(
                        dp(2),
                        MaterialColors.getColor(this@ChannelPointsDialog.binding.predictionCard, androidx.appcompat.R.attr.colorPrimary),
                    )
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                if (outcomeCount == 2) 0 else LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (outcomeCount == 2) weight = 1f
                bottomMargin = dp(4)
            }
        }
        content.addView(TextView(requireContext()).apply {
            text = outcome.title
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(color)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (winner || tie) {
            content.addView(TextView(requireContext()).apply {
                text = getString(if (winner) R.string.channel_points_prediction_winner else R.string.channel_points_prediction_tie)
                gravity = Gravity.CENTER
                setTextColor(MaterialColors.getColor(binding.predictionCard, androidx.appcompat.R.attr.colorControlNormal))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        if (viewerSelected) {
            content.addView(TextView(requireContext()).apply {
                text = getString(
                    R.string.channel_points_prediction_bet_selected,
                    numberFormat.format(viewerAmount ?: 0),
                )
                gravity = Gravity.CENTER
                setTextColor(MaterialColors.getColor(binding.predictionCard, androidx.appcompat.R.attr.colorPrimary))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        content.addView(TextView(requireContext()).apply {
            text = percent?.let { "$it%" } ?: "—"
            gravity = Gravity.CENTER
            setTextColor(color)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (outcomeCount == 2) {
            val meter = FrameLayout(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(8),
                ).apply { topMargin = dp(2) }
            }
            percent?.let { value ->
                val fill = View(requireContext()).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(4).toFloat()
                        setColor(color)
                    }
                    layoutParams = FrameLayout.LayoutParams(
                        0,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ).apply {
                        gravity = if (index == 0) Gravity.END else Gravity.START
                    }
                }
                meter.addView(fill)
                meter.doOnLayout {
                    (fill.layoutParams as FrameLayout.LayoutParams).width =
                        (meter.width * (value / 100f)).roundToInt()
                    fill.requestLayout()
                }
            }
            content.addView(meter)
        } else {
            content.addView(ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = percent ?: 0
                progressTintList = ColorStateList.valueOf(color)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(8),
                ).apply { topMargin = dp(2) }
            })
        }
        content.addView(TextView(requireContext()).apply {
            text = buildList {
                outcome.totalPoints?.let { add(numberFormat.format(it) + " points") }
                outcome.totalUsers?.let { add(numberFormat.format(it) + " voters") }
            }.ifEmpty { listOf(getString(R.string.channel_points_prediction_no_votes)) }.joinToString(" · ")
            gravity = Gravity.CENTER
            setTextColor(MaterialColors.getColor(binding.predictionCard, androidx.appcompat.R.attr.colorControlNormal))
            textSize = 12f
        })
        container.addView(content)
    }

    private fun stylePredictionButton(button: MaterialButton, color: Int) {
        button.setTextColor(Color.WHITE)
        button.backgroundTintList = ColorStateList.valueOf(color)
        button.isAllCaps = false
        button.maxLines = 2
        button.ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private fun placePredictionBet(input: EditText, outcomeId: String?) {
        val points = input.text.toString().toIntOrNull()
        val balance = listener.channelPointsFlow().value?.balance
        when {
            outcomeId.isNullOrBlank() -> input.error = getString(R.string.channel_points_prediction_bet_unavailable)
            points == null || points !in MIN_PREDICTION_POINTS..MAX_PREDICTION_POINTS -> input.error = getString(
                R.string.channel_points_prediction_bet_range,
                MIN_PREDICTION_POINTS,
                MAX_PREDICTION_POINTS,
            )
            balance != null && points > balance -> input.error = getString(
                R.string.channel_points_prediction_bet_balance,
                NumberFormat.getInstance().format(balance),
            )
            else -> {
                input.error = null
                listener.betPrediction(outcomeId, points)
            }
        }
    }

    private fun addRow(container: LinearLayout, text: CharSequence) {
        val density = resources.displayMetrics.density
        val padding = (12 * density).roundToInt()
        val bottomMargin = (4 * density).roundToInt()
        container.addView(TextView(requireContext()).apply {
            this.text = text
            setPadding(padding, padding, padding, padding)
            setBackgroundResource(R.drawable.bg_channel_points_row)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                this.bottomMargin = bottomMargin
            }
        })
    }

    override fun onDestroyView() {
        predictionAmountWatcher?.let { binding.predictionBetAmount.removeTextChangedListener(it) }
        predictionAmountWatcher = null
        predictionDraftId = null
        super.onDestroyView()
        _binding = null
    }

    private data class DialogState(
        val channelPoints: ChannelPoints?,
        val watchStreak: WatchStreak?,
        val poll: Poll?,
        val activePoll: Poll?,
        val pollSecondsLeft: Int?,
        val prediction: Prediction?,
        val ongoingPrediction: Prediction?,
        val predictionSecondsLeft: Int?,
        val pollVoteState: PollVoteState,
        val predictionBetState: PredictionBetState,
    )

    private data class BasicDialogState(
        val channelPoints: ChannelPoints?,
        val watchStreak: WatchStreak?,
        val poll: Poll?,
        val prediction: Prediction?,
    )

    private data class ActiveDialogState(
        val activePoll: Poll?,
        val ongoingPrediction: Prediction?,
        val pollSecondsLeft: Int?,
        val predictionSecondsLeft: Int?,
        val pollVoteState: PollVoteState,
        val predictionBetState: PredictionBetState,
    )

    private data class ActiveTimingState(
        val activePoll: Poll?,
        val ongoingPrediction: Prediction?,
        val pollSecondsLeft: Int?,
        val predictionSecondsLeft: Int?,
    )

}
