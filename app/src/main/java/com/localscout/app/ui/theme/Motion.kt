package com.localscout.app.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring

/**
 * M3 Expressive motion tokens — the exact spring physics from
 * md.sys.motion.spring.expressive.* (extracted from material3 1.4.0's
 * ExpressiveMotionTokens; the public MotionScheme API lands in m3 1.5,
 * which needs compileSdk 37 — not worth an alpha toolchain avalanche).
 *
 * Two families per the M3 motion spec:
 *  - Spatial: things that MOVE on screen (position, size, rotation).
 *    Overshoots and bounces into place — the signature M3 Expressive feel.
 *  - Effects: alpha/color changes. Critically damped — no overshoot,
 *    settles fast, never flickers past its target.
 *
 * Three speeds per family:
 *  - fast: chips, small cards, micro-interactions
 *  - default: cards, panels, state changes
 *  - slow: full-screen, hero transitions
 */
object ExpressiveMotion {

    // ── Spatial springs (overshoot + bounce) ────────────────────────────
    fun <T> fastSpatial(): AnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)

    fun <T> defaultSpatial(): AnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)

    fun <T> slowSpatial(): AnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)

    // ── Effects springs (no overshoot, critically damped) ───────────────
    fun <T> fastEffects(): AnimationSpec<T> = spring(dampingRatio = 1.0f, stiffness = 3800f)

    fun <T> defaultEffects(): AnimationSpec<T> = spring(dampingRatio = 1.0f, stiffness = 1600f)

    fun <T> slowEffects(): AnimationSpec<T> = spring(dampingRatio = 1.0f, stiffness = 800f)
}