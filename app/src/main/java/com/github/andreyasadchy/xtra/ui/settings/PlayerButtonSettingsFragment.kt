package com.github.andreyasadchy.xtra.ui.settings

import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.SettingsDragListItem
import com.github.andreyasadchy.xtra.util.isTelevision
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import java.util.Collections

class PlayerButtonSettingsFragment : MaterialPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.player_controls_preferences, rootKey)
        findPreference<Preference>("player_seek_controls")?.setOnPreferenceClickListener { findNavController().navigate(R.id.playerSeekFragment); true }
        findPreference<Preference>("player_gestures")?.setOnPreferenceClickListener { findNavController().navigate(R.id.playerGesturesFragment); true }
        findPreference<Preference>("player_information")?.setOnPreferenceClickListener { findNavController().navigate(R.id.playerInformationFragment); true }
        findPreference<Preference>("clip_settings")?.setOnPreferenceClickListener { findNavController().navigate(R.id.clipSettingsFragment); true }
        findPreference<Preference>("player_speed_options")?.setOnPreferenceClickListener { showSpeedOptionsDialog(); true }
        findPreference<Preference>("customize_hud")?.let { preference ->
            preference.isVisible = !requireContext().isTelevision()
            if (preference.isVisible) {
                preference.setOnPreferenceClickListener {
                    findNavController().navigate(R.id.playerHudEditorFragment)
                    true
                }
            }
        }
    }

    private fun showSpeedOptionsDialog() {
        val items = readSpeedItems()
        val listAdapter = SettingsDragListAdapter().apply {
            showVisibilityToggle = true
        }
        val itemTouchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    Collections.swap(items, viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    listAdapter.notifyItemMoved(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun isLongPressDragEnabled(): Boolean = false
            }
        )
        listAdapter.itemTouchHelper = itemTouchHelper
        val recyclerView = RecyclerView(requireContext()).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = listAdapter
            val padding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10F, resources.displayMetrics).toInt()
            setPadding(0, padding, 0, 0)
        }
        lateinit var dialog: AlertDialog
        val listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                text = "Drag to reorder. Uncheck to hide."
                // Leave room for the editable list on short landscape screens.
                isVisible = resources.configuration.screenHeightDp >= 400
                val padding = (24 * resources.displayMetrics.density).toInt()
                setPadding(padding, 0, padding, padding / 2)
            })
            addView(
                recyclerView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.42f).toInt(),
                    1f,
                ),
            )
            addView(
                com.google.android.material.button.MaterialButton(
                    context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
                ).apply {
                    text = "Add custom speed"
                    setOnClickListener {
                        dialog.dismiss()
                        showCustomSpeedDialog(items)
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    val margin = (24 * resources.displayMetrics.density).toInt()
                    marginStart = margin
                    marginEnd = margin
                },
            )
        }
        itemTouchHelper.attachToRecyclerView(recyclerView)
        listAdapter.submitList(items)
        dialog = requireActivity().getAlertDialogBuilder()
            .setTitle(R.string.settings_playback_speed_options)
            .setView(listContainer)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                saveSpeeds(items)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showCustomSpeedDialog(items: MutableList<SettingsDragListItem>) {
        // Opening the custom-speed dialog dismisses the editor. Keep its current in-memory state
        // before opening the second dialog.
        saveSpeeds(items)
        val input = android.widget.EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "> 0 and ≤ 16"
        }
        val dialog = requireActivity().getAlertDialogBuilder()
            .setTitle("Add custom speed")
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = input.text.toString().toFloatOrNull()
            if (value == null || !value.isFinite() || value <= 0f || value > 16f) {
                input.error = "Enter a speed greater than 0 and at most 16."
            } else {
                if (items.none { it.key.toFloatOrNull() == value }) {
                    items.add(SettingsDragListItem(value.toString(), "${value}×", default = false, enabled = true))
                    saveSpeeds(items)
                }
                dialog.dismiss()
            }
        }
    }

    private fun readSpeedItems(): MutableList<SettingsDragListItem> {
        val serialized = requireContext().prefs().getString(C.SETTINGS_PLAYER_SPEED_OPTIONS, null)
        val values = serialized?.split(',')?.mapNotNull { item ->
            val parts = item.split(':')
            val speed = parts.firstOrNull()?.toDoubleOrNull()?.takeIf {
                it.toFloat().isFinite() && it.toFloat() > 0f && it <= 16.0
            }
            speed?.let {
                SettingsDragListItem(
                    key = it.toString(),
                    text = "${it}×",
                    default = false,
                    enabled = parts.getOrNull(1)?.let { enabled -> enabled == "1" || enabled.equals("true", true) } ?: true,
                )
            }
        }?.distinctBy { it.key }
        return (values ?: listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0, 4.0, 8.0).map {
            SettingsDragListItem(it.toString(), "${it}×", default = false, enabled = true)
        }).toMutableList()
    }

    private fun saveSpeeds(items: List<SettingsDragListItem>) {
        requireContext().prefs().edit {
            putString(C.SETTINGS_PLAYER_SPEED_OPTIONS, serializeSpeedOptions(items))
            putString(C.PLAYER_SPEED_LIST, items.filter { it.enabled }.joinToString("\n") { it.key })
        }
    }

}
