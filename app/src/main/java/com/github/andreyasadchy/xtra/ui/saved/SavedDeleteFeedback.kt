package com.github.andreyasadchy.xtra.ui.saved

import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.github.andreyasadchy.xtra.R
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

fun Fragment.installSavedDeleteFeedback(view: View, failure: StateFlow<Boolean>, acknowledge: () -> Unit) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            failure.collect { failed ->
                if (failed) {
                    Snackbar.make(view, R.string.saved_delete_failed, Snackbar.LENGTH_LONG).show()
                    acknowledge()
                }
            }
        }
    }
}
