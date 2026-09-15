package com.github.andreyasadchy.xtra.ui.settings.hud

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.PlayerLayoutBinding
import com.github.andreyasadchy.xtra.ui.player.hud.HudDefaultLayout
import com.github.andreyasadchy.xtra.ui.player.hud.HudElementId
import com.github.andreyasadchy.xtra.ui.player.hud.HudElementRegistry
import com.github.andreyasadchy.xtra.ui.player.hud.HudOrientation
import com.github.andreyasadchy.xtra.ui.player.hud.HudPlacement
import com.github.andreyasadchy.xtra.ui.player.hud.HudPivot
import com.github.andreyasadchy.xtra.ui.player.hud.HudProfile
import com.github.andreyasadchy.xtra.ui.player.hud.HudProfileMode
import com.github.andreyasadchy.xtra.ui.player.hud.PlayerHudConfig
import com.github.andreyasadchy.xtra.ui.player.hud.PlayerHudDefaults
import com.github.andreyasadchy.xtra.ui.player.hud.PlayerHudLayout
import com.github.andreyasadchy.xtra.ui.player.hud.profile
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * Full-screen HUD editor. The preview is an actual Media3 player with the
 * production HUD hierarchy overlaid on it; only its media source is local and
 * deterministic so opening settings never depends on login or network state.
 */
class PlayerHudEditorFragment : Fragment() {
    private val density get() = resources.displayMetrics.density
    private lateinit var store: com.github.andreyasadchy.xtra.ui.player.hud.HudConfigStore

    private lateinit var preview: PlayerHudLayout
    private lateinit var previewBinding: PlayerLayoutBinding
    private lateinit var previewPlayerView: PlayerView
    private lateinit var previewContainer: PreviewAspectFrameLayout
    private lateinit var guideOverlay: HudGuideOverlayView
    private lateinit var selectedText: TextView
    private lateinit var scaleValueText: TextView
    private lateinit var globalScaleValueText: TextView
    private lateinit var enabledSwitch: MaterialSwitch
    private lateinit var scaleSlider: Slider
    private lateinit var globalScaleSlider: Slider
    private lateinit var swapButton: MaterialButton
    private lateinit var portraitButton: MaterialButton
    private lateinit var landscapeButton: MaterialButton
    private lateinit var elementList: LinearLayout
    private lateinit var editorScroll: ScrollView

    private var previewPlayer: ExoPlayer? = null
    private var workingConfig: PlayerHudConfig = PlayerHudDefaults.config()
    private var orientation = HudOrientation.PORTRAIT
    private var selected: HudElementId? = null
    private var previousProfile: HudProfile? = null
    private var suppressPanelCallbacks = false
    private val elementSwitches = linkedMapOf<HudElementId, MaterialSwitch>()
    private val elementRows = linkedMapOf<HudElementId, View>()

