package com.github.andreyasadchy.xtra.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.repository.GraphQLRepository
import com.github.andreyasadchy.xtra.util.C
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class SearchPagerViewModel(
    private val graphQLRepository: GraphQLRepository,
) : ViewModel() {

    val userResult = MutableStateFlow<Pair<String?, String?>?>(null)
    private var isLoading = false

    fun loadUserResult(checkedId: Int, result: String, networkLibrary: String?, gqlHeaders: Map<String, String>) {
        if (userResult.value == null && !isLoading) {
            isLoading = true
            viewModelScope.launch {
                try {
                    userResult.value = if (checkedId == 0) {
                        val response = graphQLRepository.loadQueryUserResultID(networkLibrary, gqlHeaders, result)
                        response.data!!.userResultByID?.let {
                            when {
                                it.onUser != null -> Pair(null, null)
                                it.onUserDoesNotExist != null -> Pair(it.__typename, it.onUserDoesNotExist.reason)
                                it.onUserError != null -> Pair(it.__typename, null)
                                else -> null
                            }
                        }
                    } else {
                        val response = graphQLRepository.loadQueryUserResultLogin(networkLibrary, gqlHeaders, result)
                        response.data!!.userResultByLogin?.let {
                            when {
                                it.onUser != null -> Pair(null, null)
                                it.onUserDoesNotExist != null -> Pair(it.__typename, it.onUserDoesNotExist.reason)
                                it.onUserError != null -> Pair(it.__typename, null)
                                else -> null
                            }
                        }
                    }
                } catch (e: Exception) {

                } finally {
                    isLoading = false
                }
            }
        }
    }

    companion object {
        val SearchPagerViewModelFactory = viewModelFactory {
            initializer {
                val application = (this[APPLICATION_KEY] as XtraApp)
                val xtraModule = application.xtraModule
                SearchPagerViewModel(xtraModule.graphQLRepository)
            }
        }
    }
}
