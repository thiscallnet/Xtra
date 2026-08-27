package com.github.andreyasadchy.xtra.ui.common

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.UiInteractionGovernor

/** Receives the parent ViewPager2 scroll state before child content is moved. */
interface PagerScrollStateAware {
    fun onPagerScrollStateChanged(scrolling: Boolean)
}

fun FragmentManager.dispatchPagerScrollState(scrolling: Boolean) {
    fragments.forEach { (it as? PagerScrollStateAware)?.onPagerScrollStateChanged(scrolling) }
}

fun Fragment.dispatchPagerScrollState(scrolling: Boolean) {
    UiInteractionGovernor.setInteracting(this, scrolling)
    (activity?.application as? XtraApp)?.xtraModule?.streamPreviewCoordinator?.onPagerScrollStateChanged(scrolling)
    childFragmentManager.dispatchPagerScrollState(scrolling)
}
