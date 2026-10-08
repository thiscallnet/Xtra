package com.github.andreyasadchy.xtra.ui.settings

import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.navigation.NavDirections
import androidx.navigation.fragment.findNavController
import androidx.preference.forEach
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.SettingsNavGraphDirections
import com.github.andreyasadchy.xtra.databinding.FragmentSettingsHomeBinding
import com.github.andreyasadchy.xtra.databinding.ItemSettingsRowBinding
import com.github.andreyasadchy.xtra.repository.auth.AuthHealth
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.ui.common.ExpressiveShapeStyling
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.color.MaterialColors

class SettingsHomeFragment : Fragment() {

    private var _binding: FragmentSettingsHomeBinding? = null
    private val binding get() = _binding!!
    private var accountRow: ItemSettingsRowBinding? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.content.removeView(binding.accountSection)
        binding.content.addView(binding.accountSection, 0)
        val expressive = requireContext().prefs().getString(C.SETTINGS_UI_STYLE, "expressive") == "expressive"
        if (expressive) configureExpressiveSettingsHome()
        binding.searchCard.setOnClickListener {
            navigate(SettingsNavGraphDirections.actionGlobalSettingsSearchFragment())
        }
        settingsGroups().forEach { group ->
            if (expressive) {
                addExpressiveSettingsGroup(group)
            } else {
                addSectionHeader(group.title)
                group.items.forEachIndexed { index, item ->
                    val rowBinding = createSettingsRow(
                        parent = binding.sections,
                        item = item,
                        showDivider = index < group.items.lastIndex,
                        expressive = false,
                    )
                    binding.sections.addView(rowBinding.root)
                }
            }
        }
        accountRow = ItemSettingsRowBinding.inflate(layoutInflater, binding.accountActions, false)
        if (expressive) styleExpressiveSettingsRow(accountRow!!)
        binding.accountActions.addView(accountRow!!.root)
        renderAccountRow()
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = resources.getDimensionPixelSize(R.dimen.settings_section_spacing) * 2 + insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onResume() {
        super.onResume()
        renderAccountRow()
    }

    private fun renderAccountRow() {
        val row = accountRow ?: return
        val settingsActivity = requireActivity() as SettingsActivity
        val authHealth = settingsActivity.accountAuthHealth()
        val isLoggedIn = isSettingsAccountConnected(authHealth)
        val needsReauthentication = authHealth == AuthHealth.REAUTH_REQUIRED
        val username = requireContext().tokenPrefs().getString(C.USERNAME, null)?.takeIf { it.isNotBlank() }
        val accountSummary = when {
            needsReauthentication -> getString(R.string.auth_health_reauth_message)
            isLoggedIn -> getString(R.string.settings_account_connected_summary)
            else -> getString(R.string.settings_account_signed_out_summary)
        }
        row.icon.setImageResource(R.drawable.ic_settings_network)
        row.title.text = when {
            needsReauthentication -> getString(R.string.auth_health_reconnect)
            isLoggedIn -> username ?: getString(R.string.settings_account_details)
            else -> getString(R.string.log_in)
        }
        row.summary.text = accountSummary
        row.arrow.visibility = View.VISIBLE
        row.divider.visibility = View.GONE
        row.root.contentDescription = row.title.text.toString() + ". " + accountSummary
        row.root.setOnClickListener {
            navigate(R.id.accountSettingsFragment)
        }
    }

    private fun addSectionHeader(title: Int) {
        binding.sections.addView(TextView(requireContext()).apply {
            setText(title)
            textSize = 14f
            setTextColor(com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(16.dp(), 16.dp(), 16.dp(), 6.dp())
        })
    }

    private fun configureExpressiveSettingsHome() {
        ExpressiveShapeStyling.applyRippleSurface(
            binding.searchCard,
            com.google.android.material.R.attr.shapeAppearanceMediumComponent,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
        )
        binding.searchCard.minimumHeight = 56.dp()
        binding.searchCard.updateLayoutParams<LinearLayout.LayoutParams> {
            marginStart = 16.dp()
            topMargin = 16.dp()
            marginEnd = 16.dp()
        }

        binding.sections.updateLayoutParams<LinearLayout.LayoutParams> {
            marginStart = 16.dp()
            marginEnd = 16.dp()
        }
        binding.accountSection.updateLayoutParams<LinearLayout.LayoutParams> {
            marginStart = 16.dp()
            marginEnd = 16.dp()
            topMargin = 16.dp()
        }
        binding.accountActions.apply {
            ExpressiveShapeStyling.applySurface(
                this,
                com.google.android.material.R.attr.shapeAppearanceMediumComponent,
                com.google.android.material.R.attr.colorSurfaceContainerLow,
            )
            setPadding(0, 6.dp(), 0, 6.dp())
        }
        (binding.accountSection.getChildAt(0) as? TextView)?.apply {
            setTextColor(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant),
            )
            setPadding(16.dp(), 4.dp(), 16.dp(), 8.dp())
        }
    }

