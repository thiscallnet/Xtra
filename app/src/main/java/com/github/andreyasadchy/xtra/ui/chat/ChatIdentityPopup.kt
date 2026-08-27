package com.github.andreyasadchy.xtra.ui.chat

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.imageLoader
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityBadge
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityCampaign
import com.github.andreyasadchy.xtra.model.chat.ChatIdentityState
import com.github.andreyasadchy.xtra.model.chat.effectiveBadge
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Floating, view-based Chat Identity editor anchored to the live composer. */
class ChatIdentityPopup(
    private val context: Context,
    private val rootView: ViewGroup,
    private val anchor: View,
    private val lifecycleOwner: LifecycleOwner,
    private val viewModel: ChatViewModel,
    private val channelDisplayName: String,
    private val channelId: String,
    private val channelLogin: String,
    private val onDismissed: () -> Unit,
) {
    private var popupWindow: PopupWindow? = null
    private var stateJob: Job? = null
    private val imageRequests = mutableListOf<Disposable>()

    val isShowing: Boolean
        get() = popupWindow?.isShowing == true

    fun show() {
        if (isShowing) return
        val content = LayoutInflater.from(context).inflate(R.layout.popup_chat_identity, null, false)
        val popup = PopupWindow(content, 1, 1, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = context.dp(8).toFloat()
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            setOnDismissListener {
                stateJob?.cancel()
                stateJob = null
                clearImageRequests()
                popupWindow = null
                onDismissed()
            }
        }
        popupWindow = popup
        bindStaticActions(content)
        stateJob = lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.chatIdentityState.collectLatest(::render)
            }
        }
        hideIme()
        viewModel.ensureChatIdentityLoaded(channelId, channelLogin)
        popup.showAtLocation(rootView, Gravity.TOP or Gravity.START, 0, 0)
        content.post { positionPopup(content, popup) }
    }

    fun dismiss() {
        popupWindow?.dismiss()
    }

    private fun bindStaticActions(content: View) {
        content.findViewById<ImageButton>(R.id.chatIdentityClose).setOnClickListener { dismiss() }
        bindCustomBadgeSwitch(content.findViewById(R.id.chatIdentityCustomBadgeSwitch))
        content.findViewById<LinearLayout>(R.id.chatIdentityMoreColors).setOnClickListener {
            val state = viewModel.chatIdentityState.value
            if (state.canUseCustomNameColor && !state.mutationInProgress) {
                showCustomColorDialog(state.nameColor)
            }
        }
    }

    private fun render(state: ChatIdentityState) {
        val popup = popupWindow ?: return
        val content = popup.contentView
        content.findViewById<ProgressBar>(R.id.chatIdentityLoading).isVisible = state.loading
        content.findViewById<TextView>(R.id.chatIdentityPreviewDescription).text =
            context.getString(R.string.chat_identity_preview_description, channelDisplayName)
        val previewName = content.findViewById<TextView>(R.id.chatIdentityPreviewName)
        previewName.text = state.displayName
        previewName.setTextColor(
            state.nameColor?.let { runCatching { Color.parseColor(it) }.getOrNull() }
                ?: MaterialColors.getColor(previewName, com.google.android.material.R.attr.colorOnSurface),
        )

        clearImageRequests()
        val previewBadge = content.findViewById<ImageView>(R.id.chatIdentityPreviewBadge)
        previewBadge.setImageDrawable(null)
        state.effectiveBadge()?.let { badge ->
            previewBadge.isVisible = true
            enqueueImage(badge.imageUrl, previewBadge)
        } ?: run { previewBadge.isVisible = false }

        renderCampaigns(content, state.campaigns)
        renderGlobalBadges(content, state)
        renderChannelBadges(content, state)
        renderColors(content, state)
    }

    private fun renderGlobalBadges(content: View, state: ChatIdentityState) {
        val grid = content.findViewById<GridLayout>(R.id.chatIdentityGlobalBadges)
        grid.removeAllViews()
        addBadgeTile(
            grid = grid,
            badge = null,
            title = context.getString(R.string.chat_identity_none_badge),
            selected = state.badgeSelectionAvailable && state.selectedGlobalBadge == null,
            enabled = state.badgeSelectionAvailable && !state.mutationInProgress,
            onClick = { viewModel.setChatIdentityGlobalBadge(null) },
        )
        state.globalBadges.forEach { badge ->
            addBadgeTile(
                grid = grid,
                badge = badge,
                title = badge.title ?: badge.key.setId,
                selected = badge.key == state.selectedGlobalBadge,
                enabled = state.badgeSelectionAvailable && !state.mutationInProgress,
                onClick = { viewModel.setChatIdentityGlobalBadge(badge) },
            )
        }
        content.findViewById<TextView>(R.id.chatIdentityGlobalUnavailable).isVisible =
            state.globalBadges.isEmpty()
    }

    private fun renderChannelBadges(content: View, state: ChatIdentityState) {
        content.findViewById<TextView>(R.id.chatIdentityChannelDescription).text =
            context.getString(R.string.chat_identity_channel_description, channelDisplayName)
        val subscriberContainer = content.findViewById<LinearLayout>(R.id.chatIdentitySubscriberBadge)
        subscriberContainer.removeAllViews()
        if (state.isSubscribed && state.subscriberBadge != null) {
            val tile = createBadgeTile(
                parent = subscriberContainer,
                badge = state.subscriberBadge,
                title = state.subscriberBadge.title ?: state.subscriberBadge.key.setId,
                selected = true,
                enabled = false,
                onClick = null,
            )
            subscriberContainer.addView(tile, LinearLayout.LayoutParams(context.dp(56), context.dp(56)))
            subscriberContainer.addView(TextView(context).apply {
                text = state.subscriberBadge.title ?: state.subscriberBadge.key.setId
                setPadding(context.dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            subscriberContainer.isVisible = true
            content.findViewById<TextView>(R.id.chatIdentitySubscriptionStatus).text = ""
        } else if (state.isSubscribed) {
            subscriberContainer.isVisible = false
            content.findViewById<TextView>(R.id.chatIdentitySubscriptionStatus)
                .setText(R.string.chat_identity_subscriber_badge_unavailable)
        } else {
            subscriberContainer.isVisible = false
            content.findViewById<TextView>(R.id.chatIdentitySubscriptionStatus)
                .setText(R.string.chat_identity_not_subscribed)
        }

        val customSwitch = content.findViewById<MaterialSwitch>(R.id.chatIdentityCustomBadgeSwitch)
        customSwitch.isVisible = state.channelBadgeOverrideAvailable
        customSwitch.isEnabled = state.channelBadgeOverrideAvailable && !state.mutationInProgress
        customSwitch.setOnCheckedChangeListener(null)
        customSwitch.isChecked = state.useCustomChannelBadge
        bindCustomBadgeSwitch(customSwitch)
        customSwitch.contentDescription = context.getString(R.string.chat_identity_custom_badge_switch)
        val customDescription = content.findViewById<TextView>(R.id.chatIdentityCustomBadgeDescription)
        customDescription.isVisible = state.channelBadgeOverrideAvailable
        customDescription.text = if (state.channelBadgeOverrideAvailable) {
            context.getString(R.string.chat_identity_custom_badge_description, channelDisplayName)
        } else {
            context.getString(R.string.chat_identity_channel_badge_unavailable)
        }
        val channelGrid = content.findViewById<GridLayout>(R.id.chatIdentityChannelBadges)
        channelGrid.removeAllViews()
        channelGrid.isVisible = state.channelBadgeOverrideAvailable &&
            state.useCustomChannelBadge && state.channelBadges.isNotEmpty()
        if (channelGrid.isVisible) {
            state.channelBadges.forEach { badge ->
                addBadgeTile(
                    grid = channelGrid,
                    badge = badge,
                    title = badge.title ?: badge.key.setId,
                    selected = badge.key == state.selectedChannelBadge,
                    enabled = !state.mutationInProgress,
                    onClick = { viewModel.setChatIdentityChannelBadge(badge) },
                )
            }
        }
        content.findViewById<TextView>(R.id.chatIdentityChannelBadgesEmpty).isVisible =
            state.channelBadgeOverrideAvailable && state.useCustomChannelBadge && state.channelBadges.isEmpty()
    }

    private fun bindCustomBadgeSwitch(customSwitch: MaterialSwitch) {
        customSwitch.setOnCheckedChangeListener { _, checked ->
            if (!viewModel.chatIdentityState.value.mutationInProgress) {
                viewModel.setChatIdentityChannelOverride(checked)
            }
        }
    }

    private fun renderColors(content: View, state: ChatIdentityState) {
        val grid = content.findViewById<GridLayout>(R.id.chatIdentityColors)
        grid.removeAllViews()
        state.standardNameColors.forEach { color ->
            val swatch = View(context).apply {
                val selected = color.hex.equals(state.nameColor, ignoreCase = true)
                background = colorSwatch(color.hex, selected)
                contentDescription = context.getString(R.string.chat_identity_badge_title_hex, color.name, color.hex)
                isSelected = selected
                isClickable = !state.mutationInProgress
                isFocusable = true
                alpha = if (state.mutationInProgress) 0.55f else 1f
                setOnClickListener {
                    if (!state.mutationInProgress) viewModel.setChatIdentityNameColor(color.hex)
                }
            }
            grid.addView(swatch, GridLayout.LayoutParams().apply {
                width = context.dp(40)
                height = context.dp(40)
                setMargins(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
            })
        }
        val more = content.findViewById<LinearLayout>(R.id.chatIdentityMoreColors)
        more.isEnabled = state.canUseCustomNameColor && !state.mutationInProgress
        more.alpha = if (more.isEnabled) 1f else 0.55f
        more.contentDescription = context.getString(R.string.chat_identity_more_colors)
        content.findViewById<TextView>(R.id.chatIdentityCustomColorUnavailable).isVisible =
            !state.canUseCustomNameColor
        content.findViewById<View>(R.id.chatIdentityMoreColorsIcon).background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#FF0000"),
                Color.parseColor("#00FF00"),
                Color.parseColor("#0000FF"),
            ),
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(context.dp(2), MaterialColors.getColor(more, com.google.android.material.R.attr.colorOutline))
        }
    }

    private fun renderCampaigns(content: View, campaigns: List<ChatIdentityCampaign>) {
        val section = content.findViewById<LinearLayout>(R.id.chatIdentityCampaignsSection)
        val container = content.findViewById<LinearLayout>(R.id.chatIdentityCampaigns)
        container.removeAllViews()
        section.isVisible = campaigns.isNotEmpty()
        campaigns.forEach { campaign ->
            container.addView(createCampaignCard(campaign))
        }
    }

    private fun createCampaignCard(campaign: ChatIdentityCampaign): View {
        val card = com.google.android.material.card.MaterialCardView(context).apply {
            radius = context.dp(4).toFloat()
            strokeWidth = context.dp(1)
            strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
            setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
        }
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        campaign.imageUrl?.let { imageUrl ->
            val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            header.addView(image, LinearLayout.LayoutParams(context.dp(56), context.dp(56)))
            enqueueImage(imageUrl, image)
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(8), 0, context.dp(4), 0)
        }
        labels.addView(TextView(context).apply {
            text = campaign.title
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        campaign.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
            labels.addView(TextView(context).apply { text = subtitle })
        }
        header.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val chevron = TextView(context).apply {
            text = context.getString(R.string.chat_identity_campaign_chevron_collapsed)
            textSize = 24f
            gravity = Gravity.CENTER
            minWidth = context.dp(48)
            minHeight = context.dp(48)
        }
        header.addView(chevron)
        root.addView(header)
        val progressText = TextView(context).apply {
            text = context.getString(
                R.string.chat_identity_campaign_progress,
                campaign.earnedBadges,
                campaign.totalBadges,
            )
            gravity = Gravity.END
        }
        root.addView(progressText)
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = campaign.totalBadges.coerceAtLeast(1)
            progress = campaign.earnedBadges.coerceIn(0, max)
            progressTintList = android.content.res.ColorStateList.valueOf(
                MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary),
            )
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, context.dp(4)))
        val rewards = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isVisible = false
        }
        campaign.rewards.forEach { reward ->
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            reward.imageUrl?.let { imageUrl ->
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
                row.addView(image, LinearLayout.LayoutParams(context.dp(36), context.dp(36)))
                enqueueImage(imageUrl, image)
            }
            row.addView(TextView(context).apply {
                text = listOfNotNull(reward.title, reward.description).joinToString("\n")
                setPadding(context.dp(8), context.dp(4), 0, context.dp(4))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            rewards.addView(row)
        }
        root.addView(rewards)
        fun setExpanded(expanded: Boolean) {
            rewards.isVisible = expanded
            chevron.text = context.getString(
                if (expanded) R.string.chat_identity_campaign_chevron_expanded
                else R.string.chat_identity_campaign_chevron_collapsed,
            )
            chevron.contentDescription = context.getString(
                if (expanded) R.string.chat_identity_campaign_collapse else R.string.chat_identity_campaign_expand,
            )
        }
        setExpanded(false)
        header.setOnClickListener { setExpanded(!rewards.isVisible) }
        card.addView(root)
        return card
    }

    private fun addBadgeTile(
        grid: GridLayout,
        badge: ChatIdentityBadge?,
        title: String,
        selected: Boolean,
        enabled: Boolean,
        onClick: (() -> Unit)?,
    ) {
        grid.addView(createBadgeTile(grid, badge, title, selected, enabled, onClick), GridLayout.LayoutParams().apply {
            width = context.dp(56)
            height = context.dp(56)
            setMargins(context.dp(2), context.dp(2), context.dp(2), context.dp(2))
        })
    }

    private fun createBadgeTile(
        parent: ViewGroup,
        badge: ChatIdentityBadge?,
        title: String,
        selected: Boolean,
        enabled: Boolean,
        onClick: (() -> Unit)?,
    ): View {
        val tile = LayoutInflater.from(context).inflate(R.layout.item_chat_identity_badge, parent, false)
        val image = tile.findViewById<ImageView>(R.id.chatIdentityBadgeImage)
        if (badge == null) {
            image.setImageResource(R.drawable.ic_no_badge)
            image.imageTintList = null
        } else {
            enqueueImage(badge.imageUrl, image)
            image.imageTintList = null
        }
        val name = badge?.title ?: title
        tile.isSelected = selected
        tile.isEnabled = enabled
        tile.alpha = if (enabled) 1f else 0.55f
        tile.contentDescription = if (selected) {
            context.getString(R.string.chat_identity_badge_selected, name)
        } else {
            name
        }
        tile.setOnClickListener {
            onClick?.invoke()
        }
        tile.setOnLongClickListener {
            if (badge?.description?.isNotBlank() == true || badge?.title?.isNotBlank() == true) {
                context.getAlertDialogBuilder()
                    .setTitle(badge.title ?: title)
                    .setMessage(badge.description)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            } else {
                false
            }
        }
        tile.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dp(4).toFloat()
            setColor(MaterialColors.getColor(tile, com.google.android.material.R.attr.colorSurfaceContainerHighest))
            setStroke(
                context.dp(if (selected) 2 else 1),
                MaterialColors.getColor(
                    tile,
                    if (selected) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOutlineVariant,
                ),
            )
        }
        return tile
    }

    private fun colorSwatch(hex: String, selected: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.parseColor(hex))
        setStroke(
            context.dp(if (selected) 3 else 1),
            MaterialColors.getColor(
                popupWindow?.contentView ?: rootView,
                if (selected) com.google.android.material.R.attr.colorOnSurface else com.google.android.material.R.attr.colorOutline,
            ),
        )
    }

    private fun enqueueImage(url: String, target: ImageView) {
        if (url.isBlank()) return
        imageRequests += context.imageLoader.enqueue(
            ImageRequest.Builder(context)
                .data(url)
                .diskCachePolicy(CachePolicy.ENABLED)
                .crossfade(false)
                .target(target)
                .build(),
        )
    }

    private fun clearImageRequests() {
        imageRequests.forEach(Disposable::dispose)
        imageRequests.clear()
    }

    private fun showCustomColorDialog(currentColor: String?) {
        val inputLayout = TextInputLayout(context).apply {
            hint = context.getString(R.string.chat_identity_custom_color_hint)
        }
        val input = TextInputEditText(context).apply {
            setSingleLine(true)
            setText(currentColor ?: "#9146FF")
            selectAll()
        }
        inputLayout.addView(input)
        val preview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(context.dp(40), context.dp(40)).apply {
                topMargin = context.dp(12)
            }
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), 0, context.dp(24), 0)
            addView(inputLayout)
            addView(preview)
        }
        fun updatePreview() {
            val value = input.text?.toString()?.trim()?.uppercase() ?: ""
            preview.background = if (Regex("^#[0-9A-F]{6}$").matches(value)) {
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(value)) }
            } else {
                ColorDrawable(MaterialColors.getColor(preview, com.google.android.material.R.attr.colorSurfaceVariant))
            }
        }
        input.addTextChangedListener { updatePreview() }
        updatePreview()
        val dialog = context.getAlertDialogBuilder()
            .setTitle(R.string.chat_identity_custom_color_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            val button = dialog.getButton(Dialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val value = input.text?.toString()?.trim()?.uppercase() ?: ""
                if (!Regex("^#[0-9A-F]{6}$").matches(value)) {
                    inputLayout.error = context.getString(R.string.chat_identity_invalid_color)
                } else {
                    inputLayout.error = null
                    viewModel.setChatIdentityNameColor(value)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun hideIme() {
        val window = (anchor.context as? android.app.Activity)?.window ?: return
        WindowCompat.getInsetsController(window, anchor).hide(WindowInsetsCompat.Type.ime())
        anchor.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(anchor.windowToken, 0)
    }

    private fun positionPopup(content: View, popup: PopupWindow) {
        val screenWidth = rootView.rootView.width.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        val desiredWidth = min(context.dp(344), screenWidth - context.dp(16)).coerceAtLeast(context.dp(240))
        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        val rootInsets = ViewCompat.getRootWindowInsets(rootView)
        val insetTop = rootInsets?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: 0
        val maxHeight = (anchorLocation[1] - insetTop - context.dp(8)).coerceAtLeast(context.dp(220))
        content.measure(
            View.MeasureSpec.makeMeasureSpec(desiredWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
        )
        val height = min(content.measuredHeight, maxHeight)
        val left = anchorLocation[0].coerceIn(context.dp(8), screenWidth - desiredWidth - context.dp(8))
        val top = max(insetTop + context.dp(8), anchorLocation[1] - height - context.dp(8))
        popup.update(left, top, desiredWidth, height)
    }

    private fun Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
