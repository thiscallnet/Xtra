package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentPlayerBinding
import com.github.andreyasadchy.xtra.databinding.PlayerLayoutBinding
import com.github.andreyasadchy.xtra.util.PlayerControlLayout
import com.github.andreyasadchy.xtra.util.PortraitPlayerControls
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Hosts the same player control hierarchy used during playback.
 *
 * The player composition is measured at the current device's runtime width and then uniformly
 * scaled into the Settings card. It owns an isolated local Media3 player so the preview is a real
 * decoder-backed player without touching the app's active stream player.
 */
class PlayerControlPreviewView @JvmOverloads constructor(
    context: Context,
    private val dragEnabled: Boolean = false,
    private val labelFor: (String) -> String = { it },
) : FrameLayout(context) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val portraitReferenceWidth = min(
        resources.displayMetrics.widthPixels,
        resources.displayMetrics.heightPixels,
    ).coerceAtLeast(dp(360))
    private val landscapeReferenceWidth = maxOf(
        resources.displayMetrics.widthPixels,
        resources.displayMetrics.heightPixels,
    ).coerceAtLeast(dp(360))
    private var referenceWidth = portraitReferenceWidth
    private var referenceHeight = (referenceWidth * 9f / 16f).roundToInt()
    private val scene = ScaledScene(context)
    private val runtimeBinding = FragmentPlayerBinding.inflate(LayoutInflater.from(context))
    private val playerLayout = runtimeBinding.playerLayout
    private val controls: PlayerLayoutBinding = runtimeBinding.playerControls
    private val dragOverlay = PreviewDragOverlay(context)
    private var previewPlayer: ExoPlayer? = null
    private var previewIsPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    private var controlScaleOverride: Float? = null
    private var metadataScaleOverride: Float? = null
    private var geometryPosted = false
    private val progressUpdater = object : Runnable {
        override fun run() {
            if (previewPlayer == null) return
            renderPlaybackChrome()
            controls.root.postDelayed(this, 250L)
        }
    }
    private val actionViews: Map<String, View> = mapOf(
        "metadata" to controls.topLeftLayout,
        "play_pause" to controls.playPause,
        "rewind" to controls.rewind,
        "fast_forward" to controls.fastForward,
        "timeline" to controls.bottomLayout,
        "interaction_lock" to controls.interactionLock,
        "menu" to controls.menu,
        "minimize" to controls.minimize,
        "download" to controls.download,
        "follow" to controls.follow,
        "sleep" to controls.sleepTimer,
        "aspect" to controls.aspectRatio,
        "quality" to controls.quality,
        "speed" to controls.speed,
        "chapters" to controls.vodGames,
        "restart" to controls.restart,
        "live" to controls.seekLive,
        "live_captions" to controls.liveCaptions,
        "clip" to controls.clip,
        "volume" to controls.volume,
        "compressor" to controls.audioCompressor,
        "mode" to controls.audioOnly,
        "subtitles" to controls.subtitles,
        "chat_input" to controls.toggleChatInput,
        "chat" to controls.toggleChat,
        "fullscreen" to controls.fullscreen,
    )
    private val rowByAnchor: Map<String, ViewGroup> = mapOf(
        PlayerControlLayout.ANCHOR_TOP_START to controls.topStartLayout,
        PlayerControlLayout.ANCHOR_TOP_CENTER to controls.topCenterLayout,
        PlayerControlLayout.ANCHOR_TOP_END to controls.topRightLayout,
        PlayerControlLayout.ANCHOR_MIDDLE_START to controls.middleLeftLayout,
        PlayerControlLayout.ANCHOR_MIDDLE_CENTER to controls.middleCenterLayout,
        PlayerControlLayout.ANCHOR_MIDDLE_END to controls.middleRightLayout,
        PlayerControlLayout.ANCHOR_BOTTOM_START to controls.bottomLeftLayout,
        PlayerControlLayout.ANCHOR_BOTTOM_CENTER to controls.bottomCenterLayout,
        PlayerControlLayout.ANCHOR_BOTTOM_END to controls.bottomRightLayout,
    )
    private var items: List<PlayerControlLayout.ControlPlacement> = emptyList()
    private var selectedAction: String? = null
    private var onLayoutChanged: ((List<PlayerControlLayout.ControlPlacement>) -> Unit)? = null
    private var onSelectionChanged: ((String?) -> Unit)? = null
    private var draggingAction: String? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var isDragging = false

    init {
        setWillNotDraw(false)
        clipChildren = true
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.rgb(14, 17, 23))
        }
        clipToOutline = true

        (playerLayout.parent as? ViewGroup)?.removeView(playerLayout)
        playerLayout.visibility = View.VISIBLE
        playerLayout.alpha = 1f
        runtimeBinding.playerTextureView.visibility = View.VISIBLE
        runtimeBinding.playerSurface.visibility = View.GONE
        runtimeBinding.aspectRatioFrameLayout.setAspectRatio(16f / 9f)
        controls.root.visibility = View.VISIBLE
        controls.root.alpha = 1f
        scene.addView(playerLayout, FrameLayout.LayoutParams(referenceWidth, referenceHeight))
        addView(scene)
        addView(dragOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dragOverlay.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        dragOverlay.visibility = View.VISIBLE
        dragOverlay.elevation = dp(1).toFloat()

        configurePreviewState()
        if (dragEnabled) {
            dragOverlay.isClickable = true
            dragOverlay.isFocusable = false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startPreviewPlayer()
    }

    override fun onDetachedFromWindow() {
        releasePreviewPlayer()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) {
            startPreviewPlayer()
        } else {
            // A Customize Controls dialog can sit above the settings activity. Release this
            // decoder while the window is covered so several previews never compete for codecs.
            releasePreviewPlayer()
        }
    }

    fun setItems(
        placements: List<PlayerControlLayout.ControlPlacement>,
        selected: String? = selectedAction,
        onChanged: ((List<PlayerControlLayout.ControlPlacement>) -> Unit)? = onLayoutChanged,
    ) {
        items = placements.map { it.copy() }
        selectedAction = selected
        onLayoutChanged = onChanged
        applyPlacementModel()
    }

    fun setVisualScale(
        control: Float = 1f,
        metadata: Float = 1f,
        isPortrait: Boolean = previewIsPortrait,
    ) {
        previewIsPortrait = isPortrait
        controlScaleOverride = control
        metadataScaleOverride = metadata
        updateReferenceDimensions(isPortrait)
        scheduleGeometryPass()
    }

    fun setSelectedAction(action: String?) {
        selectedAction = action
        onSelectionChanged?.invoke(action)
        dragOverlay.invalidate()
    }

    fun setSelectionListener(listener: (String?) -> Unit) {
        onSelectionChanged = listener
    }

    fun selectedAction(): String? = selectedAction

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = resolveSize(MeasureSpec.getSize(widthMeasureSpec), widthMeasureSpec)
        val naturalHeight = (width * 9f / 16f).roundToInt()
        val height = resolveSize(naturalHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
        scene.measure(
            MeasureSpec.makeMeasureSpec(referenceWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(referenceHeight, MeasureSpec.EXACTLY),
        )
        dragOverlay.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val scale = sceneScale()
        val sceneWidth = (referenceWidth * scale).roundToInt()
        val sceneHeight = (referenceHeight * scale).roundToInt()
        scene.renderScale = scale
        scene.translationX = (width - sceneWidth) / 2f
        scene.translationY = (height - sceneHeight) / 2f
        scene.layout(0, 0, referenceWidth, referenceHeight)
        dragOverlay.layout(0, 0, width, height)
        dragOverlay.bringToFront()
        scheduleGeometryPass()
    }

    private fun configurePreviewState() {
        // The controls and the media composition are the same views used by the runtime player.
        // Use a realistic channel label that fits the same single-line slot as a live stream.
        controls.channel.text = "ironmouse"
        controls.channel.visibility = View.VISIBLE
        controls.title.text = "A stream title that stays visible"
        controls.title.visibility = View.VISIBLE
        controls.titleAndViewersLayout.visibility = View.VISIBLE
        controls.streamDetailsLayout.visibility = View.VISIBLE
        controls.playingLabel.visibility = View.GONE
        controls.category.text = "Just Chatting"
        controls.category.visibility = View.VISIBLE
        controls.viewersText.text = "35 viewers"
        controls.viewersLayout.visibility = View.VISIBLE
        controls.channelAvatar.apply {
            visibility = View.VISIBLE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(206, 216, 232))
            }
            setImageResource(R.drawable.baseline_person_black_36)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.rgb(92, 106, 128))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            clipToOutline = true
        }
        controls.quality.text = "1080p60"
        controls.position.text = "0:00"
        controls.duration.text = "0:08"
        controls.position.visibility = View.VISIBLE
        controls.duration.visibility = View.VISIBLE
        controls.bottomLayout.visibility = View.VISIBLE
        controls.liveButton.visibility = View.GONE
        controls.progressBar.apply {
            visibility = View.VISIBLE
            setDuration(8_000L)
            setPosition(0L)
            setBufferedPosition(0L)
        }
        controls.menu.visibility = View.VISIBLE
        controls.interactionLock.visibility = View.VISIBLE
        controls.playPause.setOnClickListener {
            previewPlayer?.let { player -> player.playWhenReady = !player.isPlaying }
        }
        controls.rewind.setOnClickListener { previewPlayer?.seekBack() }
        controls.fastForward.setOnClickListener { previewPlayer?.seekForward() }
        controls.menu.setOnClickListener { }
        actionViews
            .filterKeys { it !in setOf("metadata", "timeline", "play_pause", "rewind", "fast_forward") }
            .values
            .forEach { view ->
            // Actions stay local to the preview. The editor intercepts their touch events and
            // persists only semantic placement changes, never runtime actions or network work.
            view.setOnClickListener { }
            view.isClickable = !dragEnabled
            view.isFocusable = false
            if (dragEnabled) view.setOnTouchListener { _, event -> handleDrag(event) }
        }
        controls.progressBar.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) = Unit
            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                previewPlayer?.seekTo(position)
            }
            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                if (!canceled) previewPlayer?.seekTo(position)
            }
        })
    }

    private fun startPreviewPlayer() {
        if (previewPlayer != null || !isAttachedToWindow) return
        controls.root.removeCallbacks(progressUpdater)
        val player = ExoPlayer.Builder(context.applicationContext).build()
        previewPlayer = player
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = renderPlaybackChrome()
            override fun onPlaybackStateChanged(playbackState: Int) = renderPlaybackChrome()
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = renderPlaybackChrome()
            override fun onPositionDiscontinuity(reason: Int) = renderPlaybackChrome()
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    runtimeBinding.aspectRatioFrameLayout.setAspectRatio(
                        videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height,
                    )
                }
            }
        })
        runtimeBinding.playerTextureView.visibility = View.VISIBLE
        runtimeBinding.playerSurface.visibility = View.GONE
        player.setVideoTextureView(runtimeBinding.playerTextureView)
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.volume = 0f
        player.setMediaItem(
            MediaItem.fromUri(
                Uri.parse("android.resource://${context.packageName}/${R.raw.player_preview}"),
            ),
        )
        player.prepare()
        player.playWhenReady = true
        renderPlaybackChrome()
        controls.root.post(progressUpdater)
    }

    private fun releasePreviewPlayer() {
        val player = previewPlayer ?: return
        controls.root.removeCallbacks(progressUpdater)
        previewPlayer = null
        runCatching { player.clearVideoTextureView(runtimeBinding.playerTextureView) }
        runCatching { player.release() }
        renderPlaybackChrome()
    }

    private fun renderPlaybackChrome() {
        val player = previewPlayer
        val duration = player?.duration?.takeIf { it > 0L && it != androidx.media3.common.C.TIME_UNSET } ?: 8_000L
        val position = player?.currentPosition?.coerceIn(0L, duration) ?: 0L
        val buffered = player?.bufferedPosition?.coerceIn(position, duration) ?: position
        controls.position.text = DateUtils.formatElapsedTime(position / 1_000L)
        controls.duration.text = DateUtils.formatElapsedTime(duration / 1_000L)
        controls.progressBar.setDuration(duration)
        controls.progressBar.setPosition(position)
        controls.progressBar.setBufferedPosition(buffered)
        controls.playPause.setImageResource(
            if (player?.isPlaying == true) R.drawable.baseline_pause_black_48 else R.drawable.baseline_play_arrow_black_48,
        )
    }

    private fun applyPlacementModel() {
        PlayerControlLayout.applyToPlayer(context, runtimeBinding, items)
        controls.root.requestLayout()
        dragOverlay.invalidate()
        requestLayout()
        scheduleGeometryPass()
    }

    private fun updateReferenceDimensions(isPortrait: Boolean) {
        val targetWidth = if (isPortrait) portraitReferenceWidth else landscapeReferenceWidth
        if (targetWidth == referenceWidth) return
        referenceWidth = targetWidth
        referenceHeight = (referenceWidth * 9f / 16f).roundToInt()
        (playerLayout.layoutParams as? FrameLayout.LayoutParams)?.let { params ->
            params.width = referenceWidth
            params.height = referenceHeight
            playerLayout.layoutParams = params
        }
        scene.requestLayout()
        requestLayout()
    }

    private fun scheduleGeometryPass() {
        if (geometryPosted) return
        geometryPosted = true
        post {
            geometryPosted = false
            PortraitPlayerControls.applyForPreview(
                binding = runtimeBinding,
                isPortrait = previewIsPortrait,
                controlScaleOverride = controlScaleOverride,
                metadataScaleOverride = metadataScaleOverride,
            )
            dragOverlay.invalidate()
        }
    }

    private fun handleDrag(event: MotionEvent): Boolean {
        if (!dragEnabled) return false
        val eventX = event.rawX - screenLocation()[0]
        val eventY = event.rawY - screenLocation()[1]
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = actionViews.entries
                    .filter { (action, view) -> items.any { it.action == action && it.group == PlayerControlLayout.GROUP_QUICK } && view.isShown }
                    .mapNotNull { (action, view) -> action to visualBounds(action, view) }
                    .firstOrNull { (_, bounds) -> bounds.inset(-dp(12).toFloat(), -dp(12).toFloat()); bounds.contains(eventX, eventY) }
                    ?.first
                    ?: return false
                val view = actionViews.getValue(hit)
                selectedAction = hit
                onSelectionChanged?.invoke(hit)
                draggingAction = hit
                val bounds = visualBounds(hit, view)
                dragStartX = eventX
                dragStartY = eventY
                dragOffsetX = eventX - bounds.centerX()
                dragOffsetY = eventY - bounds.centerY()
                isDragging = false
                view.bringToFront()
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val action = draggingAction ?: return false
                val dx = eventX - dragStartX
                val dy = eventY - dragStartY
                if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) isDragging = true
                if (isDragging) {
                    val view = actionViews.getValue(action)
                    val sceneScale = sceneScale().coerceAtLeast(0.001f)
                    val targetX = (eventX - dragOffsetX - scene.translationX) / sceneScale
                    val targetY = (eventY - dragOffsetY - scene.translationY) / sceneScale
                    val parentOffset = layoutOffsetInScene(view)
                    val baseX = parentOffset.first - view.translationX
                    val baseY = parentOffset.second - view.translationY
                    view.translationX = targetX - baseX - view.width / 2f
                    view.translationY = targetY - baseY - view.height / 2f
                    dragOverlay.invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val action = draggingAction ?: return false
                if (isDragging) {
                    val view = actionViews.getValue(action)
                    val bounds = visualBounds(action, view)
                    snapAction(action, bounds.centerX(), bounds.centerY())
                }
                actionViews.getValue(action).translationX = 0f
                actionViews.getValue(action).translationY = 0f
                draggingAction = null
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                applyPlacementModel()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                draggingAction?.let { action ->
                    actionViews.getValue(action).translationX = 0f
                    actionViews.getValue(action).translationY = 0f
                }
                draggingAction = null
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                applyPlacementModel()
                return true
            }
        }
        return false
    }

    private fun snapAction(action: String, centerX: Float, centerY: Float) {
        val itemIndex = items.indexOfFirst { it.action == action }
        if (itemIndex < 0) return
        val anchor = rowByAnchor.entries
            .filter { (candidate, _) -> candidate in PlayerControlLayout.validAnchors(action) }
            .minByOrNull { (candidate, _) ->
                val bounds = anchorBounds(candidate)
                hypot((bounds.centerX() - centerX).toDouble(), (bounds.centerY() - centerY).toDouble())
            }
            ?.key
            ?: PlayerControlLayout.defaultAnchor(action)
        val remaining = items.filterIndexed { index, _ -> index != itemIndex }.toMutableList()
        val anchorItems = remaining.filter { it.group == PlayerControlLayout.GROUP_QUICK && it.anchor == anchor }
        val dropCoordinate = if (anchor.startsWith("middle")) centerY else centerX
        val insertBefore = anchorItems.firstOrNull { other ->
            val otherView = actionViews[other.action] ?: return@firstOrNull false
            val otherBounds = visualBounds(other.action, otherView)
            val otherCoordinate = if (anchor.startsWith("middle")) otherBounds.centerY() else otherBounds.centerX()
            otherCoordinate > dropCoordinate
        }
        val targetIndex = insertBefore?.let(remaining::indexOf)?.takeIf { it >= 0 } ?: remaining.size
        remaining.add(targetIndex, items[itemIndex].copy(anchor = anchor))
        items = remaining
        onLayoutChanged?.invoke(items.map { it.copy() })
    }

    private fun visualBounds(view: View): RectF = visualBounds(null, view)

    private fun visualBounds(action: String?, view: View): RectF {
        if (action == "timeline") {
            return listOf(controls.position, controls.bottomLayout, controls.duration)
                .filter { it.isShown && it.width > 0 && it.height > 0 }
                .map(::visualBounds)
                .reduceOrNull { result, next -> result.apply { union(next) } }
                ?: visualBounds(view)
        }
        val logical = layoutOffsetInScene(view)
        val scale = sceneScale()
        return RectF(
            scene.translationX + logical.first * scale,
            scene.translationY + logical.second * scale,
            scene.translationX + (logical.first + view.width) * scale,
            scene.translationY + (logical.second + view.height) * scale,
        )
    }

    private fun anchorBounds(anchor: String): RectF {
        val row = rowByAnchor.getValue(anchor)
        val visibleChild = (0 until row.childCount)
            .map(row::getChildAt)
            .firstOrNull { it.isShown && it.width > 0 && it.height > 0 }
        if (visibleChild != null && row.width > 0 && row.height > 0) return visualBounds(row)

        val logicalX = when {
            anchor.endsWith("start") -> referenceWidth * 0.08f
            anchor.endsWith("end") -> referenceWidth * 0.92f
            else -> referenceWidth / 2f
        }
        val logicalY = when {
            anchor.startsWith("top") -> referenceHeight * 0.12f
            anchor.startsWith("bottom") -> referenceHeight * 0.84f
            else -> referenceHeight / 2f
        }
        val size = (48f * sceneScale()).coerceAtLeast(1f)
        val x = scene.translationX + logicalX * sceneScale()
        val y = scene.translationY + logicalY * sceneScale()
        return RectF(x - size / 2f, y - size / 2f, x + size / 2f, y + size / 2f)
    }

    private fun layoutOffsetInScene(view: View): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var current: View = view
        while (current !== controls.root) {
            x += current.left.toFloat() + current.translationX - current.scrollX
            y += current.top.toFloat() + current.translationY - current.scrollY
            current = current.parent as? View ?: break
        }
        return x to y
    }

    private fun sceneScale(): Float = min(
        width / referenceWidth.toFloat().coerceAtLeast(1f),
        height / referenceHeight.toFloat().coerceAtLeast(1f),
    ).coerceAtLeast(0f)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun screenLocation(): IntArray {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return location
    }

    private inner class PreviewDragOverlay(context: Context) : View(context) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val action = selectedAction ?: return
            val view = actionViews[action] ?: return
            val bounds = visualBounds(action, view)
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = accentColor()
            paint.alpha = 225
            canvas.drawRoundRect(bounds, dp(3).toFloat(), dp(3).toFloat(), paint)
            if (draggingAction != null) {
                PlayerControlLayout.validAnchors(action).forEach { anchor ->
                    rowByAnchor[anchor]?.let { row ->
                        val target = anchorBounds(anchor)
                        if (target.width() > 0f && target.height() > 0f) {
                            canvas.drawRoundRect(target, dp(4).toFloat(), dp(4).toFloat(), paint)
                        }
                    }
                }
            }
            paint.style = android.graphics.Paint.Style.FILL
            paint.alpha = 255
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = handleDrag(event)
    }

    private class ScaledScene(context: Context) : FrameLayout(context) {
        var renderScale = 1f

        override fun dispatchDraw(canvas: Canvas) {
            canvas.save()
            canvas.scale(renderScale, renderScale)
            super.dispatchDraw(canvas)
            canvas.restore()
        }
    }

    private fun accentColor(): Int {
        val value = android.util.TypedValue()
        if (!context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, value, true)) {
            return Color.rgb(190, 90, 255)
        }
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }
}
