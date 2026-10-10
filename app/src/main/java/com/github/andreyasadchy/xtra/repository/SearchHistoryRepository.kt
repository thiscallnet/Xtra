package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.SearchHistoryDao
import com.github.andreyasadchy.xtra.model.ui.SearchHistoryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SearchHistoryRepository(
    private val searchHistoryDao: SearchHistoryDao,
) {

    fun getAll() = searchHistoryDao.getAll()

    suspend fun recordOpened(item: SearchHistoryItem) = withContext(Dispatchers.IO) {
        searchHistoryDao.recordOpened(item)
    }

    suspend fun delete(item: SearchHistoryItem) = withContext(Dispatchers.IO) {
        searchHistoryDao.delete(item.kind, item.refId)
    }

    suspend fun restore(items: List<SearchHistoryItem>) = withContext(Dispatchers.IO) {
        searchHistoryDao.restore(items)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        searchHistoryDao.deleteAll()
    }
}
