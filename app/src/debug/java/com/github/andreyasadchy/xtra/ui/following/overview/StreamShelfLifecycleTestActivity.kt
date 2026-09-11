package com.github.andreyasadchy.xtra.ui.following.overview

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity

/** A debug-only host for stream shelf lifecycle instrumentation tests. */
class StreamShelfLifecycleTestActivity : FragmentActivity() {
    lateinit var root: FrameLayout
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = FrameLayout(this).apply {
            id = View.generateViewId()
        }
        setContentView(root)
    }
}
