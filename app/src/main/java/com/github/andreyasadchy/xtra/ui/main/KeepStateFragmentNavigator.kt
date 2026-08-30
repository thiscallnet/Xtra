package com.github.andreyasadchy.xtra.ui.main

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavOptions
import androidx.navigation.Navigator
import androidx.navigation.fragment.FragmentNavigator
import com.github.andreyasadchy.xtra.R

/**
 * Fragment navigator used by the main screen.
 *
 * The stock navigator replaces the host's fragment on every destination change. That is a poor fit
 * for the six long-lived bottom tabs: replacing a tab destroys its entire view hierarchy and makes
 * the next click synchronously rebuild nested pagers and shelves. Keep those fragments attached and
 * switch them with hide/show instead. Detail destinations are removed when navigation moves past
 * them so a long browsing session does not retain every detail view; the parent tab remains alive.
 */
@Navigator.Name("fragment")
class KeepStateFragmentNavigator(
    private val appContext: Context,
    private val fragmentManager: FragmentManager,
    private val hostContainerId: Int,
) : FragmentNavigator(appContext, fragmentManager, hostContainerId) {

    var onNavigationTransactionCommitted: ((destinationId: Int?) -> Unit)? = null

    private val entryFragments = mutableMapOf<String, Fragment>()
    private val pendingFragments = mutableMapOf<String, Fragment>()
    private val preservedFragments = mutableMapOf<Int, Fragment>()
    private val lastUsedTabAt = mutableMapOf<Int, Long>()
    private val destinationByTag = mutableMapOf<String, Int>()
    private var usageSequence = 0L
    private var hiddenTabTrimPending = false

    private val tabDestinationIds = setOf(
        R.id.rootGamesFragment,
        R.id.rootDiscoverFragment,
        R.id.rootTopFragment,
        R.id.followPagerFragment,
        R.id.savedPagerFragment,
        R.id.statisticsFragment,
        R.id.dropsFragment,
    )

    override fun navigate(
        entries: List<NavBackStackEntry>,
        navOptions: NavOptions?,
        navigatorExtras: Navigator.Extras?,
    ) {
        if (fragmentManager.isStateSaved || entries.isEmpty()) {
            if (fragmentManager.isStateSaved) {
                Log.i(TAG, "Ignoring navigate() because FragmentManager has already saved state")
            }
            return
        }

        val currentEntry = state.backStack.value.lastOrNull()
        var outgoing = currentEntry?.let(::findFragment)
            ?: fragmentManager.primaryNavigationFragment
        val transaction = fragmentManager.beginTransaction().setReorderingAllowed(true)
        val outgoingDestinationId = currentEntry?.destination?.id
        var transactionOutgoingDestinationId = outgoingDestinationId
        val createdInThisNavigation = mutableSetOf<Fragment>()
        val addedInThisTransaction = mutableSetOf<Fragment>()

        entries.forEach { entry ->
            val destinationId = entry.destination.id
            val fragment = findFragment(entry) ?: createFragment(entry).also {
                registerNewFragment(entry, it)
                createdInThisNavigation += it
            }

            if (outgoing != null && outgoing !== fragment) {
                if (transactionOutgoingDestinationId != null && isTabDestination(transactionOutgoingDestinationId)) {
                    transaction.hide(outgoing)
                    transaction.setMaxLifecycle(outgoing, Lifecycle.State.CREATED)
                } else if (outgoing !in createdInThisNavigation) {
                    transaction.remove(outgoing)
                    forget(outgoing)
                }
            }

            if (
                fragment.isAdded ||
                fragment in addedInThisTransaction ||
                (fragment !in createdInThisNavigation && isPending(fragment)) ||
                fragment.tag?.let { fragmentManager.findFragmentByTag(it) === fragment } == true
            ) {
                transaction.show(fragment)
            } else {
                transaction.add(hostContainerId, fragment, fragment.tag ?: entry.id)
                addedInThisTransaction += fragment
            }

            outgoing = fragment
            transactionOutgoingDestinationId = destinationId
        }

        val incoming = requireNotNull(outgoing)
        val incomingDestinationId = entries.last().destination.id
        // Navigation can recover with a stale non-tab fragment still attached when the
        // navigator's bookkeeping was interrupted by a process/lifecycle transition. The
        // current-entry cleanup above is not sufficient in that case: remove or hide every
        // other attached fragment before committing the new primary destination so global
        // routes (for example Notifications -> Drops) cannot render on top of each other.
        fragmentManager.fragments
            .filter { it.isAdded && it !== incoming }
            .forEach { fragment ->
                val destinationId = fragment.tag?.let(destinationByTag::get)
                if (fragment in preservedFragments.values || destinationId?.let(::isTabDestination) == true) {
                    transaction.hide(fragment)
                    transaction.setMaxLifecycle(fragment, Lifecycle.State.CREATED)
                } else {
                    transaction.remove(fragment)
                    forget(fragment)
                }
            }
        evictLeastRecentlyUsedTabIfNeeded(
            transaction = transaction,
            incomingDestinationId = incomingDestinationId,
            outgoing = outgoing,
        )
        if (shouldAnimate(outgoingDestinationId, incomingDestinationId, navOptions)) {
            transaction.setCustomAnimations(
                navOptions?.enterAnim ?: 0,
                navOptions?.exitAnim ?: 0,
                navOptions?.popEnterAnim ?: 0,
                navOptions?.popExitAnim ?: 0,
            )
        }
        transaction.setPrimaryNavigationFragment(incoming)
        transaction.setMaxLifecycle(incoming, Lifecycle.State.RESUMED)

        val entriesToComplete = entries.toList()
        transaction.runOnCommit {
            entriesToComplete.forEach { entry ->
                pendingFragments.remove(entry.id)
                state.markTransitionComplete(entry)
            }
            onNavigationTransactionCommitted?.invoke(incomingDestinationId)
        }
        transaction.commit()

        entries.forEach { entry ->
            state.pushWithTransition(entry)
        }
    }

    override fun onLaunchSingleTop(backStackEntry: NavBackStackEntry) {
        if (fragmentManager.isStateSaved) {
            Log.i(TAG, "Ignoring onLaunchSingleTop() because FragmentManager has already saved state")
            return
        }

        val currentEntry = state.backStack.value.lastOrNull()
        val currentFragment = currentEntry?.let(::findFragment)
        if (currentFragment != null &&
            currentEntry.destination.id == backStackEntry.destination.id &&
            isTabDestination(backStackEntry.destination.id)
        ) {
            entryFragments[backStackEntry.id] = currentFragment
            destinationByTag[backStackEntry.id] = backStackEntry.destination.id
            if (isTabDestination(backStackEntry.destination.id)) {
                preservedFragments[backStackEntry.destination.id] = currentFragment
                markTabUsed(backStackEntry.destination.id)
            }
            state.onLaunchSingleTop(backStackEntry)
            return
        }

        // This is only a fallback for callers that use launchSingleTop outside the bottom bar. The
        // stock implementation remains the safest behavior when there is no reusable fragment.
        super.onLaunchSingleTop(backStackEntry)
    }

    override fun popBackStack(popUpTo: NavBackStackEntry, savedState: Boolean) {
        if (fragmentManager.isStateSaved) {
            Log.i(TAG, "Ignoring popBackStack() because FragmentManager has already saved state")
            return
        }

        val backStack = state.backStack.value
        val popUpToIndex = backStack.indexOf(popUpTo)
        if (popUpToIndex < 0) {
            Log.w(TAG, "Ignoring popBackStack() for an entry not in the navigator back stack")
            return
        }

        val poppedEntries = backStack.subList(popUpToIndex, backStack.size).toList()
        val incomingEntry = backStack.getOrNull(popUpToIndex - 1)
        val transaction = fragmentManager.beginTransaction().setReorderingAllowed(true)
        val addedInThisTransaction = mutableSetOf<Fragment>()

        poppedEntries.asReversed().forEach { entry ->
            val fragment = findFragment(entry) ?: return@forEach
            if (savedState && isTabDestination(entry.destination.id)) {
                transaction.hide(fragment)
                transaction.setMaxLifecycle(fragment, Lifecycle.State.CREATED)
            } else {
                transaction.remove(fragment)
                forget(fragment)
                if (isTabDestination(entry.destination.id)) {
                    preservedFragments.remove(entry.destination.id)
                    lastUsedTabAt.remove(entry.destination.id)
                }
            }
        }

        incomingEntry?.let { entry ->
            val existingFragment = findFragment(entry)
            val fragment = existingFragment ?: createFragment(entry).also { registerNewFragment(entry, it) }
            if (
                fragment.isAdded ||
                fragment in addedInThisTransaction ||
                (existingFragment != null && isPending(fragment)) ||
                fragment.tag?.let { fragmentManager.findFragmentByTag(it) === fragment } == true
            ) {
                transaction.show(fragment)
            } else {
                transaction.add(hostContainerId, fragment, fragment.tag ?: entry.id)
                addedInThisTransaction += fragment
            }
            transaction.setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
            transaction.setPrimaryNavigationFragment(fragment)
        }

        transaction.runOnCommit {
            poppedEntries.forEach { entry ->
                pendingFragments.remove(entry.id)
                state.markTransitionComplete(entry)
            }
            incomingEntry?.let {
                pendingFragments.remove(it.id)
                state.markTransitionComplete(it)
            }
            onNavigationTransactionCommitted?.invoke(incomingEntry?.destination?.id)
        }
        transaction.commit()
        state.popWithTransition(popUpTo, savedState)
    }

    override fun onSaveState(): Bundle? {
        val state = super.onSaveState() ?: Bundle()
        val tags = ArrayList<String>()
        val destinationIds = ArrayList<Int>()
        destinationByTag.forEach { (tag, destinationId) ->
            if (fragmentManager.findFragmentByTag(tag) != null) {
                tags += tag
                destinationIds += destinationId
            }
        }
        state.putStringArrayList(KEY_DESTINATION_TAGS, tags)
        state.putIntegerArrayList(KEY_DESTINATION_IDS, destinationIds)
        return state
    }

    override fun onRestoreState(savedState: Bundle) {
        super.onRestoreState(savedState)
        val tags = savedState.getStringArrayList(KEY_DESTINATION_TAGS).orEmpty()
        val destinationIds = savedState.getIntegerArrayList(KEY_DESTINATION_IDS).orEmpty()
        tags.zip(destinationIds).forEach { (tag, destinationId) ->
            destinationByTag[tag] = destinationId
        }
    }

    private fun createFragment(entry: NavBackStackEntry): Fragment {
        val destination = entry.destination as Destination
        var className = destination.className
        if (className.startsWith('.')) {
            className = appContext.packageName + className
        }
        return instantiateFragment(appContext, fragmentManager, className, entry.arguments).apply {
            arguments = entry.arguments
        }
    }

    private fun registerNewFragment(entry: NavBackStackEntry, fragment: Fragment) {
        pendingFragments[entry.id] = fragment
        entryFragments[entry.id] = fragment
        destinationByTag[entry.id] = entry.destination.id
        if (isTabDestination(entry.destination.id)) {
            preservedFragments[entry.destination.id] = fragment
            markTabUsed(entry.destination.id)
        }
    }

    private fun isPending(fragment: Fragment): Boolean = pendingFragments.values.any { it === fragment }

    private fun forget(fragment: Fragment) {
        entryFragments.filterValues { it === fragment }.keys.forEach(entryFragments::remove)
        pendingFragments.filterValues { it === fragment }.keys.forEach(pendingFragments::remove)
        fragment.tag?.let(destinationByTag::remove)
    }

    private fun findFragment(entry: NavBackStackEntry): Fragment? {
        entryFragments[entry.id]?.let { fragment ->
            if (isTabDestination(entry.destination.id)) markTabUsed(entry.destination.id)
            return fragment
        }
        fragmentManager.findFragmentByTag(entry.id)?.let { fragment ->
            remember(entry, fragment)
            return fragment
        }
        if (isTabDestination(entry.destination.id)) {
            preservedFragments[entry.destination.id]?.let { fragment ->
                remember(entry, fragment)
                return fragment
            }
            destinationByTag.entries.firstOrNull { it.value == entry.destination.id }
                ?.key
                ?.let(fragmentManager::findFragmentByTag)
                ?.let { fragment ->
                    remember(entry, fragment)
                    return fragment
                }
        }
        return null
    }

    private fun remember(entry: NavBackStackEntry, fragment: Fragment) {
        entryFragments[entry.id] = fragment
        destinationByTag[fragment.tag ?: entry.id] = entry.destination.id
        if (isTabDestination(entry.destination.id)) {
            preservedFragments[entry.destination.id] = fragment
            markTabUsed(entry.destination.id)
        }
    }

    private fun markTabUsed(destinationId: Int) {
        usageSequence++
        lastUsedTabAt[destinationId] = usageSequence
    }

    private fun evictLeastRecentlyUsedTabIfNeeded(
        transaction: FragmentTransaction,
        incomingDestinationId: Int,
        outgoing: Fragment?,
    ) {
        if (!isTabDestination(incomingDestinationId) ||
            preservedFragments.size <= MAX_PRESERVED_TABS
        ) {
            return
        }

        val candidate = preservedFragments.entries
            .asSequence()
            .filter { (destinationId, fragment) ->
                destinationId != incomingDestinationId &&
                    fragment !== outgoing &&
                    fragment.isAdded &&
                    fragment.isHidden &&
                    fragment.lifecycle.currentState == Lifecycle.State.CREATED &&
                    !isPending(fragment)
            }
            .minByOrNull { (destinationId, _) -> lastUsedTabAt[destinationId] ?: Long.MIN_VALUE }
            ?: return

        transaction.remove(candidate.value)
        preservedFragments.remove(candidate.key)
        lastUsedTabAt.remove(candidate.key)
        forget(candidate.value)
    }

    /** Drop hidden root views when Android reports process memory pressure. */
    fun trimHiddenTabs() {
        if (fragmentManager.isStateSaved || hiddenTabTrimPending) return
        val hiddenTabs = preservedFragments.entries.filter { (_, fragment) ->
            fragment.isAdded && fragment.isHidden && fragment.lifecycle.currentState == Lifecycle.State.CREATED
        }
        if (hiddenTabs.isEmpty()) return

        hiddenTabTrimPending = true
        val transaction = fragmentManager.beginTransaction().setReorderingAllowed(true)
        hiddenTabs.forEach { (destinationId, fragment) ->
            transaction.remove(fragment)
            preservedFragments.remove(destinationId)
            lastUsedTabAt.remove(destinationId)
            forget(fragment)
        }
        transaction.runOnCommit { hiddenTabTrimPending = false }
        transaction.commit()
    }

    private fun isTabDestination(destinationId: Int): Boolean = destinationId in tabDestinationIds

    private fun shouldAnimate(
        outgoingDestinationId: Int?,
        incomingDestinationId: Int,
        navOptions: NavOptions?,
    ): Boolean {
        if (navOptions == null) return false
        return !(outgoingDestinationId != null &&
            isTabDestination(outgoingDestinationId) &&
            isTabDestination(incomingDestinationId))
    }

    private companion object {
        const val TAG = "KeepStateNavigator"
        const val KEY_DESTINATION_TAGS = "xtra:destinationTags"
        const val KEY_DESTINATION_IDS = "xtra:destinationIds"
        const val MAX_PRESERVED_TABS = 3
    }
}
