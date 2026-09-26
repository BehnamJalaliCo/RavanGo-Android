package com.ravango.feature.camera.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ravango.feature.beauty.looks.LookController
import com.ravango.feature.beauty.looks.LookDef
import com.ravango.feature.beauty.looks.LooksState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Looks side of the Camera Studio lens tray: applies complete looks through the shared [LookController] (the same
 * looks, favourites and recents as the Beauty panel). Lenses and filters keep going through the camera view model,
 * which owns their Pro gating.
 */
@HiltViewModel
class LensTrayViewModel @Inject constructor(private val looks: LookController) : ViewModel() {

    val state: StateFlow<LooksState> = looks.state

    fun applyLook(look: LookDef) {
        looks.apply(look.id)
    }

    fun clearLook() = looks.clear()

    fun setLookIntensity(value: Int) = looks.setIntensity(value)

    fun toggleFavourite(key: String) = looks.toggleFavourite(key)

    fun recordRecent(key: String) = looks.recordRecent(key)
}

/** The tray's looks state plus the look intents, for the stateful Camera Studio screen. */
internal class LensTrayBinding(val looks: LooksState, val viewModel: LensTrayViewModel)

@Composable
internal fun rememberLensTray(): LensTrayBinding {
    val viewModel: LensTrayViewModel = hiltViewModel()
    val looks by viewModel.state.collectAsStateWithLifecycle()
    return remember(looks, viewModel) { LensTrayBinding(looks, viewModel) }
}
