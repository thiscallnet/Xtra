package com.github.andreyasadchy.xtra.ui.settings.hud

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.PlayerLayoutBinding
import com.github.andreyasadchy.xtra.ui.player.hud.HudDefaultLayout
import com.github.andreyasadchy.xtra.ui.player.hud.HudConfigShareCodec
import com.github.andreyasadchy.xtra.ui.player.hud.HudConfigMigration
import com.github.andreyasadchy.xtra.ui.player.hud.HudElementId
import com.github.andreyasadchy.xtra.ui.player.hud.HudElementRegistry
import com.github.andreyasadchy.xtra.ui.player.hud.HudEditorHorizontalAlignment
import com.github.andreyasadchy.xtra.ui.player.hud.HudEditorVerticalAlignment
import com.github.andreyasadchy.xtra.ui.player.hud.HudOrientation
import com.github.andreyasadchy.xtra.ui.player.hud.HudPlacement
import com.github.andreyasadchy.xtra.ui.player.hud.HudPivot
import com.github.andreyasadchy.xtra.ui.player.hud.HudProfile
import com.github.andreyasadchy.xtra.ui.player.hud.HudProfileMode
import com.github.andreyasadchy.xtra.ui.player.hud.HudScale
import com.github.andreyasadchy.xtra.ui.player.hud.PlayerHudConfig
import com.github.andreyasadchy.xtra.ui.player.hud.PlayerHudDefaults
import com.github.andreyasadchy.xtra.ui.player.hud.PlayerHudLayout
import com.github.andreyasadchy.xtra.ui.player.hud.profile
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import java.util.ArrayDeque
import kotlin.math.roundToInt

/**
 * Full-screen HUD editor. The preview is an actual Media3 player with the
 * production HUD hierarchy overlaid on it; only its media source is local and
 * deterministic so opening settings never depends on login or network state.
 */
class PlayerHudEditorFragment : Fragment() {
    private companion object {
        const val HISTORY_LIMIT = 50
        const val PREVIEW_LIVE_EDGE_MS = 4L * 60L * 60L * 1000L + 20L * 60L * 1000L + 27L * 1000L
    }

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
    private lateinit var selectedControlsContainer: LinearLayout
    private lateinit var swapButton: MaterialButton
    private lateinit var portraitButton: MaterialButton
    private lateinit var landscapeButton: MaterialButton
    private lateinit var undoButton: ImageButton
    private lateinit var redoButton: ImageButton
    private lateinit var elementList: LinearLayout
    private lateinit var editorScroll: NestedScrollView

    private var previewPlayer: ExoPlayer? = null
    private var workingConfig: PlayerHudConfig = PlayerHudDefaults.config()
    private var orientation = HudOrientation.PORTRAIT
    // Start with a real selection so the controls are measured as visible on
    // the first pass. Selecting from preview.doOnLayout would otherwise make
    // these children visible after the parent had already measured them as
    // GONE, leaving their measured size at 0 until another unrelated layout.
    private var selected: HudElementId? = HudElementId.PLAY_PAUSE
    private var dragStartSnapshot: HudEditorSnapshot? = null
    private var sliderStartSnapshot: HudEditorSnapshot? = null
    private var suppressPanelCallbacks = false
    private val elementSwitches = linkedMapOf<HudElementId, MaterialSwitch>()
    private val elementRows = linkedMapOf<HudElementId, View>()
    private val undoStack = ArrayDeque<HudEditorSnapshot>()
    private val redoStack = ArrayDeque<HudEditorSnapshot>()

    private data class HudEditorSnapshot(
        val config: PlayerHudConfig,
        val orientation: HudOrientation,
        val selected: HudElementId?,
    )

    private val previewProgressUpdate = object : Runnable {
        override fun run() {
            if (!isAdded || !::preview.isInitialized) return
            syncPreviewProgress()
            preview.postDelayed(this, 250L)
        }
    }

