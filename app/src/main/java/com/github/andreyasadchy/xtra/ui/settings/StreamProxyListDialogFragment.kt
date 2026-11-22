package com.github.andreyasadchy.xtra.ui.settings

import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.core.widget.NestedScrollView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.HttpProxyAddress
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class StreamProxyListDialogFragment : DialogFragment() {

    companion object {
        const val RESULT_KEY = "stream_proxy_list_updated"
        private const val ARG_HTTP_PROXY_BACKUPS = "http_proxy_backups"
        private const val STATE_URLS = "urls"
        private const val ACTION_MOVE_UP = 1
        private const val ACTION_MOVE_DOWN = 2
        private const val ACTION_REMOVE = 3

        fun newHttpProxyBackups(): StreamProxyListDialogFragment =
            StreamProxyListDialogFragment().apply {
                arguments = Bundle().apply { putBoolean(ARG_HTTP_PROXY_BACKUPS, true) }
            }
    }

    private val isHttpProxyBackupList: Boolean
        get() = arguments?.getBoolean(ARG_HTTP_PROXY_BACKUPS) == true

    private val listPreferenceKey: String
        get() = if (isHttpProxyBackupList) C.PROXY_BACKUP_SERVERS else C.PLAYER_PROXY_URL

    private val proxyUrls = mutableListOf<String>()
    private lateinit var urlList: LinearLayout
    private lateinit var emptyMessage: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val savedUrls = savedInstanceState?.getStringArrayList(STATE_URLS)
        if (savedUrls != null) {
            proxyUrls.addAll(savedUrls)
        } else {
            val savedList = requireContext().prefs().getString(listPreferenceKey, null).orEmpty()
            proxyUrls.addAll(if (isHttpProxyBackupList) {
                HttpProxyAddress.parseList(savedList).map(HttpProxyAddress::toString)
            } else {
                savedList.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            })
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(STATE_URLS, ArrayList(proxyUrls))
        super.onSaveInstanceState(outState)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val content = createContentView()
        renderUrls()
        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isHttpProxyBackupList) R.string.settings_http_proxy_backups else R.string.settings_stream_proxy_url)
            .setView(content)
            .setPositiveButton(R.string.settings_stream_proxy_done) { _, _ -> saveUrls() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun createContentView(): View {
        val context = requireContext()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        content.addView(TextView(context).apply {
            text = getString(if (isHttpProxyBackupList) {
                R.string.settings_http_proxy_backups_help
            } else {
                R.string.settings_stream_proxy_list_help
            })
            textSize = 14f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, 0, 0, dp(12))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        urlList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        content.addView(urlList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        emptyMessage = TextView(context).apply {
            text = getString(if (isHttpProxyBackupList) {
                R.string.settings_http_proxy_backups_empty
            } else {
                R.string.settings_stream_proxy_list_empty
            })
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        content.addView(emptyMessage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        content.addView(MaterialButton(context).apply {
            setText(if (isHttpProxyBackupList) R.string.settings_http_proxy_add_backup else R.string.settings_stream_proxy_add_url)
            setIconResource(R.drawable.ic_add)
            iconSize = dp(18)
            iconPadding = dp(8)
            setOnClickListener { showUrlEditor() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })

        return NestedScrollView(context).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun renderUrls() {
        if (!::urlList.isInitialized || !::emptyMessage.isInitialized) return
        urlList.removeAllViews()
        emptyMessage.visibility = if (proxyUrls.isEmpty()) View.VISIBLE else View.GONE

        proxyUrls.forEachIndexed { index, url ->
            val card = MaterialCardView(requireContext()).apply {
                radius = dp(12).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
                setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow))
                isClickable = true
                isFocusable = true
                setOnClickListener { showUrlEditor(index) }
            }
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(4), dp(6))
            }
            val order = TextView(requireContext()).apply {
                text = (index + 1).toString()
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSecondaryContainer))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(MaterialColors.getColor(card, com.google.android.material.R.attr.colorSecondaryContainer))
                }
            }
            row.addView(order, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
                marginEnd = dp(10)
            })

            val labels = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = false
            }
            labels.addView(TextView(requireContext()).apply {
                text = url
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            labels.addView(TextView(requireContext()).apply {
                text = getString(R.string.settings_stream_proxy_tap_to_edit)
                textSize = 12f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(ImageButton(requireContext()).apply {
                setImageResource(R.drawable.ic_happening_now_more)
                imageTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant),
                )
                contentDescription = getString(
                    if (isHttpProxyBackupList) R.string.settings_http_proxy_backup_actions else R.string.settings_stream_proxy_actions,
                    index + 1,
                )
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                val backgroundValue = TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, backgroundValue, true)) {
                    setBackgroundResource(backgroundValue.resourceId)
                }
                setOnClickListener { showActions(this, index) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))

            card.addView(row, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            urlList.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                if (index > 0) topMargin = dp(8)
            })
        }
    }

    private fun showActions(anchor: View, index: Int) {
        PopupMenu(requireContext(), anchor).apply {
            menu.add(Menu.NONE, ACTION_MOVE_UP, Menu.NONE, R.string.settings_stream_proxy_move_up).isEnabled = index > 0
            menu.add(Menu.NONE, ACTION_MOVE_DOWN, Menu.NONE, R.string.settings_stream_proxy_move_down).isEnabled = index < proxyUrls.lastIndex
            menu.add(Menu.NONE, ACTION_REMOVE, Menu.NONE, R.string.settings_stream_proxy_remove)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    ACTION_MOVE_UP -> if (index > 0) {
                        proxyUrls.add(index - 1, proxyUrls.removeAt(index))
                    }
                    ACTION_MOVE_DOWN -> if (index < proxyUrls.lastIndex) {
                        proxyUrls.add(index + 1, proxyUrls.removeAt(index))
                    }
                    ACTION_REMOVE -> proxyUrls.removeAt(index)
                    else -> return@setOnMenuItemClickListener false
                }
                renderUrls()
                true
            }
        }.show()
    }

    private fun showUrlEditor(index: Int = -1) {
        val context = requireContext()
        val input = TextInputEditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setText(proxyUrls.getOrNull(index).orEmpty())
            setSelection(text?.length ?: 0)
        }
        val inputLayout = TextInputLayout(context).apply {
            hint = getString(if (isHttpProxyBackupList) {
                R.string.settings_http_proxy_backup_hint
            } else {
                R.string.settings_stream_proxy_url_hint
            })
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        input.doAfterTextChanged { inputLayout.error = null }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(if (index >= 0) R.string.settings_stream_proxy_edit_url else R.string.settings_stream_proxy_add_url)
            .setView(inputLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (index >= 0) R.string.settings_stream_proxy_save else R.string.settings_stream_proxy_add, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val url = input.text?.toString()?.trim().orEmpty()
                val normalizedUrl = if (isHttpProxyBackupList) {
                    HttpProxyAddress.parse(url)?.toString()
                } else url
                when {
                    url.isEmpty() -> inputLayout.error = getString(if (isHttpProxyBackupList) {
                        R.string.settings_http_proxy_backup_required
                    } else {
                        R.string.settings_stream_proxy_url_required
                    })
                    normalizedUrl == null -> inputLayout.error = getString(R.string.settings_http_proxy_backup_invalid)
                    isHttpProxyBackupList && index < 0 && proxyUrls.size >= HttpProxyAddress.MAX_ENTRIES ->
                        inputLayout.error = getString(R.string.settings_http_proxy_backup_limit, HttpProxyAddress.MAX_ENTRIES)
                    proxyUrls.withIndex().any { (urlIndex, current) ->
                        urlIndex != index && (if (isHttpProxyBackupList) {
                            HttpProxyAddress.parse(current)?.toString() == normalizedUrl
                        } else current == normalizedUrl)
                    } -> inputLayout.error = getString(if (isHttpProxyBackupList) {
                        R.string.settings_http_proxy_backup_duplicate
                    } else {
                        R.string.settings_stream_proxy_duplicate_url
                    })
                    else -> {
                        if (index >= 0) proxyUrls[index] = normalizedUrl else proxyUrls.add(normalizedUrl)
                        renderUrls()
                        hideKeyboard(input)
                        dialog.dismiss()
                    }
                }
            }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                hideKeyboard(input)
                dialog.dismiss()
            }
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
            input.requestFocus()
            input.post {
                val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputMethodManager.showSoftInput(input, 0)
            }
        }
        dialog.show()
    }

    private fun hideKeyboard(input: View) {
        val inputMethodManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.hideSoftInputFromWindow(input.windowToken, 0)
        input.clearFocus()
    }

    private fun saveUrls() {
        requireContext().prefs().edit {
            if (proxyUrls.isEmpty()) remove(listPreferenceKey)
            else putString(listPreferenceKey, proxyUrls.joinToString("\n"))
        }
        parentFragmentManager.setFragmentResult(RESULT_KEY, Bundle())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
