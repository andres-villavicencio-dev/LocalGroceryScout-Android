package com.localscout.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import com.localscout.app.ui.theme.ExpressiveMotion

/**
 * M3 Expressive navigation transitions (per m3.material.io/styles/motion):
 *  - Top-level tabs (Search/Lists/History/Account): horizontal slide+fade —
 *    screens feel like one continuous space, "begin and end on screen".
 *  - Pushed routes (Scanner/Receipt/Settings): vertical slide-up + fade —
 *    modal-style, matching M3's sheet pattern for subsidiary flows.
 *  - Pops mirror the entrance.
 */
object NavTransitions {

    // Tab switches: horizontal slide (spatial spring — overshoots & settles)
    fun tabEnter(): EnterTransition =
        slideInHorizontally(animationSpec = ExpressiveMotion.defaultSpatial()) { it / 4 } +
            fadeIn(animationSpec = ExpressiveMotion.fastEffects())

    fun tabExit(): ExitTransition =
        slideOutHorizontally(animationSpec = ExpressiveMotion.defaultSpatial()) { it / 4 } +
            fadeOut(animationSpec = ExpressiveMotion.fastEffects())

    // Pushed routes: slide up from bottom (spatial spring)
    fun pushEnter(): EnterTransition =
        slideInVertically(animationSpec = ExpressiveMotion.defaultSpatial()) { it } +
            fadeIn(animationSpec = ExpressiveMotion.fastEffects())

    fun pushExit(): ExitTransition =
        slideOutVertically(animationSpec = ExpressiveMotion.defaultSpatial()) { it / 3 } +
            fadeOut(animationSpec = ExpressiveMotion.fastEffects())

    // Pop: content slides back down / away, previous screen fades in
    fun popEnter(): EnterTransition =
        slideInVertically(animationSpec = ExpressiveMotion.defaultSpatial()) { -it / 3 } +
            fadeIn(animationSpec = ExpressiveMotion.fastEffects())

    fun popExit(): ExitTransition =
        slideOutVertically(animationSpec = ExpressiveMotion.defaultSpatial()) { it } +
            fadeOut(animationSpec = ExpressiveMotion.fastEffects())
}