package com.github.andreyasadchy.xtra.ui.channel.about

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.res.use
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentAboutBinding
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentArgs
import com.github.andreyasadchy.xtra.ui.channel.about.ChannelAboutViewModel.Companion.ChannelAboutViewModelFactory
import com.github.andreyasadchy.xtra.ui.common.BaseNetworkFragment
import com.github.andreyasadchy.xtra.ui.team.TeamFragmentDirections
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.tokenPrefs
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ChannelAboutFragment : BaseNetworkFragment(), com.github.andreyasadchy.xtra.ui.common.Scrollable {

    override val initializeWithoutNetwork = true

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!
    private val args: ChannelPagerFragmentArgs by navArgs()
    private val viewModel: ChannelAboutViewModel by viewModels { ChannelAboutViewModelFactory }
    private val channelViewModel: com.github.andreyasadchy.xtra.ui.channel.ChannelPagerViewModel by viewModels(ownerProducer = { requireParentFragment() })
    private var panelAdapter: ChannelPanelAdapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        panelAdapter = ChannelPanelAdapter(this@ChannelAboutFragment)
        binding.recyclerView.adapter = panelAdapter
        binding.retry.setOnClickListener { loadDetails() }
        binding.scheduleRetry.setOnClickListener { loadDetails() }
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, windowInsets ->
            if (activity?.findViewById<LinearLayout>(R.id.navBarContainer)?.isVisible == false) {
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                binding.aboutScroll.updatePadding(bottom = insets.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun initialize() {
        with(binding) {
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.description.collectLatest {
                        if (!it.isNullOrBlank()) {
                            description.visibility = View.VISIBLE
                            description.text = it
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.socialMedias.collectLatest { result ->
                        if (result != null) {
                            socialMediaList.visibility = View.VISIBLE
                            socialMediaList.removeAllViews()
                            result.forEach { (title, url) ->
                                val uri = url?.toUri()?.takeIf { it.scheme in listOf("https", "http") && !it.host.isNullOrBlank() }
                                if (!title.isNullOrBlank() && uri != null) {
                                    socialMediaList.addView(com.google.android.material.chip.Chip(requireContext()).apply {
                                        text = title
                                        isCheckable = false
                                        isClickable = true
                                        setEnsureMinTouchTargetSize(true)
                                        setOnClickListener {
                                            try {
                                                startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
                                            } catch (_: ActivityNotFoundException) {
                                                Toast.makeText(requireContext(), R.string.no_browser_found, Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    })
                                }
                            }
                            socialMediaList.isVisible = socialMediaList.childCount > 0
                            linksHeading.isVisible = socialMediaList.childCount > 0
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.team.collectLatest { result ->
                        if (result != null) {
                            val name = result.first
                            val displayName = result.second
                            if (!displayName.isNullOrBlank()) {
                                team.visibility = View.VISIBLE
                                val string = getString(R.string.team, displayName)
                                val index = string.indexOf(displayName)
                                val spannableString = SpannableString(string)
                                spannableString.setSpan(StyleSpan(Typeface.BOLD), index, index + displayName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                if (name != null) {
                                    spannableString.setSpan(object : ClickableSpan() {
                                        override fun onClick(widget: View) {
                                            findNavController().navigate(
                                                TeamFragmentDirections.actionGlobalTeamFragment(
                                                    teamName = name,
                                                )
                                            )
                                        }
                                    }, index, index + displayName.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    team.movementMethod = LinkMovementMethod.getInstance()
                                }
                                team.text = spannableString
                            }
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.originalName.collectLatest {
                        if (!it.isNullOrBlank()) {
                            originalName.visibility = View.VISIBLE
                            originalName.text = getString(R.string.old_username, it)
                        }
                    }
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    viewModel.panels.collectLatest { result ->
                        panelAdapter?.submitList(result)
                        panelsHeading.isVisible = !result.isNullOrEmpty()
                    }
                }
            }
        }
        observeDetails()
        loadDetails()
    }

    private fun observeDetails() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collectLatest { state ->
                        with(binding) {
                            loading.isVisible = state == ChannelAboutViewModel.LoadState.Loading
                            retry.isVisible = state == ChannelAboutViewModel.LoadState.Failed
                            val hasAbout = !viewModel.description.value.isNullOrBlank() || !viewModel.team.value?.second.isNullOrBlank() || !viewModel.originalName.value.isNullOrBlank()
                            aboutCard.isVisible = hasAbout
                            val empty = !hasAbout && viewModel.socialMedias.value.isNullOrEmpty() && viewModel.panels.value.isNullOrEmpty()
                            status.isVisible = state == ChannelAboutViewModel.LoadState.Failed || (state == ChannelAboutViewModel.LoadState.Ready && empty)
                            status.setText(if (state == ChannelAboutViewModel.LoadState.Failed) R.string.channel_about_failed else R.string.channel_about_empty)
                        }
                    }
                }
                launch {
                    viewModel.scheduleState.collectLatest { state ->
                        binding.scheduleLoading.isVisible = state == ChannelAboutViewModel.LoadState.Loading
                        binding.scheduleRetry.isVisible = state == ChannelAboutViewModel.LoadState.Failed
                        renderSchedule()
                        binding.scheduleStatus.isVisible = state != ChannelAboutViewModel.LoadState.Loading && (state == ChannelAboutViewModel.LoadState.Failed || binding.scheduleList.childCount == 0)
                        binding.scheduleStatus.setText(if (state == ChannelAboutViewModel.LoadState.Failed) R.string.channel_schedule_failed else R.string.channel_schedule_empty)
                    }
                }
                launch {
                    channelViewModel.isFollowing.collectLatest { following ->
                        if (following != null) loadConnection(revalidate = true)
                    }
                }
                launch {
                    viewModel.connection.collectLatest { connection ->
                        val lines = buildList {
                            connection?.followedAt?.let { kotlin.time.Instant.parseOrNull(it) }?.let {
                                add(getString(R.string.channel_followed_since, TwitchApiHelper.formatDate(requireContext(), it.toEpochMilliseconds())))
                            }
                            if (connection?.subscribed == true && (connection.months ?: 0) <= 0) add(getString(R.string.channel_subscribed))
                            connection?.months?.takeIf { it > 0 }?.let { months ->
                                add(resources.getQuantityString(
                                    if (connection.subscribed) R.plurals.user_card_subscribed_months else R.plurals.user_card_previously_subbed_months,
                                    months, months,
                                ))
                            }
                        }
                        binding.connectionCard.isVisible = lines.isNotEmpty()
                        binding.connection.text = lines.joinToString("\n")
                    }
                }
            }
        }
    }

    private fun renderSchedule() {
        binding.scheduleList.removeAllViews()
        val now = kotlin.time.Clock.System.now()
        viewModel.schedule.value.mapNotNull { item ->
            val start = item.start?.let { kotlin.time.Instant.parseOrNull(it) } ?: return@mapNotNull null
            val end = item.end?.let { kotlin.time.Instant.parseOrNull(it) } ?: return@mapNotNull null
            if (end <= now) return@mapNotNull null
            Triple(item, start, end)
        }.sortedBy { it.second }.forEach { (item, start, end) ->
            val date = android.text.format.DateUtils.formatDateTime(requireContext(), start.toEpochMilliseconds(), android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_SHOW_DATE)
            val timeFormat = android.text.format.DateFormat.getTimeFormat(requireContext())
            val dateFormat = android.text.format.DateFormat.getDateFormat(requireContext())
            val startDate = java.util.Date(start.toEpochMilliseconds())
            val endDate = java.util.Date(end.toEpochMilliseconds())
            val endLabel = if (dateFormat.format(startDate) == dateFormat.format(endDate)) {
                timeFormat.format(endDate)
            } else {
                "${android.text.format.DateUtils.formatDateTime(requireContext(), end.toEpochMilliseconds(), android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or android.text.format.DateUtils.FORMAT_SHOW_DATE)}, ${timeFormat.format(endDate)}"
            }
            val times = getString(R.string.channel_schedule_time_range, timeFormat.format(startDate), endLabel)
            binding.scheduleList.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                val gap = (12 * resources.displayMetrics.density).toInt()
                setPadding(0, gap, 0, gap)
                addView(TextView(context).apply {
                    text = date
                    context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.textAppearanceLabelLarge)).use {
                        TextViewCompat.setTextAppearance(this, it.getResourceId(0, 0))
                    }
                    setTextColor(com.google.android.material.color.MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
                })
                addView(TextView(context).apply {
                    text = times
                    context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.textAppearanceBodyMedium)).use {
                        TextViewCompat.setTextAppearance(this, it.getResourceId(0, 0))
                    }
                })
                addView(TextView(context).apply {
                    text = item.title?.takeIf { it.isNotBlank() } ?: getString(R.string.channel_schedule_broadcast)
                    context.obtainStyledAttributes(intArrayOf(com.google.android.material.R.attr.textAppearanceBodyLarge)).use {
                        TextViewCompat.setTextAppearance(this, it.getResourceId(0, 0))
                    }
                })
                if (!item.category.isNullOrBlank()) addView(TextView(context).apply { text = item.category })
            })
        }
    }

    private fun loadDetails() {
        val network = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val headers = TwitchApiHelper.getGQLHeaders(requireContext())
        viewModel.loadAbout(args.channelId, args.channelLogin, network, headers)
        viewModel.loadSchedule(args.channelId, args.channelLogin, network, headers)
        loadConnection()
    }

    private fun loadConnection(revalidate: Boolean = false) {
        val network = requireContext().prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val authenticated = TwitchApiHelper.getWebGQLHeaders(requireContext())
        if (!authenticated[C.HEADER_TOKEN].isNullOrBlank()) {
            viewModel.loadConnection(requireContext().tokenPrefs().getString(C.USER_ID, null), args.channelId, network, authenticated, revalidate)
        }
    }

    override fun onNetworkRestored() = loadDetails()

    override fun scrollToTop() { binding.aboutScroll.smoothScrollTo(0, 0) }

    override fun onDestroyView() {
        binding.recyclerView.adapter = null
        panelAdapter = null
        super.onDestroyView()
        _binding = null
    }
}
