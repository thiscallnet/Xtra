package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem
import kotlin.math.roundToInt

class SettingsLayoutPreview(
    context: Context,
    private val items: List<SettingsDragListItem>,
    private val mode: Mode,
) : MaterialCardView(context) {

    enum class Mode {
        NAVIGATION,
        TABS,
        SECTIONS,
    }

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(10))
    }
    private val previewFrame = FrameLayout(context)

    init {
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = themeColor(com.google.android.material.R.attr.colorOutline, Color.GRAY)
        setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerLow, Color.DKGRAY))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val title = TextView(context).apply {
            text = context.getString(R.string.settings_layout_preview)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(android.R.attr.textColorPrimary, Color.WHITE))
        }
        content.addView(title)
        val hint = TextView(context).apply {
            text = context.getString(
                when (mode) {
                    Mode.NAVIGATION, Mode.TABS -> R.string.settings_tabs_preview_help
                    Mode.SECTIONS -> R.string.settings_sections_preview_help
                },
            )
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(themeColor(android.R.attr.textColorSecondary, Color.GRAY))
            setPadding(0, dp(2), 0, dp(7))
        }
        content.addView(hint)
        content.addView(
            previewFrame,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(142)),
        )
        refresh()
    }

    fun refresh() {
        previewFrame.removeAllViews()
        previewFrame.background = roundedBackground(Color.rgb(18, 21, 28), dp(10))
        when (mode) {
            Mode.NAVIGATION -> buildNavigationPreview()
            Mode.TABS -> buildTabsPreview()
            Mode.SECTIONS -> buildSectionsPreview()
        }
    }

    private fun buildNavigationPreview() {
        addToolbar()
        addContentLines(top = dp(38))
        addNavigationBar()
    }

    private fun buildTabsPreview() {
        addToolbar()
        addTabStrip(top = dp(34))
        addContentLines(top = dp(72))
    }

    private fun buildSectionsPreview() {
        addToolbar()
        val enabled = items.filter { it.enabled }
        if (enabled.isEmpty()) {
            previewFrame.addView(TextView(context).apply {
                text = context.getString(R.string.settings_customize_controls_empty)
                gravity = Gravity.CENTER
                setTextColor(themeColor(android.R.attr.textColorSecondary, Color.GRAY))
            }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            return
        }
        enabled.take(3).forEachIndexed { index, item ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(9), dp(5), dp(9), dp(5))
                background = roundedBackground(Color.rgb(34, 39, 49), dp(7))
            }
            row.addView(TextView(context).apply {
                text = item.text
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(android.R.attr.textColorPrimary, Color.WHITE))
            })
            row.addView(View(context).apply {
                setBackgroundColor(Color.argb(65, 255, 255, 255))
            }, LinearLayout.LayoutParams((width * 0.52f).roundToInt().coerceAtLeast(dp(70)), dp(4)).apply {
                topMargin = dp(4)
            })
            previewFrame.addView(
                row,
                FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(34)).apply {
                    leftMargin = dp(8)
                    rightMargin = dp(8)
                    topMargin = dp(36) + index * dp(35)
                },
            )
        }
    }

    private fun addToolbar() {
        val toolbar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        toolbar.addView(TextView(context).apply {
            text = "Xtra"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        toolbar.addView(TextView(context).apply {
            text = "•••"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.argb(190, 255, 255, 255))
        })
        previewFrame.addView(toolbar, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(32)))
    }

    private fun addContentLines(top: Int) {
        val lines = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), 0)
        }
        repeat(2) { index ->
            lines.addView(View(context).apply {
                setBackgroundColor(Color.argb(if (index == 0) 105 else 62, 255, 255, 255))
            }, LinearLayout.LayoutParams(if (index == 0) dp(108) else dp(76), dp(5)).apply {
                bottomMargin = dp(5)
            })
        }
        previewFrame.addView(lines, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(35)).apply {
            topMargin = top
        })
    }

    private fun addNavigationBar() {
        val bar = chipRow(items.filter { it.enabled }, selectedUsesDefault = true)
        previewFrame.addView(bar, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(49)).apply {
            gravity = Gravity.BOTTOM
        })
    }

    private fun addTabStrip(top: Int) {
        val bar = chipRow(items.filter { it.enabled }, selectedUsesDefault = true)
        previewFrame.addView(bar, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(39)).apply {
            topMargin = top
        })
    }

    private fun chipRow(visibleItems: List<SettingsDragListItem>, selectedUsesDefault: Boolean): HorizontalScrollView {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        if (visibleItems.isEmpty()) {
            row.addView(TextView(context).apply {
                text = context.getString(R.string.settings_customize_controls_empty)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.argb(150, 255, 255, 255))
                gravity = Gravity.CENTER_VERTICAL
            })
        } else {
            visibleItems.forEach { item ->
                val selected = selectedUsesDefault && item.default
                row.addView(TextView(context).apply {
                    text = item.text
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
                    setTextColor(if (selected) {
                        themeColor(com.google.android.material.R.attr.colorOnPrimaryContainer, Color.WHITE)
                    } else {
                        Color.argb(215, 255, 255, 255)
                    })
                    background = roundedBackground(
                        if (selected) themeColor(com.google.android.material.R.attr.colorPrimaryContainer, Color.DKGRAY)
                        else Color.rgb(42, 48, 60),
                        dp(8),
                    )
                    setPadding(dp(9), 0, dp(9), 0)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(29)).apply {
                    marginEnd = dp(5)
                })
            }
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radius.toFloat()
        setColor(color)
    }

    private fun themeColor(attribute: Int, fallback: Int): Int {
        val value = android.util.TypedValue()
        if (!context.theme.resolveAttribute(attribute, value, true)) return fallback
        return if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