    private fun addExpressiveSettingsGroup(group: SettingsGroup) {
        val section = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 14.dp()
            }
        }
        section.addView(TextView(requireContext()).apply {
            setText(group.title)
            applyThemeTextAppearance(com.google.android.material.R.attr.textAppearanceTitleSmall)
            setTextColor(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant),
            )
            setPadding(16.dp(), 4.dp(), 16.dp(), 8.dp())
        })

        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            ExpressiveShapeStyling.applySurface(
                this,
                com.google.android.material.R.attr.shapeAppearanceMediumComponent,
                com.google.android.material.R.attr.colorSurfaceContainerLow,
            )
            setPadding(0, 4.dp(), 0, 4.dp())
        }
        group.items.forEach { item ->
            val rowBinding = createSettingsRow(
                parent = card,
                item = item,
                showDivider = false,
                expressive = true,
            )
            card.addView(rowBinding.root)
        }
        section.addView(card)
        binding.sections.addView(section)
    }

    private fun createSettingsRow(
        parent: ViewGroup,
        item: SettingsItem,
        showDivider: Boolean,
        expressive: Boolean,
    ): ItemSettingsRowBinding {
        val rowBinding = ItemSettingsRowBinding.inflate(layoutInflater, parent, false)
        rowBinding.icon.setImageResource(item.icon)
        rowBinding.title.setText(item.title)
        rowBinding.summary.setText(item.summary)
        rowBinding.root.contentDescription = getString(item.title) + ". " + getString(item.summary)
        rowBinding.divider.visibility = if (showDivider) View.VISIBLE else View.GONE
        rowBinding.root.setOnClickListener { item.onClick() }
        if (expressive) styleExpressiveSettingsRow(rowBinding)
        return rowBinding
    }

    private fun styleExpressiveSettingsRow(rowBinding: ItemSettingsRowBinding) {
        rowBinding.icon.updateLayoutParams<ViewGroup.LayoutParams> {
            width = 36.dp()
            height = 36.dp()
        }
        rowBinding.icon.apply {
            setPadding(8.dp(), 8.dp(), 8.dp(), 8.dp())
            ExpressiveShapeStyling.applySurface(
                this,
                com.google.android.material.R.attr.shapeAppearanceSmallComponent,
                com.google.android.material.R.attr.colorPrimaryContainer,
            )
            setColorFilter(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer),
            )
        }
        rowBinding.divider.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = 64.dp()
            marginEnd = 16.dp()
        }
    }

    private fun TextView.applyThemeTextAppearance(attribute: Int) {
        val value = TypedValue()
        if (context.theme.resolveAttribute(attribute, value, true) && value.resourceId != 0) {
            TextViewCompat.setTextAppearance(this, value.resourceId)
        }
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun settingsGroups(): List<SettingsGroup> = listOf(
        SettingsGroup(
            R.string.settings_section_watch,
            listOf(
                SettingsItem(R.string.settings_section_playback, R.drawable.ic_settings_playback, R.string.settings_home_playback_summary) {
                    navigate(SettingsNavGraphDirections.actionGlobalPlayerSettingsFragment())
                },
                SettingsItem(R.string.settings_home_controls, R.drawable.baseline_settings_black_24, R.string.settings_home_controls_summary) {
                    navigate(SettingsNavGraphDirections.actionGlobalPlayerButtonSettingsFragment())
                },
                SettingsItem(R.string.settings_section_chat, R.drawable.ic_settings_chat, R.string.settings_home_chat_summary) {
                    navigate(SettingsNavGraphDirections.actionGlobalChatSettingsFragment())
                },
                SettingsItem(R.string.settings_general_notifications, R.drawable.ic_settings_notifications, R.string.settings_home_notifications_summary) {
                    navigate(SettingsNavGraphDirections.actionGlobalLiveNotificationSettingsFragment())
                },
            ),
        ),
        SettingsGroup(
            R.string.settings_section_customize,
            listOf(
                SettingsItem(R.string.settings_section_appearance, R.drawable.ic_settings_appearance, R.string.settings_home_appearance_summary) {
                    navigate(SettingsNavGraphDirections.actionGlobalThemeSettingsFragment())
                },
                SettingsItem(R.string.settings_home_browsing, R.drawable.ic_settings_data, R.string.settings_home_browsing_summary) {
                    navigate(SettingsNavGraphDirections.actionGlobalUiSettingsFragment())
                },
            ),
        ),
        SettingsGroup(
            R.string.settings_section_app,
            listOf(
                SettingsItem(R.string.settings_section_downloads, R.drawable.ic_settings_download, R.string.settings_home_downloads_summary) {
                    navigate(SettingsNavGraphDirections.actionGlobalDownloadSettingsFragment())
                },
                SettingsItem(R.string.settings_app, R.drawable.ic_settings_data, R.string.settings_app_summary) {
                    findNavController().navigate(R.id.appSettingsFragment)
                },
            ),
        ),
        SettingsGroup(
            R.string.settings_help_about,
            listOf(
                SettingsItem(R.string.settings_general_updates, R.drawable.ic_settings_updates, R.string.settings_home_updates_summary) {
                    navigate(SettingsNavGraphDirections.actionGlobalUpdateSettingsFragment())
                },
                SettingsItem(R.string.settings_join_discord, R.drawable.ic_settings_discord, R.string.settings_join_discord_summary) {
                    (requireActivity() as SettingsActivity).openDiscord()
                },
            ),
        ),
        SettingsGroup(
            R.string.settings_section_advanced_tools,
            listOf(
                SettingsItem(R.string.settings_section_advanced, R.drawable.ic_settings_advanced, R.string.settings_home_advanced_summary) {
                    navigate(SettingsNavGraphDirections.actionGlobalDebugSettingsFragment())
                },
            ),
        ),
    )

    private fun navigate(directions: NavDirections) {
        requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
        findNavController().navigate(directions)
    }

    private fun navigate(destinationId: Int) {
        requireActivity().findViewById<AppBarLayout>(R.id.appBar)?.setExpanded(true)
        findNavController().navigate(destinationId)
    }

    override fun onDestroyView() {
        accountRow = null
        _binding = null
        super.onDestroyView()
    }

    private data class SettingsItem(
        @StringRes val title: Int,
        @DrawableRes val icon: Int,
        @StringRes val summary: Int,
        val onClick: () -> Unit
    )

    private data class SettingsGroup(
        @StringRes val title: Int,
        val items: List<SettingsItem>,
    )
}
