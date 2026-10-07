package com.github.andreyasadchy.xtra.ui.whispers

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchInboxError
import com.github.andreyasadchy.xtra.model.twitchinbox.TwitchUserSummary
import com.github.andreyasadchy.xtra.repository.WhispersRepository
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.tokenPrefs
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Changes only isolated fixture preferences; never signs the debug app out or sends a request. */
@RunWith(AndroidJUnit4::class)
class WhisperAccountWorkTest {
    @Test
    fun changingFixtureAccountCancelsOldWorkAndClearsTheDraft() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureNames = mutableSetOf<String>()
        val prefix = "whisper-account-fixture-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                fixtureNames.add(prefix + name)
                return base.getSharedPreferences(prefix + name, mode)
            }
        }
        context.tokenPrefs().edit().putString(C.USER_ID, "fixture-old").commit()
        val module = (base.applicationContext as XtraApp).xtraModule
        val repository = WhispersRepository(context, module.twitchPrivateGqlClient, module.graphQLRepository)
        val canceled = CompletableDeferred<Unit>()
        var viewModel: WhisperThreadViewModel? = null
        try {
            withContext(Dispatchers.Main) {
                val model = WhisperThreadViewModel(repository, TwitchUserSummary("fixture", "fixture", "Fixture", null), null)
                viewModel = model
                model.setComposer("Draft belonging to the old account")
                model.viewModelScope.launch {
                    try { awaitCancellation() } finally { canceled.complete(Unit) }
                }
                context.tokenPrefs().edit().putString(C.USER_ID, "fixture-new").commit()
                model.refreshLatest()
                assertEquals("", model.uiState.value.composer)
                assertEquals(TwitchInboxError.SignedOut, model.uiState.value.error)
                assertFalse(model.uiState.value.initialLoading)
            }
            withTimeout(5_000L) { canceled.await() }
        } finally {
            viewModel?.viewModelScope?.cancel()
            fixtureNames.forEach { base.deleteSharedPreferences(it) }
        }
    }
}