    private val sliderHistoryListener = object : Slider.OnSliderTouchListener {
        override fun onStartTrackingTouch(slider: Slider) {
            sliderStartSnapshot = snapshot()
        }

        override fun onStopTrackingTouch(slider: Slider) {
            sliderStartSnapshot?.let { commitAction(it) }
            sliderStartSnapshot = null
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

        editorScroll = NestedScrollView(requireContext()).apply {
            clipToPadding = false
            isFillViewport = true
            isNestedScrollingEnabled = true
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(32))
        }
        editorScroll.addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(
            editorScroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                topMargin = dp(56)
            },
        )
        root.addView(buildAppBar(), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        content.addView(sectionLabel("Preview"))
        val orientationSelector = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        portraitButton = orientationButton("Portrait") { selectOrientation(HudOrientation.PORTRAIT) }
        landscapeButton = orientationButton("Landscape") { selectOrientation(HudOrientation.LANDSCAPE) }
        orientationSelector.addView(portraitButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        orientationSelector.addView(landscapeButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        content.addView(orientationSelector, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        content.addView(MaterialButton(requireContext()).apply {
            text = getString(R.string.settings_hud_use_for_both)
            isAllCaps = false
            configureFullWidthTextButton()
            setOnClickListener { useCurrentSetupForBoth() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(4)
            bottomMargin = dp(8)
        })

        previewContainer = PreviewAspectFrameLayout(requireContext()).apply {
            setBackgroundColor(Color.BLACK)
            elevation = dp(2).toFloat()
            // The progress handle straddles the video/content boundary, like
            // a conventional video player. Let the lower half remain visible
            // instead of clipping it at the preview's last row of pixels.
            clipChildren = false
            clipToPadding = false
        }
        previewBinding = PlayerLayoutBinding.inflate(inflater, previewContainer, false)
        preview = previewBinding.root
        preview.visibility = View.VISIBLE
        preview.alpha = 1f
        preview.setSafeInsetsOverride(Rect())
        preview.setHudOrientation(orientation)
        preview.setPreviewMode(true)

        previewPlayerView = inflater.inflate(
            R.layout.view_stream_preview,
            previewContainer,
            false,
        ) as PlayerView
        previewPlayerView.apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setKeepContentOnPlayerReset(true)
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
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(0xFF29242F.toInt())
        }
        selectedText = TextView(requireContext()).apply {
            text = "Tap a control in the preview or choose one below"
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        selectedPanel.addView(selectedText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        selectedControlsContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
        }
        selectedPanel.addView(
            selectedControlsContainer,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val enabledRow = LinearLayout(requireContext()).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        enabledRow.addView(TextView(requireContext()).apply {
            text = "Show this control"
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        enabledSwitch = MaterialSwitch(requireContext()).apply {
            contentDescription = "Show this control"
            setOnCheckedChangeListener { _, value ->
                if (!suppressPanelCallbacks) {
                    selected?.let { id ->
                        updateSelected(id, transform = { placement -> placement.copy(enabled = value) })
                    }
                }
            }
        }
        enabledRow.addView(enabledSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        selectedControlsContainer.addView(enabledRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        val scaleHeader = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
        scaleHeader.addView(TextView(requireContext()).apply {
            text = "Selected control size"
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, dp(32), 1f))
        scaleValueText = TextView(requireContext()).apply {
            setTextColor(0xFFD0C2FF.toInt())
            gravity = Gravity.CENTER_VERTICAL
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        scaleHeader.addView(scaleValueText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
        selectedControlsContainer.addView(scaleHeader)
        scaleSlider = Slider(requireContext()).apply {
            valueFrom = HudScale.ELEMENT_MIN * 100f
            valueTo = HudScale.ELEMENT_MAX * 100f
            stepSize = 1f
            addOnChangeListener { _, value, fromUser ->
                if (fromUser && !suppressPanelCallbacks) {
                    selected?.let {
                        updateSelected(
                            it,
                            transform = { placement -> placement.copy(scale = value.roundToInt() / 100f) },
                            recordHistory = false,
                        )
                    }
                }
            }
            addOnSliderTouchListener(sliderHistoryListener)
        }
        selectedControlsContainer.addView(scaleSlider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        val globalHeader = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
        globalHeader.addView(TextView(requireContext()).apply {
            text = "Overall HUD size"
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, dp(32), 1f))
        globalScaleValueText = TextView(requireContext()).apply {
            setTextColor(0xFFD0C2FF.toInt())
            gravity = Gravity.CENTER_VERTICAL
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        globalHeader.addView(globalScaleValueText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)))
        selectedControlsContainer.addView(globalHeader)
        globalScaleSlider = Slider(requireContext()).apply {
            valueFrom = HudScale.GLOBAL_MIN * 100f
            valueTo = HudScale.GLOBAL_MAX * 100f
            stepSize = 1f
            addOnChangeListener { _, value, fromUser ->
                if (fromUser && !suppressPanelCallbacks) {
                    if (currentProfile().mode == HudProfileMode.DEFAULT) materializeDefault()
                    setProfile(currentProfile().copy(globalScale = value.roundToInt() / 100f))
                }
            }
            addOnSliderTouchListener(sliderHistoryListener)
        }
        selectedControlsContainer.addView(globalScaleSlider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        selectedControlsContainer.addView(TextView(requireContext()).apply {
            text = "Align selected control"
            textSize = 14f
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))
        val alignment = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        alignment.addView(alignmentButton("Center X") {
            alignSelected(horizontal = HudEditorHorizontalAlignment.CENTER)
        }, weightedButtonParams())
        alignment.addView(alignmentButton("Center Y") {
            alignSelected(vertical = HudEditorVerticalAlignment.CENTER)
        }, weightedButtonParams())
        alignment.addView(alignmentButton("Align...") { showAlignmentDialog() }, weightedButtonParams(last = true))
        selectedControlsContainer.addView(alignment, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val resetRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        resetRow.addView(alignmentButton("Reset position") { selected?.let(::resetElement) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        selectedControlsContainer.addView(resetRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(4)
        })
        swapButton = MaterialButton(requireContext()).apply {
            text = "Swap with..."
            isAllCaps = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minWidth = 0
            minimumWidth = 0
            insetLeft = 0
            insetRight = 0
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { showSwapDialog() }
        }
        selectedControlsContainer.addView(swapButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
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
        HudElementRegistry.all
            .filter { it.isMovable }
            .forEach { spec -> addElementRow(spec.id) }
        content.addView(elementList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val shareSection = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        shareSection.addView(sectionLabel(getString(R.string.settings_hud_share_setup)))
        shareSection.addView(TextView(requireContext()).apply {
            text = getString(R.string.settings_hud_share_setup_summary)
            textSize = 13f
            setTextColor(0xFFB8B0C2.toInt())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val shareButtons = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        shareButtons.addView(MaterialButton(requireContext()).apply {
            text = getString(R.string.settings_hud_copy_setup)
            isAllCaps = false
            configureFullWidthTextButton()
            setOnClickListener { copySetup() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        shareButtons.addView(MaterialButton(requireContext()).apply {
            text = getString(R.string.settings_hud_paste_setup)
            isAllCaps = false
            configureFullWidthTextButton()
            setOnClickListener { pasteSetup() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        shareSection.addView(shareButtons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
            topMargin = dp(8)
        })
        content.addView(shareSection)

        val resetSection = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, 0)
        }
        resetSection.addView(sectionLabel("Reset"))
        resetSection.addView(MaterialButton(requireContext()).apply {
            text = "Reset this orientation"
            isAllCaps = false
            configureFullWidthTextButton()
            setOnClickListener {
                val before = snapshot()
                setProfile(PlayerHudDefaults.config().profile(orientation))
                selectElement(null)
                commitAction(before)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        resetSection.addView(MaterialButton(requireContext()).apply {
            text = "Reset both orientations"
            isAllCaps = false
            configureFullWidthTextButton()
            setOnClickListener {
                val before = snapshot()
                workingConfig = PlayerHudDefaults.config()
                setProfile(currentProfile())
                selectElement(null)
                commitAction(before)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })
        content.addView(resetSection)

        preview.setEditing(
            true,
            onSelected = { id ->
                guideOverlay.clearCollision()
                selectElement(id)
            },
            onDragStarted = { id ->
                dragStartSnapshot = snapshot()
                guideOverlay.setDragging(true)
                guideOverlay.selected = id
                guideOverlay.invalidate()
            },
            onMoved = { id, value ->
                selected = id
                updateWorkingPlacement(id, value)
                guideOverlay.selected = id
                val snap = preview.editorSnapPreview(id, value)
                guideOverlay.setDragging(true)
                guideOverlay.setCollision(id, preview.editorCollisionIds(id, value))
                guideOverlay.setProspectiveGuides(snap.guides)
                guideOverlay.invalidate()
            },
            onDropped = { id, canceled, dragStart ->
                val before = dragStartSnapshot
                if (canceled) {
                    before?.let(::applySnapshot)
                    guideOverlay.clearCollision()
                    guideOverlay.setDragging(false)
                } else {
                    val raw = preview.editorPlacement(id) ?: placement(id)
                    val result = preview.resolveEditorDrop(id, dragStart ?: placement(id), raw)
                    if (result.profile == null) {
                        before?.let(::applySnapshot)
                        guideOverlay.setDragging(false)
                        guideOverlay.setCollision(id, result.blockers)
                        guideOverlay.showDropFeedback(result.explanation)
                    } else {
                        setProfile(result.profile)
                        before?.let { commitAction(it) }
                        guideOverlay.clearCollision()
                        guideOverlay.setDragging(false)
                        guideOverlay.showDropResult(result)
                    }
                }
                dragStartSnapshot = null
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
        if (previewPlayer == null) return
        // The video is real Media3 playback, but its short local asset is not
        // the stream represented by the HUD. Keep the editor's fake live state
        // stable and use the same single live-rewind time group as production.
        previewBinding.progressBar.setDuration(PREVIEW_LIVE_EDGE_MS)
        previewBinding.progressBar.setPosition(PREVIEW_LIVE_EDGE_MS)
        previewBinding.progressBar.setBufferedPosition(PREVIEW_LIVE_EDGE_MS)
        previewBinding.progressBar.setPlayedColor(0xFFB388FF.toInt())
        previewBinding.progressBar.setScrubberColor(0xFFB388FF.toInt())
    }

    private fun buildAppBar(): View = LinearLayout(requireContext()).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setBackgroundColor(0xFF141218.toInt())
        elevation = dp(4).toFloat()
        addView(MaterialButton(context).apply {
            text = "Cancel"
            isAllCaps = false
            configureToolbarTextButton()
            setOnClickListener { requireActivity().finish() }
        }, LinearLayout.LayoutParams(dp(64), dp(48)))
        undoButton = historyButton(R.drawable.ic_undo_24, "Undo") { undo() }
        addView(undoButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(2) })
        redoButton = historyButton(R.drawable.ic_redo_24, "Redo") { redo() }
        addView(redoButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(2) })
        addView(TextView(context).apply {
            text = "HUD editor"
            contentDescription = getString(R.string.settings_customize_hud)
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) })
        addView(MaterialButton(context).apply {
            text = "Save"
            isAllCaps = false
            configureToolbarTextButton()
            setOnClickListener {
                store.save(workingConfig)
                (requireActivity() as? SettingsActivity)?.setHudResult()
            }
        }, LinearLayout.LayoutParams(dp(64), dp(48)))
    }

    private fun historyButton(icon: Int, description: String, action: () -> Unit): ImageButton =
        ImageButton(requireContext()).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                roundedBackground(0xFF302B38.toInt()),
                null,
            )
            contentDescription = description
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setPadding(0, 0, 0, 0)
            setOnClickListener { action() }
        }

    private fun MaterialButton.configureToolbarTextButton() {
        textSize = 14f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        minWidth = 0
        minimumWidth = 0
        insetTop = 0
        insetBottom = 0
        insetLeft = 0
        insetRight = 0
        setPadding(dp(4), 0, dp(4), 0)
    }

    private fun MaterialButton.configureFullWidthTextButton() {
        textSize = 14f
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        minWidth = 0
        minimumWidth = 0
        insetLeft = 0
        insetRight = 0
        setPadding(dp(8), 0, dp(8), 0)
    }

    private fun sectionLabel(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 17f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun instructionLabel(): TextView = TextView(requireContext()).apply {
        text = "Tap to select. Hold and drag to move it. Adjust size or alignment below."
        textSize = 12f
        setTextColor(0xFFB8B0C2.toInt())
        setPadding(0, dp(8), 0, 0)
        maxLines = 2
    }

    private fun orientationButton(text: String, action: () -> Unit): MaterialButton = MaterialButton(requireContext()).apply {
        this.text = text
        isAllCaps = false
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        minWidth = 0
        minimumWidth = 0
        insetLeft = 0
        insetRight = 0
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { action() }
    }

    private fun alignmentButton(text: String, action: () -> Unit): MaterialButton = MaterialButton(requireContext()).apply {
        this.text = text
        isAllCaps = false
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        minWidth = 0
        minimumWidth = 0
        insetLeft = 0
        insetRight = 0
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { action() }
    }

    private fun weightedButtonParams(last: Boolean = false) = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        if (!last) marginEnd = dp(4)
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
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minWidth = 0
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val toggle = MaterialSwitch(requireContext()).apply {
            contentDescription = "Show ${displayName(id)}"
            minWidth = 0
            minimumWidth = dp(48)
            setOnCheckedChangeListener { _, value ->
                if (!suppressPanelCallbacks) {
                    selectElement(id)
                    updateSelected(id, transform = { placement -> placement.copy(enabled = value) })
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
        guideOverlay.invalidate()
        updateSelectedPanel()
        updateElementList()
        updateHistoryButtons()
    }

    private fun snapshot(): HudEditorSnapshot = HudEditorSnapshot(workingConfig, orientation, selected)

    private fun commitAction(before: HudEditorSnapshot) {
        val after = snapshot()
        if (before.config == after.config) return
        undoStack.addLast(before)
        while (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        updateHistoryButtons()
    }

    private fun applySnapshot(value: HudEditorSnapshot) {
        workingConfig = value.config
        orientation = value.orientation
        selected = value.selected
        updateOrientationButtons()
        preview.setHudOrientationAndProfile(orientation, currentProfile())
        guideOverlay.clearCollision()
        guideOverlay.setDragging(false)
        guideOverlay.selected = selected
        updateSelectedPanel()
        updateElementList()
        updateHistoryButtons()
        guideOverlay.invalidate()
    }

    private fun undo() {
        if (undoStack.isEmpty()) return
        val target = undoStack.removeLast()
        redoStack.addLast(snapshot())
        applySnapshot(target)
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        val target = redoStack.removeLast()
        undoStack.addLast(snapshot())
        applySnapshot(target)
    }

    private fun updateHistoryButtons() {
        if (!::undoButton.isInitialized || !::redoButton.isInitialized) return
        undoButton.isEnabled = undoStack.isNotEmpty()
        redoButton.isEnabled = redoStack.isNotEmpty()
        undoButton.alpha = if (undoButton.isEnabled) 1f else 0.45f
        redoButton.alpha = if (redoButton.isEnabled) 1f else 0.45f
    }

    private fun currentProfile(): HudProfile = when (orientation) {
        HudOrientation.PORTRAIT -> workingConfig.portrait
        HudOrientation.LANDSCAPE -> workingConfig.landscape
    }

    private fun placement(id: HudElementId): HudPlacement = currentProfile().placements[id]
        ?: if (preview.isLaidOut) {
            preview.defaultEditorPlacement(id)
        } else {
            HudDefaultLayout.semanticFallback(id, orientation, currentProfile().defaultPolicyVersion)
        }

    private fun materializeDefault() {
        if (currentProfile().mode != HudProfileMode.DEFAULT) return
        val materialized = preview.materializedDefaultProfile()
        workingConfig = when (orientation) {
            HudOrientation.PORTRAIT -> workingConfig.copy(portrait = materialized)
            HudOrientation.LANDSCAPE -> workingConfig.copy(landscape = materialized)
        }
    }

    private fun selectElement(id: HudElementId?) {
        selected = id
        guideOverlay.clearCollision()
        guideOverlay.selected = id
        updateSelectedPanel()
        updateElementList()
        guideOverlay.invalidate()
    }

    private fun updateSelectedPanel() {
        val id = selected ?: run {
            selectedText.text = "Tap a control in the preview or choose one below"
            setSelectedControlsVisible(false)
            return
        }
        val value = placement(id)
        selectedText.text = displayName(id)
        setSelectedControlsVisible(true)
        val spec = HudElementRegistry.get(id)
        setSwapButtonVisible(value.enabled && spec.isInteractive && spec.pivot == HudPivot.CENTER)
        suppressPanelCallbacks = true
        enabledSwitch.isChecked = value.enabled
        bindSlider(
            scaleSlider,
            (spec.minimumScale * 100f).roundToInt(),
            (spec.maximumScale * 100f).roundToInt(),
            (value.scale * 100f).roundToInt(),
        )
        bindSlider(
            globalScaleSlider,
            (HudScale.GLOBAL_MIN * 100f).roundToInt(),
            (HudScale.GLOBAL_MAX * 100f).roundToInt(),
            (currentProfile().globalScale * 100f).roundToInt(),
        )
        scaleValueText.text = "${scaleSlider.value.roundToInt()}%"
        globalScaleValueText.text = "${globalScaleSlider.value.roundToInt()}%"
        suppressPanelCallbacks = false
    }

    private fun setSelectedControlsVisible(visible: Boolean) {
        if (selectedControlsContainer.isVisible == visible) return
        selectedControlsContainer.isVisible = visible
        selectedControlsContainer.requestLayout()
        editorScroll.requestLayout()
    }

    private fun setSwapButtonVisible(visible: Boolean) {
        if (swapButton.isVisible == visible) return
        swapButton.isVisible = visible
        selectedControlsContainer.requestLayout()
        editorScroll.requestLayout()
    }

    private fun updateSelected(
        id: HudElementId,
        transform: (HudPlacement) -> HudPlacement,
        recordHistory: Boolean = true,
    ) {
        val before = snapshot()
        val originalProfile = currentProfile()
        if (currentProfile().mode == HudProfileMode.DEFAULT) materializeDefault()
        val current = placement(id)
        val requested = preview.clampEditorPlacement(id, transform(current))
        val next = if (!requested.enabled) {
            requested
        } else {
            preview.repairEditorPlacement(id, requested)
                ?: if (!current.enabled) preview.collisionFreeEditorPlacement(id, requested) else null
        }
        if (next != null && next.enabled && preview.editorDropHasCollision(id, next)) {
            if (originalProfile.mode == HudProfileMode.DEFAULT) setProfile(originalProfile)
            guideOverlay.setCollision(id, preview.editorCollisionIds(id, next))
            updateSelectedPanel()
            updateElementList()
            return
        }
        if (next == null) {
            if (originalProfile.mode == HudProfileMode.DEFAULT) setProfile(originalProfile)
            guideOverlay.setCollision(id, preview.editorCollisionIds(id, requested))
            updateSelectedPanel()
            updateElementList()
            return
        }
        setProfile(currentProfile().copy(mode = HudProfileMode.CUSTOM, placements = currentProfile().placements + (id to next)))
        guideOverlay.clearCollision()
        if (recordHistory) commitAction(before)
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

    private fun alignSelected(
        horizontal: HudEditorHorizontalAlignment? = null,
        vertical: HudEditorVerticalAlignment? = null,
    ) {
        val id = selected ?: return
        val requested = preview.editorPlacementAlignedToCanvas(id, horizontal, vertical) ?: return
        updateSelected(id, transform = { requested })
    }

    private fun showAlignmentDialog() {
        val id = selected ?: return
        val targetIds = preview.resolvedElements()
            .map { it.id }
            .filter { it != id }
        val actions = buildList<Pair<String, () -> Unit>> {
            add("Left edge" to { alignSelected(horizontal = HudEditorHorizontalAlignment.LEFT) })
            add("Center X" to { alignSelected(horizontal = HudEditorHorizontalAlignment.CENTER) })
            add("Right edge" to { alignSelected(horizontal = HudEditorHorizontalAlignment.RIGHT) })
            add("Top edge" to { alignSelected(vertical = HudEditorVerticalAlignment.TOP) })
            add("Center Y" to { alignSelected(vertical = HudEditorVerticalAlignment.CENTER) })
            add("Bottom edge" to { alignSelected(vertical = HudEditorVerticalAlignment.BOTTOM) })
            targetIds.forEach { targetId ->
                add("Center with ${displayName(targetId)}" to {
                    preview.editorPlacementAlignedTo(id, targetId)?.let { requested ->
                        updateSelected(id, transform = { requested })
                    }
                })
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Align ${displayName(id)}")
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSwapDialog() {
        val first = selected ?: return
        val firstSpec = HudElementRegistry.get(first)
        if (!placement(first).enabled || !firstSpec.isInteractive || firstSpec.pivot != HudPivot.CENTER) return
        val candidates = HudElementRegistry.activeIds.filter { id ->
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
        val before = snapshot()
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
            guideOverlay.setCollision(first, setOf(second))
            guideOverlay.invalidate()
            return
        }
        setProfile(
            swapped,
        )
        commitAction(before)
        selectElement(first)
    }

    private fun resetElement(id: HudElementId) {
        val before = snapshot()
        if (currentProfile().mode == HudProfileMode.DEFAULT) materializeDefault()
        setProfile(currentProfile().copy(placements = currentProfile().placements - id))
        commitAction(before)
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

    private fun copySetup() {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.settings_hud_setup_clip_label),
                HudConfigShareCodec.encode(workingConfig),
            ),
        )
        Toast.makeText(requireContext(), R.string.settings_hud_setup_copied, Toast.LENGTH_SHORT).show()
    }

    private fun useCurrentSetupForBoth() {
        val before = snapshot()
        val current = currentProfile()
        workingConfig = workingConfig.copy(portrait = current, landscape = current)
        preview.setHudOrientationAndProfile(orientation, current)
        guideOverlay.clearCollision()
        guideOverlay.setDragging(false)
        guideOverlay.selected = selected
        updateSelectedPanel()
        updateElementList()
        guideOverlay.invalidate()
        commitAction(before)
        Toast.makeText(requireContext(), R.string.settings_hud_setup_copied_to_both, Toast.LENGTH_SHORT).show()
    }

    private fun pasteSetup() {
        val raw = runCatching {
            requireContext().getSystemService(ClipboardManager::class.java)
                ?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(requireContext())
                ?.toString()
                ?.trim()
        }.getOrNull().orEmpty()
        val imported = HudConfigShareCodec.decode(raw)?.let(HudConfigMigration::apply)
        if (imported == null) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_hud_setup_invalid_title)
                .setMessage(R.string.settings_hud_setup_invalid_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_hud_paste_setup_title)
            .setMessage(R.string.settings_hud_paste_setup_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.settings_hud_paste_setup) { _, _ -> applySharedSetup(imported) }
            .show()
    }

    private fun applySharedSetup(config: PlayerHudConfig) {
        val before = snapshot()
        workingConfig = config
        preview.setHudOrientationAndProfile(orientation, currentProfile())
        guideOverlay.clearCollision()
        guideOverlay.setDragging(false)
        guideOverlay.selected = selected
        updateSelectedPanel()
        updateElementList()
        guideOverlay.invalidate()
        commitAction(before)
    }

    private fun displayName(id: HudElementId): String = id.name
        .replace('_', ' ')
        .lowercase()
        .replaceFirstChar(Char::uppercase)

    private fun bindSlider(slider: Slider, minimum: Int, maximum: Int, value: Int) {
        val min = minimum.coerceAtMost(maximum)
        val max = maximum.coerceAtLeast(min + 1)
        // Material Slider validates stepped values against exact float
        // endpoints. Bind the editor in integer percentages, even though the
        // persisted layout model remains a Float.
        slider.stepSize = 0f
        slider.valueFrom = min.toFloat()
        slider.valueTo = max.toFloat()
        slider.value = value.coerceIn(min, max).toFloat()
        slider.stepSize = 1f
    }

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
            val exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            val exactHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                // HUD drag updates must not make Media3 resize its TextureView
                // surface. The video child owns one stable viewport; only the
                // HUD and guide overlay are remeasured as editor state changes.
                if (child is PlayerView &&
                    child.measuredWidth == width &&
                    child.measuredHeight == height &&
                    !child.isLayoutRequested
                ) {
                    continue
                }
                child.measure(exactWidth, exactHeight)
            }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                if (child is PlayerView &&
                    child.left == 0 &&
                    child.top == 0 &&
                    child.right == width &&
                    child.bottom == height
                ) {
                    continue
                }
                child.layout(0, 0, width, height)
            }
        }
    }

    private class HudGuideOverlayView(
        context: Context,
        private val preview: PlayerHudLayout,
    ) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
        var selected: HudElementId? = null
        var collision: HudElementId? = null
        private var collisionIds: Set<HudElementId> = emptySet()
        private var dragging = false
        private var prospectiveGuides: List<com.github.andreyasadchy.xtra.ui.player.hud.HudEditorGuide> = emptyList()
        private var releaseGuides: List<com.github.andreyasadchy.xtra.ui.player.hud.HudEditorGuide> = emptyList()
        private var feedbackText: String? = null
        private var feedbackUntil = 0L

        fun setDragging(value: Boolean) {
            dragging = value
            if (!value) prospectiveGuides = emptyList()
            invalidate()
        }

        fun setProspectiveGuides(value: List<com.github.andreyasadchy.xtra.ui.player.hud.HudEditorGuide>) {
            prospectiveGuides = value
        }

        fun setCollision(selectedId: HudElementId, blockers: Set<HudElementId>) {
            selected = selectedId
            collision = selectedId.takeIf { blockers.isNotEmpty() }
            collisionIds = if (collision == null) blockers else blockers + selectedId
        }

        fun clearCollision() {
            collision = null
            collisionIds = emptySet()
            feedbackText = null
            feedbackUntil = 0L
            prospectiveGuides = emptyList()
            releaseGuides = emptyList()
        }

        fun showDropResult(result: com.github.andreyasadchy.xtra.ui.player.hud.HudEditorDropResult) {
            releaseGuides = result.guides
            feedbackText = result.explanation
            feedbackUntil = android.os.SystemClock.uptimeMillis() + 1100L
            postDelayed({ invalidate() }, 1150L)
        }

        fun showDropFeedback(text: String?) {
            releaseGuides = emptyList()
            feedbackText = text
            feedbackUntil = android.os.SystemClock.uptimeMillis() + 1600L
            postDelayed({ invalidate() }, 1650L)
        }

        override fun onDraw(canvas: Canvas) {
            val safe = preview.safeHudRect()
            val showGuides = dragging || collision != null || android.os.SystemClock.uptimeMillis() < feedbackUntil
            if (showGuides) {
                paint.style = Paint.Style.STROKE
                paint.color = 0x99B388FF.toInt()
                canvas.drawRect(safe.left, safe.top, safe.right, safe.bottom, paint)
                val guides = prospectiveGuides + releaseGuides
                guides.forEach { guide ->
                    paint.style = Paint.Style.FILL
                    paint.color = when (guide.kind) {
                        com.github.andreyasadchy.xtra.ui.player.hud.HudEditorGuideKind.SAFE_CENTER -> 0xFFB388FF.toInt()
                        com.github.andreyasadchy.xtra.ui.player.hud.HudEditorGuideKind.CONTROL_BASELINE -> 0xFFE0B7FF.toInt()
                        else -> 0xCC8FD3FF.toInt()
                    }
                    if (guide.axis == com.github.andreyasadchy.xtra.ui.player.hud.HudEditorGuideAxis.VERTICAL) {
                        canvas.drawRect(guide.coordinate - 0.75f, safe.top, guide.coordinate + 0.75f, safe.bottom, paint)
                    } else {
                        canvas.drawRect(safe.left, guide.coordinate - 0.75f, safe.right, guide.coordinate + 0.75f, paint)
                    }
                }
            }
            preview.resolvedElements().forEach { element ->
                if (element.id != selected && element.id !in collisionIds) return@forEach
                val isSelected = element.id == selected
                val isCollision = element.id in collisionIds
                if (isSelected) {
                    // The visual bounds are the editor bounds. Runtime touch
                    // geometry follows the same rectangle, so the selection
                    // outline never advertises invisible padding.
                    paint.color = if (isCollision) 0xFFFF6B6B.toInt() else 0xFFB388FF.toInt()
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 2f
                    paint.pathEffect = null
                    canvas.drawRect(
                        element.visualRect.left,
                        element.visualRect.top,
                        element.visualRect.right,
                        element.visualRect.bottom,
                        paint,
                    )
                    if (element.hitRect != element.visualRect && (dragging || isCollision)) {
                        paint.color = if (isCollision) 0x99FF6B6B.toInt() else 0x669B8CFF
                        paint.strokeWidth = 1f
                        paint.pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
                        canvas.drawRect(
                            element.hitRect.left,
                            element.hitRect.top,
                            element.hitRect.right,
                            element.hitRect.bottom,
                            paint,
                        )
                        paint.pathEffect = null
                    }
                } else if (isCollision) {
                    // Collision feedback is about competing touch targets,
                    // therefore the blocker outline remains its hit rect.
                    paint.color = 0xFFFF6B6B.toInt()
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1f
                    paint.pathEffect = null
                    canvas.drawRect(
                        element.hitRect.left,
                        element.hitRect.top,
                        element.hitRect.right,
                        element.hitRect.bottom,
                        paint,
                    )
                }
            }
            val label = feedbackText ?: collision?.let { selectedId ->
                val blockers = collisionIds
                    .filter { it != selectedId }
                    .joinToString { it.name.replace('_', ' ').lowercase() }
                "Overlaps $blockers"
            }
            if (showGuides && label != null) {
                val density = resources.displayMetrics.density
                paint.textSize = 12f * density
                val textWidth = paint.measureText(label) + 24f * density
                val labelHeight = 28f * density
                val labelTop = (safe.bottom - labelHeight - 4f * density)
                    .coerceAtLeast(safe.top + 4f * density)
                paint.style = Paint.Style.FILL
                paint.color = 0xDD2A1D2F.toInt()
                canvas.drawRoundRect(
                    safe.centerX - textWidth / 2f,
                    labelTop,
                    safe.centerX + textWidth / 2f,
                    labelTop + labelHeight,
                    14f * density,
                    14f * density,
                    paint,
                )
                paint.color = Color.WHITE
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText(label, safe.centerX, labelTop + 18f * density, paint)
                paint.textAlign = Paint.Align.LEFT
            }
        }
    }
}
