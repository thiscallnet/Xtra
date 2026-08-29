package com.github.andreyasadchy.xtra.ui.update

import com.github.andreyasadchy.xtra.util.updater.UpdateState

object UpdateUiMapper {
    fun map(state: UpdateState): UpdateUiModel = state.toUiModel()
}
