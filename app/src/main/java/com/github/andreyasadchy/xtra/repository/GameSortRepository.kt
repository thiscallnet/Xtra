package com.github.andreyasadchy.xtra.repository

import com.github.andreyasadchy.xtra.db.GameSortDao
import com.github.andreyasadchy.xtra.model.ui.GameSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GameSortRepository(
    private val gameSortDao: GameSortDao,
) {

    suspend fun getById(id: String) = withContext(Dispatchers.IO) {
        gameSortDao.getById(id)
    }

    suspend fun save(item: GameSort) = withContext(Dispatchers.IO) {
        gameSortDao.insert(item)
    }

    suspend fun deleteStreamSort(id: String) = withContext(Dispatchers.IO) {
        gameSortDao.deleteStreamSort(id)
    }

    suspend fun deleteVideoSort(id: String) = withContext(Dispatchers.IO) {
        gameSortDao.deleteVideoSort(id)
    }

    suspend fun deleteClipSort(id: String) = withContext(Dispatchers.IO) {
        gameSortDao.deleteClipSort(id)
    }
}