    private val previewProgressUpdate = object : Runnable {
        override fun run() {
            if (!isAdded || !::preview.isInitialized) return
            syncPreviewProgress()
            preview.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = com.github.andreyasadchy.xtra.ui.player.hud.HudConfigStore(requireContext())
        workingConfig = store.load()
        orientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            HudOrientation.LANDSCAPE
        } else {
            HudOrientation.PORTRAIT
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = FrameLayout(requireContext()).apply {
            setBackgroundColor(Color.rgb(20, 18, 24))
            clipChildren = false
        }

        var systemInsets = Rect()
        fun applySystemInsets() {
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            val screenHeight = resources.displayMetrics.heightPixels
            val rootBottom = location[1] + root.height
            root.updatePadding(
                top = (systemInsets.top - location[1]).coerceAtLeast(0),
                bottom = (systemInsets.bottom - (screenHeight - rootBottom)).coerceAtLeast(0),
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val values = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            systemInsets = Rect(values.left, values.top, values.right, values.bottom)
            applySystemInsets()
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applySystemInsets() }

        editorScroll = ScrollView(requireContext()).apply {
            clipToPadding = false
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(32))
        }
        editorScroll.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(
            editorScroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(64)
            },
        )
        root.addView(buildAppBar(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))

        content.addView(sectionLabel("Preview"))
        val orientationSelector = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        portraitButton = orientationButton("Portrait") { selectOrientation(HudOrientation.PORTRAIT) }
        landscapeButton = orientationButton("Landscape") { selectOrientation(HudOrientation.LANDSCAPE) }
        orientationSelector.addView(portraitButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        orientationSelector.addView(landscapeButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        content.addView(orientationSelector, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        previewContainer = PreviewAspectFrameLayout(requireContext()).apply {
            setBackgroundColor(Color.BLACK)
            elevation = dp(2).toFloat()
        }
        previewBinding = PlayerLayoutBinding.inflate(inflater, previewContainer, false)
        preview = previewBinding.root
        preview.visibility = View.VISIBLE
        preview.alpha = 1f
        preview.setSafeInsetsOverride(Rect())
        preview.setHudOrientation(orientation)
        preview.setPreviewMode(true)

        previewPlayerView = PlayerView(requireContext()).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            isClickable = false
            isFocusable = false
        }
        previewContainer.addView(
            previewPlayerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        previewContainer.addView(
            preview,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        guideOverlay = HudGuideOverlayView(requireContext(), preview).apply {
            isClickable = false
            isFocusable = false
        }
        previewContainer.addView(
            guideOverlay,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        content.addView(
            previewContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        content.addView(instructionLabel())

        val selectedPanel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(0xFF29242F.toInt())
        }
        selectedText = TextView(requireContext()).apply {
            text = "Tap a control in the preview or choose one below"
            textSize = 16f
            setTextColor(Color.WHITE)
        }
        selectedPanel.addView(selectedText)

        enabledSwitch = MaterialSwitch(requireContext()).apply {
            text = "Show this control"
            setOnCheckedChangeListener { _, value ->
                if (!suppressPanelCallbacks) selected?.let { updateSelected(it) { placement -> placement.copy(enabled = value) } }
            }
        }
        selectedPanel.addView(enabledSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        val scaleHeader = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
        scaleHeader.addView(TextView(requireContext()).apply {
            text = "Selected control size"
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, dp(32), 1f))
        scaleValueText = TextView(requireContext()).apply {
            setTextColor(0xFFD0C2FF.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        scaleHeader.addView(scaleValueText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
        selectedPanel.addView(scaleHeader)
        scaleSlider = Slider(requireContext()).apply {
            valueFrom = 75f
            valueTo = 175f
            stepSize = 1f
            addOnChangeListener { _, value, fromUser ->
                if (fromUser && !suppressPanelCallbacks) {
                    selected?.let { updateSelected(it) { placement -> placement.copy(scale = value / 100f) } }
                }
            }
        }
        selectedPanel.addView(scaleSlider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        val globalHeader = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
        globalHeader.addView(TextView(requireContext()).apply {
            text = "Overall HUD size"
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, dp(32), 1f))
        globalScaleValueText = TextView(requireContext()).apply {
            setTextColor(0xFFD0C2FF.toInt())
            gravity = Gravity.CENTER_VERTICAL
        }
        globalHeader.addView(globalScaleValueText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
        selectedPanel.addView(globalHeader)
        globalScaleSlider = Slider(requireContext()).apply {
            valueFrom = 85f
            valueTo = 130f
            stepSize = 1f
            addOnChangeListener { _, value, fromUser ->
                if (fromUser && !suppressPanelCallbacks) {
                    if (currentProfile().mode == HudProfileMode.DEFAULT) materializeDefault()
                    setProfile(currentProfile().copy(globalScale = value / 100f))
                }
            }
        }
        selectedPanel.addView(globalScaleSlider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        selectedPanel.addView(TextView(requireContext()).apply {
            text = "Align selected control"
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        val alignment = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        alignment.addView(alignmentButton("Center") { alignSelected(.5f, .5f) }, weightedButtonParams())
        alignment.addView(alignmentButton("Top") { alignSelected(null, 0f) }, weightedButtonParams())
        alignment.addView(alignmentButton("Bottom") { alignSelected(null, 1f) }, weightedButtonParams())
        alignment.addView(alignmentButton("Reset") { selected?.let(::resetElement) }, weightedButtonParams())
        selectedPanel.addView(alignment, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        swapButton = MaterialButton(requireContext()).apply {
            text = "Swap with…"
            isAllCaps = false
            setOnClickListener { showSwapDialog() }
        }
        selectedPanel.addView(swapButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(8)
        })
        content.addView(selectedPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })

        content.addView(sectionLabel("HUD controls"), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(10)
        })
        content.addView(TextView(requireContext()).apply {
            text = "Select a row, then drag the highlighted control in the player preview."
            textSize = 13f
            setTextColor(0xFFB8B0C2.toInt())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        elementList = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBackground(0xFF29242F.toInt())
        }
        HudElementId.entries.forEach { id -> addElementRow(id) }
        content.addView(elementList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val resetSection = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        resetSection.addView(sectionLabel("Reset"))
        resetSection.addView(MaterialButton(requireContext()).apply {
            text = "Reset this orientation"
            isAllCaps = false
            setOnClickListener {
                setProfile(PlayerHudDefaults.config().profile(orientation))
                selectElement(null)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        resetSection.addView(MaterialButton(requireContext()).apply {
            text = "Reset both orientations"
            isAllCaps = false
            setOnClickListener {
                workingConfig = PlayerHudDefaults.config()
                setProfile(currentProfile())
                selectElement(null)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
        content.addView(resetSection)

        preview.setEditing(
            true,
            onSelected = { id ->
                previousProfile = preview.hudProfile()
                selectElement(id)
            },
            onMoved = { id, value ->
                selected = id
                updateWorkingPlacement(id, value)
                guideOverlay.selected = id
                guideOverlay.invalidate()
            },
            onDropped = { id, canceled ->
                val previous = previousProfile
                if (canceled) {
                    previous?.let(::setProfile)
                    guideOverlay.collision = null
                } else {
                    val snapped = preview.snapEditorPlacement(id)
                    if (preview.editorDropHasCollision(id, snapped)) {
                        previous?.let(::setProfile)
                        guideOverlay.collision = id
                    } else {
                        preview.updateEditorPlacement(id, snapped)
                        updateWorkingPlacement(id, snapped)
                        guideOverlay.collision = null
                    }
                }
                previousProfile = null
                guideOverlay.invalidate()
                updateSelectedPanel()
            },
        )

        selectOrientation(orientation)
        preview.doOnLayout {
            selectElement(HudElementId.PLAY_PAUSE)
            updateElementList()
        }
        startPreviewPlayer()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (requireActivity() as? SettingsActivity)?.setSettingsChromeVisible(false)
    }

    override fun onDestroyView() {
        if (::preview.isInitialized) preview.removeCallbacks(previewProgressUpdate)
        previewPlayerView.player = null
        previewPlayer?.release()
        previewPlayer = null
        (requireActivity() as? SettingsActivity)?.setSettingsChromeVisible(true)
        super.onDestroyView()
    }

    private fun startPreviewPlayer() {
        previewPlayer = ExoPlayer.Builder(requireContext()).build().also { player ->
            player.repeatMode = Player.REPEAT_MODE_ALL
            player.volume = 0f
            player.setMediaItem(
                MediaItem.fromUri(Uri.parse("android.resource://${requireContext().packageName}/${R.raw.player_preview}")),
            )
            previewPlayerView.player = player
            player.prepare()
            player.playWhenReady = true
        }
        preview.post(previewProgressUpdate)
    }

    private fun syncPreviewProgress() {
        val player = previewPlayer ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        previewBinding.progressBar.setDuration(duration)
        previewBinding.progressBar.setPosition(player.currentPosition.coerceIn(0L, duration))
        previewBinding.progressBar.setBufferedPosition(player.bufferedPosition.coerceIn(0L, duration))
        previewBinding.position.text = android.text.format.DateUtils.formatElapsedTime(player.currentPosition / 1000L)
        previewBinding.duration.text = android.text.format.DateUtils.formatElapsedTime(duration / 1000L)
        previewBinding.progressBar.setPlayedColor(0xFFB388FF.toInt())
        previewBinding.progressBar.setScrubberColor(0xFFB388FF.toInt())
    }

    private fun buildAppBar(): View = LinearLayout(requireContext()).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setBackgroundColor(0xFF141218.toInt())
        elevation = dp(4).toFloat()
        addView(MaterialButton(context).apply {
            text = "Cancel"
            isAllCaps = false
            setOnClickListener { requireActivity().finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        addView(TextView(context).apply {
            text = "Customize player HUD"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        addView(MaterialButton(context).apply {
            text = "Save"
            isAllCaps = false
            setOnClickListener {
                store.save(workingConfig)
                (requireActivity() as? SettingsActivity)?.setHudResult()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
    }

    private fun sectionLabel(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 17f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun instructionLabel(): TextView = TextView(requireContext()).apply {
        text = "Tap to select • drag after a short hold to move • use the controls below to resize or align"
        textSize = 13f
        setTextColor(0xFFB8B0C2.toInt())
        setPadding(0, dp(8), 0, 0)
    }

    private fun orientationButton(text: String, action: () -> Unit): MaterialButton = MaterialButton(requireContext()).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun alignmentButton(text: String, action: () -> Unit): MaterialButton = MaterialButton(requireContext()).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        setOnClickListener { action() }
    }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        marginEnd = dp(4)
    }

    private fun addElementRow(id: HudElementId) {
        val row = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPadding(dp(12), 0, dp(8), 0)
            background = roundedBackground(0x0029242F)
            setOnClickListener { selectElement(id) }
        }
        row.addView(TextView(requireContext()).apply {
            text = displayName(id)
            textSize = 15f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = MaterialSwitch(requireContext()).apply {
            contentDescription = "Show ${displayName(id)}"
            setOnCheckedChangeListener { _, value ->
                if (!suppressPanelCallbacks) {
                    selectElement(id)
                    updateSelected(id) { placement -> placement.copy(enabled = value) }
                }
            }
        }
        elementSwitches[id] = toggle
        elementRows[id] = row
        row.addView(toggle, LinearLayout.LayoutParams(dp(56), dp(48)))
        elementList.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
    }

    private fun selectOrientation(value: HudOrientation) {
        orientation = value
        updateOrientationButtons()
        setProfile(currentProfile())
        selected?.let(::selectElement)
        updateElementList()
    }

    private fun updateOrientationButtons() {
        listOf(
            portraitButton to (orientation == HudOrientation.PORTRAIT),
            landscapeButton to (orientation == HudOrientation.LANDSCAPE),
        ).forEach { (button, isSelected) ->
            button.isSelected = isSelected
            button.backgroundTintList = ColorStateList.valueOf(
                if (isSelected) 0xFFB9C5FF.toInt() else 0xFF332D3B.toInt(),
            )
            button.setTextColor(if (isSelected) 0xFF241A3A.toInt() else Color.WHITE)
        }
    }

    private fun setProfile(value: HudProfile) {
        workingConfig = when (orientation) {
            HudOrientation.PORTRAIT -> workingConfig.copy(portrait = value)
            HudOrientation.LANDSCAPE -> workingConfig.copy(landscape = value)
        }
        preview.setHudOrientationAndProfile(orientation, value)
        preview.setPreviewMode(true)
        preview.refreshAvailability()
        guideOverlay.invalidate()
        updateSelectedPanel()
        updateElementList()
    }

    private fun currentProfile(): HudProfile = when (orientation) {
        HudOrientation.PORTRAIT -> workingConfig.portrait
        HudOrientation.LANDSCAPE -> workingConfig.landscape
    }

    private fun placement(id: HudElementId): HudPlacement = currentProfile().placements[id]
        ?: if (preview.isLaidOut) {
            preview.defaultEditorPlacement(id)
        } else {
            HudDefaultLayout.semanticFallback(id, orientation)
        }

    private fun materializeDefault() {
        if (currentProfile().mode != HudProfileMode.DEFAULT) return
        setProfile(preview.materializedDefaultProfile())
    }

    private fun selectElement(id: HudElementId?) {
        selected = id
        guideOverlay.selected = id
        updateSelectedPanel()
        updateElementList()
        guideOverlay.invalidate()
    }

    private fun updateSelectedPanel() {
        val id = selected ?: run {
            selectedText.text = "Tap a control in the preview or choose one below"
            enabledSwitch.isVisible = false
            scaleSlider.isVisible = false
            globalScaleSlider.isVisible = false
            swapButton.isVisible = false
            return
        }
        val value = placement(id)
        selectedText.text = displayName(id)
        enabledSwitch.isVisible = true
        scaleSlider.isVisible = true
        globalScaleSlider.isVisible = true
        val spec = HudElementRegistry.get(id)
        swapButton.isVisible = value.enabled && spec.isInteractive && spec.pivot == HudPivot.CENTER
        suppressPanelCallbacks = true
        enabledSwitch.isChecked = value.enabled
        scaleSlider.valueFrom = spec.minimumScale * 100f
        scaleSlider.valueTo = spec.maximumScale * 100f
        scaleSlider.value = (value.scale * 100f).coerceIn(scaleSlider.valueFrom, scaleSlider.valueTo)
        globalScaleSlider.value = currentProfile().globalScale * 100f
        scaleValueText.text = "${scaleSlider.value.roundToInt()}%"
        globalScaleValueText.text = "${globalScaleSlider.value.roundToInt()}%"
        suppressPanelCallbacks = false
    }

    private fun updateSelected(id: HudElementId, transform: (HudPlacement) -> HudPlacement) {
        val originalProfile = currentProfile()
        if (currentProfile().mode == HudProfileMode.DEFAULT) materializeDefault()
        val current = placement(id)
        val requested = preview.clampEditorPlacement(id, transform(current))
        val next = if (!current.enabled && requested.enabled) {
            preview.collisionFreeEditorPlacement(id, requested)
        } else {
            requested
        }
        if (next == null) {
            if (originalProfile.mode == HudProfileMode.DEFAULT) setProfile(originalProfile)
            guideOverlay.collision = id
            updateSelectedPanel()
            updateElementList()
            return
        }
        setProfile(currentProfile().copy(mode = HudProfileMode.CUSTOM, placements = currentProfile().placements + (id to next)))
        preview.updateEditorPlacement(id, next)
        guideOverlay.collision = null
        updateSelectedPanel()
        updateElementList()
    }

    private fun updateWorkingPlacement(id: HudElementId, value: HudPlacement) {
        if (currentProfile().mode == HudProfileMode.DEFAULT) materializeDefault()
        val next = currentProfile().copy(
            mode = HudProfileMode.CUSTOM,
            placements = currentProfile().placements + (id to value),
        )
        workingConfig = when (orientation) {
            HudOrientation.PORTRAIT -> workingConfig.copy(portrait = next)
            HudOrientation.LANDSCAPE -> workingConfig.copy(landscape = next)
        }
    }

    private fun alignSelected(x: Float?, y: Float?) {
        val id = selected ?: return
        updateSelected(id) { value -> value.copy(x = x ?: value.x, y = y ?: value.y) }
    }

    private fun showSwapDialog() {
        val first = selected ?: return
        val firstSpec = HudElementRegistry.get(first)
        if (!placement(first).enabled || !firstSpec.isInteractive || firstSpec.pivot != HudPivot.CENTER) return
        val candidates = HudElementId.entries.filter { id ->
            id != first &&
                HudElementRegistry.get(id).isInteractive &&
                HudElementRegistry.get(id).pivot == HudPivot.CENTER &&
                placement(id).enabled
        }
        if (candidates.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Swap ${displayName(first)} with")
            .setItems(candidates.map(::displayName).toTypedArray()) { _, which ->
                swapElements(first, candidates[which])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun swapElements(first: HudElementId, second: HudElementId) {
        if (!placement(first).enabled || !placement(second).enabled) return
        if (!HudElementRegistry.get(first).isInteractive ||
            !HudElementRegistry.get(second).isInteractive ||
            HudElementRegistry.get(first).pivot != HudPivot.CENTER ||
            HudElementRegistry.get(second).pivot != HudPivot.CENTER
        ) return
        if (currentProfile().mode == HudProfileMode.DEFAULT) materializeDefault()
        val profile = currentProfile()
        val firstPlacement = placement(first)
        val secondPlacement = placement(second)
        val swapped = profile.copy(
            mode = HudProfileMode.CUSTOM,
            // A swap changes only semantic position. Enabled state and scale
            // belong to the element and must travel with its visual control.
            placements = profile.placements +
                (first to firstPlacement.copy(x = secondPlacement.x, y = secondPlacement.y)) +
                (second to secondPlacement.copy(x = firstPlacement.x, y = firstPlacement.y)),
        )
        if (preview.editorProfileHasCollision(swapped)) {
            guideOverlay.collision = first
            guideOverlay.invalidate()
            return
        }
        setProfile(
            swapped,
        )
        selectElement(first)
    }

    private fun resetElement(id: HudElementId) {
        if (currentProfile().mode == HudProfileMode.DEFAULT) materializeDefault()
        setProfile(currentProfile().copy(placements = currentProfile().placements - id))
        selectElement(id)
    }

    private fun updateElementList() {
        if (!::elementList.isInitialized) return
        elementSwitches.forEach { (id, toggle) ->
            val value = placement(id)
            suppressPanelCallbacks = true
            toggle.isChecked = value.enabled
            suppressPanelCallbacks = false
            elementRows[id]?.background = roundedBackground(
                if (id == selected) 0xFF463A5A.toInt() else 0x0029242F,
            )
        }
    }

    private fun displayName(id: HudElementId): String = id.name
        .replace('_', ' ')
        .lowercase()
        .replaceFirstChar(Char::uppercase)

    private fun roundedBackground(color: Int) = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(color)
    }

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private class PreviewAspectFrameLayout(context: Context) : FrameLayout(context) {
        private val aspectRatio = 16f / 9f
        private val maxPreviewWidth = (720f * resources.displayMetrics.density).roundToInt()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val availableWidth = when (MeasureSpec.getMode(widthMeasureSpec)) {
                MeasureSpec.UNSPECIFIED -> maxPreviewWidth
                else -> MeasureSpec.getSize(widthMeasureSpec)
            }
            val width = availableWidth.coerceAtMost(maxPreviewWidth).coerceAtLeast(1)
            val desiredHeight = (width / aspectRatio).roundToInt().coerceAtLeast(1)
            val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
                // The preview owns its aspect ratio. An explicit height would make the
                // editor show a letterboxed strip instead of the real player viewport.
                MeasureSpec.EXACTLY -> desiredHeight
                else -> desiredHeight.coerceAtMost(MeasureSpec.getSize(heightMeasureSpec).takeIf { it > 0 } ?: desiredHeight)
            }
            setMeasuredDimension(width, height)
            for (index in 0 until childCount) {
                getChildAt(index).measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
                )
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for (index in 0 until childCount) getChildAt(index).layout(0, 0, width, height)
        }
    }

    private class HudGuideOverlayView(
        context: Context,
        private val preview: PlayerHudLayout,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
        var selected: HudElementId? = null
        var collision: HudElementId? = null

        override fun onDraw(canvas: Canvas) {
            val safe = preview.safeHudRect()
            paint.style = Paint.Style.STROKE
            paint.color = if (collision != null) 0xFFFF6B6B.toInt() else 0x99B388FF.toInt()
            canvas.drawRect(safe.left, safe.top, safe.right, safe.bottom, paint)
            paint.style = Paint.Style.FILL
            paint.color = 0x669B7BFF
            canvas.drawRect(safe.centerX - 0.5f, safe.top, safe.centerX + 0.5f, safe.bottom, paint)
            canvas.drawRect(safe.left, safe.centerY - 0.5f, safe.right, safe.centerY + 0.5f, paint)
            preview.resolvedElements().forEach { element ->
                if (element.id == selected) {
                    paint.color = if (element.id == collision) 0xFFFF6B6B.toInt() else 0xFFB388FF.toInt()
                    paint.style = Paint.Style.STROKE
                    canvas.drawRect(
                        element.hitRect.left,
                        element.hitRect.top,
                        element.hitRect.right,
                        element.hitRect.bottom,
                        paint,
                    )
                }
            }
        }
    }
}
