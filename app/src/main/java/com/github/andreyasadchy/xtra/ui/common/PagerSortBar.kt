package com.github.andreyasadchy.xtra.ui.common

import android.os.Bundle
import android.view.View
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.github.andreyasadchy.xtra.databinding.SortBarBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

fun Fragment.installPagerSortBar(pager: ViewPager2, bar: SortBarBinding) {
    var active = true
    var boundPage: Fragment? = null
    fun clear() {
        boundPage = null
        bar.root.isVisible = false
        bar.root.setOnClickListener(null)
    }
    fun update() {
        if (!active) return
        val page = childFragmentManager.findFragmentByTag("f${pager.currentItem}")?.takeIf { it.view != null }
        if (page != null && page === boundPage) return
        clear()
        boundPage = page
        (page as? Sortable)?.setupSortBar(bar)
    }
    val pages = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = update()
    }
    val fragments = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) = update()
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) = update()
        override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
            if (f === boundPage) clear()
        }
    }
    childFragmentManager.registerFragmentLifecycleCallbacks(fragments, false)
    pager.registerOnPageChangeCallback(pages)
    viewLifecycleOwner.lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            active = false
            pager.unregisterOnPageChangeCallback(pages)
            childFragmentManager.unregisterFragmentLifecycleCallbacks(fragments)
            clear()
            owner.lifecycle.removeObserver(this)
        }
    })
    pager.doOnLayout { update() }
    update()
}

fun Fragment.isCurrentSortPage(): Boolean =
    isResumed && view != null && !childFragmentManager.isStateSaved && (parentFragment as? FragmentHost)?.currentFragment === this

fun Fragment.collectSelectedSortBar(
    bar: SortBarBinding,
    sort: StateFlow<CharSequence?>,
    filters: StateFlow<CharSequence?>? = null,
): Job {
    bar.sortText.text = sort.value
    bar.filtersText.text = filters?.value
    bar.filtersText.isVisible = filters?.value != null
    return viewLifecycleOwner.lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                sort.collect {
                    if ((parentFragment as? FragmentHost)?.currentFragment === this@collectSelectedSortBar) bar.sortText.text = it
                }
            }
            if (filters != null) launch {
                filters.collect {
                    if ((parentFragment as? FragmentHost)?.currentFragment === this@collectSelectedSortBar) {
                        bar.filtersText.text = it
                        bar.filtersText.isVisible = it != null
                    }
                }
            }
        }
    }
}
