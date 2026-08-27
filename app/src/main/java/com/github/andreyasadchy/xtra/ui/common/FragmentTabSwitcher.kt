package com.github.andreyasadchy.xtra.ui.common

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle

/** Adds each child tab once and keeps inactive tab views created but not active. */
class FragmentTabSwitcher(
    private val fragmentManager: FragmentManager,
    private val containerId: Int,
    private val onSelected: (Fragment) -> Unit,
) {
    private val fragments = linkedMapOf<String, Fragment>()
    private val pendingAdds = mutableSetOf<String>()
    private var requestedKey: String? = null
    private var transactionInFlight = false

    fun show(key: String, create: () -> Fragment) {
        if (fragmentManager.isStateSaved) return
        fragments[key]
            ?: fragmentManager.findFragmentByTag(key)?.also { fragments[key] = it }
            ?: create().also { fragments[key] = it }
        requestedKey = key
        if (transactionInFlight) return
        commitRequestedSelection()
    }

    private fun commitRequestedSelection() {
        if (fragmentManager.isStateSaved) {
            transactionInFlight = false
            return
        }
        val key = requestedKey ?: return
        val target = fragments[key] ?: return
        val transaction = fragmentManager.beginTransaction().setReorderingAllowed(true)
        (fragments.values + fragmentManager.fragments).distinct().forEach { fragment ->
            if (fragment !== target && fragment.isAdded) {
                transaction.hide(fragment)
                transaction.setMaxLifecycle(fragment, Lifecycle.State.CREATED)
            }
        }
        if (target.isAdded) {
            transaction.show(target)
        } else if (key !in pendingAdds) {
            transaction.add(containerId, target, key)
            pendingAdds += key
        }
        transaction.setMaxLifecycle(target, Lifecycle.State.RESUMED)
        transaction.runOnCommit {
            transactionInFlight = false
            pendingAdds.remove(key)
            if (requestedKey == key) {
                onSelected(target)
            } else {
                // A rapid tab sequence should settle on the latest request;
                // do not run setup work for every intermediate selection.
                commitRequestedSelection()
            }
        }
        transactionInFlight = true
        transaction.commit()
    }
}
