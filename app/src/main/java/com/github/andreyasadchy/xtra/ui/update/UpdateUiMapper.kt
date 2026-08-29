package com.github.andreyasadchy.xtra.ui.update

import com.github.andreyasadchy.xtra.util.updater.UpdateSelectedAssetInfo
import com.github.andreyasadchy.xtra.util.updater.UpdateState

object UpdateUiMapper {
    fun map(state: UpdateState, selectedAsset: UpdateSelectedAssetInfo? = null): UpdateUiModel =
        state.toUiModel(selectedAsset)
}
